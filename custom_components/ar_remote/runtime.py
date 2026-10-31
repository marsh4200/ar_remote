"""The controller side: owns room activities, routes keys, keeps the remote in sync."""

from __future__ import annotations

import asyncio
from datetime import timedelta
import logging
import time
from typing import Any

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Context, Event, HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import (
    async_call_later,
    async_track_state_change_event,
    async_track_time_interval,
)
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.script import Script, async_validate_actions_config
from homeassistant.helpers.start import async_at_started
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .api import RemoteClient, RemoteError
from .const import (
    COLOR_KEYS,
    COMMAND_SETS,
    CONF_HOST,
    CONF_PORT,
    CONF_PROJECT,
    CONF_WEBHOOK_ID,
    DOMAIN,
    EVENT_BUTTON,
    MEDIA_KEYS,
    PRESS_DOMAINS,
    SIGNAL_UPDATE,
    TRANSPORT_SERVICES,
)
from .project import compact_state, compile_project, tracked_entities

_LOGGER = logging.getLogger(__name__)

POLL_INTERVAL = timedelta(seconds=60)
DEFAULT_INPUT_DELAY = 7.0  # s between powering on and switching inputs
MP_TURN_ON = 128  # MediaPlayerEntityFeature.TURN_ON
MP_TURN_OFF = 256  # MediaPlayerEntityFeature.TURN_OFF
MP_PAUSE = 1  # MediaPlayerEntityFeature.PAUSE
ASLEEP = {"off", "standby", "unavailable", "unknown"}
PUSH_DELAY = 0.25

# Domains an activity's start sequence may have switched on, and that Room Off
# switches off again. Scenes, scripts and helpers are left alone.
OFF_DOMAINS = {
    "light", "switch", "media_player", "fan", "input_boolean", "climate",
    "remote", "siren", "humidifier",
}


def timed_list(items: Any) -> list[dict]:
    """A stored per-device timer list: [{"entity": "light.x", ...}], junk dropped."""
    if not isinstance(items, list):
        return []
    return [
        d for d in items
        if isinstance(d, dict) and isinstance(d.get("entity"), str) and "." in d["entity"]
    ]


def seconds(value: Any, default: float = 0.0) -> float:
    """A stored number of seconds, never negative, never a crash."""
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        return default


def entity_list(value: Any) -> list[str]:
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return []
    return [e for e in value if isinstance(e, str) and "." in e]


def _int(value: Any) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


class ArRemoteRuntime:
    """One AR Remote device."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.client = RemoteClient(
            async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT]
        )
        self.config: dict = entry.options.get(CONF_PROJECT) or {"rooms": []}
        self.project: dict = {}
        self.current: dict[str, str | None] = {}
        self.info: dict[str, Any] = {}
        self.online = False
        self.battery: int | None = None
        self.charging: bool | None = None
        self.screen: str | None = None
        self.button_listeners: list = []
        self._store = Store(hass, 1, f"{DOMAIN}.{entry.entry_id}")
        self._pending: dict[str, Any] = {}
        self._push_handle = None
        self._unsubs: list = []
        self._scripts: dict[tuple[str, str, str], Script | None] = {}
        self.base_url: str | None = None
        # Pending "switch this off later" timers, keyed "<room>|<entity>".
        # Persisted with their wall-clock deadline so a restart doesn't leave
        # something running forever.
        self._timers: dict[str, dict] = {}
        self._timer_unsubs: dict[str, Any] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        # Per room: the "wait, then switch inputs" step of a starting activity.
        self._input_tasks: dict[str, asyncio.Task] = {}
        # Per room: what the last Room Off did (shown on the room's activity entity).
        self.last_room_off: dict[str, dict] = {}
        # Per room: the last custom / colour button press and what came of it.
        self.last_button: dict[str, dict] = {}

    # ------------------------------------------------------------------ setup

    @property
    def webhook_id(self) -> str:
        return self.entry.data[CONF_WEBHOOK_ID]

    @property
    def signal(self) -> str:
        return SIGNAL_UPDATE.format(self.entry.entry_id)

    def webhook_url(self) -> str | None:
        try:
            base = get_url(self.hass, allow_external=False, allow_cloud=False, prefer_external=False)
        except NoURLAvailableError:
            return None
        self.base_url = base
        return f"{base}{webhook.async_generate_path(self.webhook_id)}"

    async def async_setup(self) -> None:
        try:
            stored = await self._store.async_load() or {}
        except Exception as err:  # noqa: BLE001 - a damaged file must not stop the remote
            _LOGGER.warning("AR Remote: stored state unreadable, starting fresh: %s", err)
            stored = {}
        if not isinstance(stored, dict):
            stored = {}
        current = stored.get("current")
        self.current = current if isinstance(current, dict) else {}
        timers = stored.get("timers")
        if isinstance(timers, dict):
            self._timers = {
                key: t for key, t in timers.items()
                if isinstance(t, dict) and isinstance(t.get("entity"), str)
                and isinstance(t.get("at"), (int, float))
            }
        self.project = compile_project(self.hass, self.config)
        self.webhook_url()  # resolves base_url for artwork links

        # A setup that failed half-way may have left our handler behind.
        webhook.async_unregister(self.hass, self.webhook_id)
        webhook.async_register(
            self.hass, DOMAIN, f"AR Remote {self.client.host}", self.webhook_id,
            self._handle_webhook, local_only=True,
        )
        self._unsubs.append(lambda: webhook.async_unregister(self.hass, self.webhook_id))

        tracked = tracked_entities(self.config)
        if tracked:
            self._unsubs.append(
                async_track_state_change_event(self.hass, list(tracked), self._on_state_change)
            )
        self._unsubs.append(
            async_track_time_interval(self.hass, self._poll, POLL_INTERVAL)
        )
        self.hass.async_create_task(self._poll())
        # Timers that were running before a restart / reload: re-arm them once
        # every integration is up, so the entities exist when they fire.
        self._unsubs.append(async_at_started(self.hass, self._rearm_timers))

    async def async_unload(self) -> None:
        for unsub in self._unsubs:
            unsub()
        self._unsubs.clear()
        if self._push_handle:
            self._push_handle()
            self._push_handle = None
        # Stop the in-memory timers; they stay in storage and the next setup re-arms them.
        for unsub in self._timer_unsubs.values():
            unsub()
        self._timer_unsubs.clear()
        for task in self._input_tasks.values():
            task.cancel()
        self._input_tasks.clear()

    # ------------------------------------------------------------- sync logic

    async def _poll(self, _now=None) -> None:
        """Reachability, battery, and re-linking when the app lost its link."""
        try:
            info = await self.client.info()
        except RemoteError:
            if self.online:
                self.online = False
                async_dispatcher_send(self.hass, self.signal)
            return
        self.info = info
        self.online = True
        self._check_launchers(info)
        if info.get("battery") is not None:
            self.battery = info["battery"]
            self.charging = info.get("charging")
        url = self.webhook_url()
        try:
            if url and info.get("webhook_url") != url:
                await self.client.link(url)
            if info.get("project_version") != self.project.get("version"):
                await self.async_program()
        except RemoteError as err:
            _LOGGER.debug("Sync with %s failed: %s", self.client.host, err)
        async_dispatcher_send(self.hass, self.signal)

    @callback
    def _check_launchers(self, info: dict) -> None:
        """Warn in HA when the stock launcher (RosCard) is switched on again."""
        from homeassistant.components import persistent_notification

        others = info.get("other_launchers") or []
        note_id = f"{DOMAIN}_launcher_{self.entry.entry_id}"
        if others:
            persistent_notification.async_create(
                self.hass,
                f"The remote at {self.client.host} has another home-screen app switched on "
                f"again: **{', '.join(others)}**. That brings back the clock lock screen and "
                "shows it first after updates. Plug the remote in over USB and run "
                "**tools/fix-remote.bat** (Windows) or **tools/fix-remote.sh** (Mac/Linux).",
                title="AR Remote: stock launcher is back",
                notification_id=note_id,
            )
        else:
            persistent_notification.async_dismiss(self.hass, note_id)

    async def async_program(self) -> None:
        """Push the compiled project and full state (Composer's 'Refresh Navigators')."""
        self.project = compile_project(self.hass, self.config)
        url = self.webhook_url()
        if url:
            await self.client.link(url)
        await self.client.push_project(self.project)
        await self.client.push_state(self.full_state())

    def full_state(self) -> dict:
        return {
            "full": True,
            "rooms": {rid: {"activity": act} for rid, act in self.current.items()},
            "entities": {eid: self._compact(eid) for eid in tracked_entities(self.config)},
        }

    def _now_playing_map(self) -> dict[str, list[str]]:
        """source -> the entities that say what is playing on it ('Show what's playing from')."""
        out: dict[str, list[str]] = {}
        for room in self.config.get("rooms") or []:
            for act in room.get("activities") or []:
                src, np = act.get("source"), act.get("now_playing")
                if isinstance(src, str) and isinstance(np, str) and src and np and np != src:
                    out.setdefault(src, [])
                    if np not in out[src]:
                        out[src].append(np)
        return out

    def _compact(self, entity_id: str) -> dict:
        """The entity's state for the remote; a source also gets the title / artist /
        picture of its 'Show what's playing from' entity (e.g. the TV's Google Cast
        entity, which knows the YouTube title and thumbnail)."""
        out = compact_state(self.hass.states.get(entity_id), self.base_url)
        for np in self._now_playing_map().get(entity_id, []):
            info = compact_state(self.hass.states.get(np), self.base_url)
            if info.get("s") in ("off", "unavailable", "unknown", "standby"):
                continue
            if info.get("t") or info.get("pic"):
                for key in ("t", "a", "pic"):
                    if info.get(key):
                        out[key] = info[key]
                    else:
                        out.pop(key, None)
                break
        return out

    @callback
    def _on_state_change(self, event: Event) -> None:
        entity_id = event.data["entity_id"]
        pending = self._pending.setdefault("entities", {})
        pending[entity_id] = self._compact(entity_id)
        # A 'now playing' entity changed: refresh the source it describes.
        for src, nps in self._now_playing_map().items():
            if entity_id in nps:
                pending[src] = self._compact(src)
        self._schedule_push()

    @callback
    def _schedule_push(self) -> None:
        if self._push_handle is None:
            self._push_handle = async_call_later(self.hass, PUSH_DELAY, self._flush)

    async def _flush(self, _now=None) -> None:
        self._push_handle = None
        payload, self._pending = self._pending, {}
        if not payload:
            return
        payload["full"] = False
        try:
            await self.client.push_state(payload)
        except RemoteError as err:
            _LOGGER.debug("State push skipped (remote asleep?): %s", err)

    # ----------------------------------------------------------------- lookup

    def room(self, room_id: str | None) -> dict | None:
        return next((r for r in self.config.get("rooms") or [] if r.get("id") == room_id), None)

    def activity(self, room_id: str | None, act_id: str | None) -> dict | None:
        room = self.room(room_id)
        if not room or not act_id:
            return None
        return next((a for a in room.get("activities") or [] if a.get("id") == act_id), None)

    # ---------------------------------------------------------------- webhook

    async def _handle_webhook(self, hass, webhook_id, request):
        from aiohttp import web

        try:
            msg = await request.json()
        except ValueError:
            return web.json_response({"ok": False, "error": "bad json"}, status=400)
        if not isinstance(msg, dict):
            return web.json_response({"ok": False, "error": "expected a JSON object"}, status=400)
        try:
            reply = await self.handle_message(msg)
        except Exception as err:  # noqa: BLE001 - never 500 back to the remote
            _LOGGER.exception("AR Remote message failed: %s", msg)
            return web.json_response({"ok": False, "error": str(err)})
        return web.json_response({"ok": True, **(reply or {})})

    async def handle_message(self, msg: dict) -> dict | None:
        kind = msg.get("type")
        if not self.online:
            self.online = True
            async_dispatcher_send(self.hass, self.signal)

        if kind == "hello":
            stale = msg.get("project_version") != self.project.get("version")
            return {"project": self.project if stale else None, "state": self.full_state()}
        if kind == "key":
            await self._on_key(msg)
        elif kind == "activity":
            await self.start_activity(msg.get("room"), msg.get("activity"))
        elif kind == "room_off":
            await self.room_off(msg.get("room"))
        elif kind == "slot":
            room_id = msg.get("room")
            act = self.activity(room_id, msg.get("activity"))
            # In the background: a long script must not hold up the remote's next press.
            self.hass.async_create_task(
                self._slot(room_id, act, str(msg.get("slot") or ""), "screen"), "ar_remote button"
            )
        elif kind == "run":
            await self.run_action(msg.get("entity"))
        elif kind == "light":
            await self._light(msg)
        elif kind == "cover":
            await self._call("cover", f"{msg.get('action', 'stop')}_cover", msg.get("entity"))
        elif kind == "climate":
            await self._climate(msg)
        elif kind == "media":
            await self._media(msg.get("entity"), msg.get("action"))
        elif kind == "battery":
            self.battery = msg.get("level")
            self.charging = msg.get("charging")
            async_dispatcher_send(self.hass, self.signal)
        elif kind == "screen":
            self.screen = msg.get("screen")
        return None

    # ------------------------------------------------------------------- keys

    async def _on_key(self, msg: dict) -> None:
        key = str(msg.get("key") or "UNKNOWN")
        press = str(msg.get("press") or "short")
        data = {
            "device_id": self.entry.entry_id,
            "host": self.client.host,
            "key": key,
            "press": press,
            "room": msg.get("room"),
            "activity": msg.get("activity"),
            "screen": msg.get("screen"),
        }
        if press != "repeat":
            self.hass.bus.async_fire(EVENT_BUTTON, data)
            for listener in list(self.button_listeners):
                try:
                    listener(key, data)
                except Exception:  # noqa: BLE001 - an entity error must not stop the key
                    _LOGGER.exception("AR Remote: button listener failed")
        # Colour keys: run the activity's action whatever the remote decided about
        # routing (an older app, or a remote that lost track of the activity,
        # sends them as "ui" and they did nothing).
        if key in COLOR_KEYS and press == "short":
            room_id = msg.get("room")
            act = self._activity_for_key(room_id, msg.get("activity"), COLOR_KEYS[key])
            if act is None:
                self._note_press(room_id, {
                    "button": COLOR_KEYS[key], "via": "key",
                    "result": "no activity running in this room — start one first",
                })
                _LOGGER.warning("AR Remote: %s pressed in room %s, but no activity is running there", key, room_id)
                return
            self.hass.async_create_task(self._slot(room_id, act, COLOR_KEYS[key], "key"), "ar_remote button")
            return

        if msg.get("route") != "device" or press == "long":
            return

        # ⏪ ⏵ ⏹ ⏩ set to "Run an action" under Remote settings: works on every screen.
        if key in MEDIA_KEYS:
            keys = (self.config.get("device") or {}).get("keys")
            cfg = keys.get(key) if isinstance(keys, dict) else None
            if isinstance(cfg, dict) and cfg.get("mode") == "action":
                await self.run_action(cfg.get("action"))
                return
            if isinstance(cfg, dict) and cfg.get("mode") not in (None, "transport"):
                return  # opens a screen on the remote; nothing to do here

        room_id = msg.get("room")
        # HA's record first; else the activity the remote says it is showing.
        act = self.activity(room_id, self.current.get(room_id)) or self.activity(room_id, msg.get("activity"))
        if act is None:
            _LOGGER.warning("AR Remote: %s pressed in room %s, but no activity is running there", key, room_id)
            if key in COLOR_KEYS:
                self._note_press(room_id, {"button": COLOR_KEYS[key], "result": "no activity running in this room"})
            return

        if key in ("VOLUME_UP", "VOLUME_DOWN", "MUTE"):
            vol = act.get("volume") or act.get("source")
            await self._media(vol, key.lower())
            return
        if key in COLOR_KEYS:
            self.hass.async_create_task(self._slot(room_id, act, COLOR_KEYS[key], "key"), "ar_remote button")
            return

        commands = COMMAND_SETS.get(act.get("command_set") or "", {})
        if act.get("dpad") and key in commands:
            await self._call(
                "remote", "send_command", act["dpad"], {"command": commands[key]}
            )
            return
        if key in TRANSPORT_SERVICES and act.get("source"):
            await self._call("media_player", TRANSPORT_SERVICES[key], act["source"])

    # ------------------------------------------------------------- activities

    async def _script(self, room_id: str, act: dict, key: str) -> Script | None:
        """The activity's start/stop sequence as an HA script (delays and all)."""
        cache = (room_id, act["id"], key)
        if cache in self._scripts:
            return self._scripts[cache]
        script = None
        actions = act.get(key) or []
        if actions:
            try:
                sequence = await async_validate_actions_config(self.hass, cv.SCRIPT_SCHEMA(actions))
                script = Script(
                    self.hass, sequence, f"AR Remote {act['name']} {key}", DOMAIN,
                    script_mode="restart", logger=_LOGGER,
                )
            except Exception as err:  # noqa: BLE001 - a bad sequence must not break the room
                _LOGGER.warning("AR Remote: %s %s is invalid: %s", act["name"], key, err)
        self._scripts[cache] = script
        return script

    @staticmethod
    def _devices(act: dict) -> list[dict]:
        """The activity's 'Also turn on' list: [{"entity": ..., "source": ... | None}]."""
        return [
            d for d in act.get("devices") or []
            if isinstance(d, dict) and isinstance(d.get("entity"), str) and d["entity"]
        ]

    def _core_entities(self, act: dict) -> set[str]:
        """Source, amp and the extra devices the activity powers on."""
        ents = {e for e in (act.get("source"), act.get("volume")) if e}
        ents |= {d["entity"] for d in self._devices(act)}
        return ents

    @staticmethod
    def _pulse_entities(act: dict) -> set[str]:
        """Devices the activity switches on for a few seconds only."""
        return {d["entity"] for d in timed_list(act.get("pulse"))}

    async def _activity_entities(self, room_id: str, act: dict) -> set[str]:
        """Everything the activity switches on: source, amp, extra devices, pulses, start-step targets."""
        ents = self._core_entities(act) | self._pulse_entities(act)
        script = await self._script(room_id, act, "start_actions")
        if script is not None:
            ents |= set(script.referenced_entities)
        return ents

    def _run(self, script: Script | None) -> None:
        if script is not None:
            self.hass.async_create_task(script.async_run(context=Context()), "ar_remote sequence")

    def _features(self, entity_id: str) -> int | None:
        state = self.hass.states.get(entity_id)
        if state is None or "supported_features" not in state.attributes:
            return None  # unknown: just try the call
        try:
            return int(state.attributes.get("supported_features") or 0)
        except (TypeError, ValueError):
            return None

    def _siblings(self, entity_id: str) -> list[str]:
        """Other media players on the same HA device (Denon AVR + HEOS, main zone + zone 2)."""
        try:
            from homeassistant.helpers import entity_registry as er

            reg = er.async_get(self.hass)
            entry = reg.async_get(entity_id)
            if entry is None or not entry.device_id:
                return []
            return [
                e.entity_id for e in er.async_entries_for_device(reg, entry.device_id)
                if e.entity_id != entity_id and e.entity_id.startswith("media_player.")
            ]
        except Exception:  # noqa: BLE001 - registry lookups are only a fallback
            return []

    def _power_entity(self, entity_id: str, feature: int) -> str | None:
        """The entity that can actually power this player on/off.

        A HEOS entity can't switch a Denon off; its Denon AVR sibling can.
        None when nothing on the device can.
        """
        feats = self._features(entity_id)
        if feats is None or feats & feature:
            return entity_id  # unknown (not loaded yet): just try it
        for sib in self._siblings(entity_id):
            sf = self._features(sib)
            if sf is not None and sf & feature:
                return sib
        return None

    async def _switch_off(self, entity_ids: set[str], report: dict | None = None) -> None:
        for eid in sorted(entity_ids):
            domain = eid.split(".", 1)[0]
            if domain == "media_player":
                feats = self._features(eid)
                if feats is None or feats & MP_PAUSE:
                    await self._call("media_player", "media_pause", eid)
                target = self._power_entity(eid, MP_TURN_OFF)
                if target is None:
                    if report is not None:
                        report.setdefault("cannot_power_off", []).append(eid)
                    _LOGGER.warning(
                        "AR Remote: %s can't be switched off (no power-off support on it or its device)", eid
                    )
                    continue
                await self._call("media_player", "turn_off", target)
                if report is not None:
                    report.setdefault("switched_off", []).append(target)
            elif domain in OFF_DOMAINS:
                await self._call("homeassistant", "turn_off", eid)
                if report is not None:
                    report.setdefault("switched_off", []).append(eid)

    def _off_commands(self, room_id: str) -> dict[str, str]:
        """device -> its 'turn off with' command, from every activity in the room."""
        out: dict[str, str] = {}
        for act in (self.room(room_id) or {}).get("activities") or []:
            for dev in self._devices(act):
                off = dev.get("off")
                if isinstance(off, str) and "." in off:
                    out[dev["entity"]] = off
        return out

    async def _power_off(
        self, room_id: str, entity_ids: set[str], report: dict | None = None, listed: bool = False
    ) -> None:
        """Switch devices off the right way.

        - a device with a "turn off with" command: that command (e.g. the IR
          "projector off" button) — never its "on" button again;
        - a button / script / scene: pressed only when the installer put it on
          an off list (`listed`); an "on" button without an off command can't be
          switched off and is reported;
        - everything else: switched off.
        """
        cmds = self._off_commands(room_id)
        devices = {d["entity"] for a in (self.room(room_id) or {}).get("activities") or [] for d in self._devices(a)}
        plain: set[str] = set()
        for eid in sorted(entity_ids):
            cmd = cmds.get(eid)
            if cmd:
                if cmd.split(".", 1)[0] in PRESS_DOMAINS:
                    await self.run_action(cmd)
                    if report is not None:
                        report.setdefault("pressed", []).append(cmd)
                else:
                    await self._switch_off({cmd}, report)
            elif eid.split(".", 1)[0] in PRESS_DOMAINS:
                if listed:
                    await self.run_action(eid)
                    if report is not None:
                        report.setdefault("pressed", []).append(eid)
                elif eid in devices and report is not None:
                    report.setdefault("cannot_power_off", []).append(f"{eid} (no 'turn off with' set)")
            else:
                plain.add(eid)
        await self._switch_off(plain, report)

    async def _off_or_press(self, entity_ids: set[str]) -> None:
        """For lists the installer picked: switch things off, press buttons / run scripts.

        Never used on start-step targets, so a scene used to start an activity
        is never re-run at Room Off.
        """
        press = {e for e in entity_ids if e.split(".", 1)[0] in PRESS_DOMAINS}
        await self._switch_off(entity_ids - press)
        for eid in sorted(press):
            await self.run_action(eid)

    async def _end_activity(self, room_id: str, act: dict, keep: set[str], report: dict | None = None) -> set[str]:
        """Stop the start sequence, run the stop sequence, switch off what it used."""
        start = await self._script(room_id, act, "start_actions")
        if start is not None and start.is_running:
            await start.async_stop()
        self._run(await self._script(room_id, act, "stop_actions"))
        if act.get("turn_off_on_end", True):
            off = await self._activity_entities(room_id, act) - keep
        else:
            off = (self._core_entities(act) | self._pulse_entities(act)) - keep
        await self._power_off(room_id, off, report)
        return off

    def _lock(self, room_id: str) -> asyncio.Lock:
        """One change at a time per room: a fast double-press can't interleave."""
        return self._locks.setdefault(room_id, asyncio.Lock())

    async def start_activity(self, room_id: str | None, act_id: str | None) -> None:
        room = self.room(room_id)
        new = self.activity(room_id, act_id)
        if room is None or new is None:
            return
        async with self._lock(room["id"]):
            await self._start_activity(room["id"], new)

    async def _start_activity(self, room_id: str, new: dict) -> None:
        old = self.activity(room_id, self.current.get(room_id))
        uses = await self._activity_entities(room_id, new)
        # A Room Off "stay on for X min" or an earlier pulse must not switch off
        # something this activity is about to use.
        self._cancel_timers(room_id, uses)
        self._cancel_inputs(room_id)
        if old and old["id"] != new["id"]:
            await self._end_activity(room_id, old, uses)

        # 1. Things that must go off when this activity starts (now, or after N s).
        for item in timed_list(new.get("off_on_start")):
            after = seconds(item.get("after"))
            if after > 0:
                self._schedule_off(room_id, item["entity"], after)
            else:
                self._cancel_timers(room_id, {item["entity"]})
                await self._power_off(room_id, {item["entity"]}, listed=True)

        # 2. Pulses: on now, off again by themselves after their own time.
        for item in timed_list(new.get("pulse")):
            await self._turn_on(item["entity"])
            self._schedule_off(room_id, item["entity"], seconds(item.get("seconds"), 5.0) or 5.0)

        # 3. Source, amp and every "Also turn on" device.
        targets = self._input_targets(new)
        was_on = {eid: self._is_on(eid) for eid in targets}
        for eid in (new.get("volume"), new.get("source")):
            if eid:
                await self._call("media_player", "turn_on", self._power_entity(eid, MP_TURN_ON) or eid)
        for dev in self._devices(new):
            await self._turn_on(dev["entity"])
        await self.run_action(new.get("power_on"))
        self._run(await self._script(room_id, new, "start_actions"))
        await self._set_current(room_id, new["id"])

        # 4. Inputs and volume once everything has had time to wake up. Runs in
        #    the background so the remote isn't kept waiting and Room Off (or
        #    another activity) can cancel it.
        self._cancel_inputs(room_id)
        if targets or new.get("start_volume") is not None:
            delay = 0.0 if targets and all(was_on.values()) else seconds(
                new.get("input_delay"), DEFAULT_INPUT_DELAY
            )
            self._input_tasks[room_id] = self.hass.async_create_task(
                self._switch_inputs(room_id, new, delay), "ar_remote inputs"
            )

    def _input_targets(self, act: dict) -> dict[str, str]:
        """media player -> input it must be on for this activity."""
        out: dict[str, str] = {}
        if act.get("volume") and act.get("input"):
            out[act["volume"]] = act["input"]
        if act.get("source") and act.get("source_input"):
            out[act["source"]] = act["source_input"]
        for dev in self._devices(act):
            if dev.get("source") and dev["entity"].startswith("media_player."):
                out[dev["entity"]] = dev["source"]
        return out

    def _is_on(self, entity_id: str) -> bool:
        state = self.hass.states.get(entity_id)
        return state is not None and state.state not in ASLEEP

    @callback
    def _cancel_inputs(self, room_id: str) -> None:
        task = self._input_tasks.pop(room_id, None)
        if task is not None and not task.done():
            task.cancel()

    async def _switch_inputs(self, room_id: str, act: dict, delay: float) -> None:
        """Wait `delay` s after power-on, then set inputs (only where not already right) and volume."""
        try:
            if delay > 0:
                await asyncio.sleep(delay)
            for eid, wanted in self._input_targets(act).items():
                state = self.hass.states.get(eid)
                current = state.attributes.get("source") if state is not None else None
                if current is not None and str(current) == wanted:
                    continue  # already on it: leave it alone
                await self._select_source(eid, wanted)
            start_volume = act.get("start_volume")
            if act.get("volume") and start_volume is not None:
                level = seconds(start_volume, -1)
                if 0 <= level <= 100:
                    await self._call(
                        "media_player", "volume_set", act["volume"], {"volume_level": round(level / 100, 2)}
                    )
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - a failed input switch must not break the room
            _LOGGER.exception("AR Remote: switching inputs for %s failed", act.get("name"))
        finally:
            if self._input_tasks.get(room_id) is asyncio.current_task():
                self._input_tasks.pop(room_id, None)

    def room_off_entities(self, room: dict) -> set[str]:
        """What Room Off switches off besides the running activity."""
        ents: set[str] = set()
        for act in room.get("activities") or []:
            ents |= self._core_entities(act) | self._pulse_entities(act)
        if room.get("off_lights", True):
            ents |= set(entity_list(room.get("lights")))
        if room.get("off_climate") and room.get("climate"):
            ents.add(room["climate"])
        ents |= set(entity_list(room.get("off_extra")))
        return ents

    @staticmethod
    def room_off_delayed(room: dict) -> dict[str, float]:
        """entity -> seconds it keeps running after Room Off."""
        out: dict[str, float] = {}
        for item in timed_list(room.get("off_delayed")):
            secs = seconds(item.get("minutes")) * 60
            if secs > 0:
                out[item["entity"]] = secs
        return out

    async def room_off(self, room_id: str | None) -> None:
        """Everything in the room goes off; music pauses first.

        Devices on the room's "keep running" list stay on for their own number
        of minutes and then switch off by themselves.
        """
        room = self.room(room_id)
        if room is None:
            return
        room_id = room["id"]
        async with self._lock(room_id):
            delayed = self.room_off_delayed(room)
            hold = set(delayed)
            current = self.activity(room_id, self.current.get(room_id))
            rest = self.room_off_entities(room)
            handled = rest | hold
            if current:
                handled |= await self._activity_entities(room_id, current)
            # Whatever happens to these now is decided here, not by an older timer
            # or an input switch still waiting to run.
            self._cancel_timers(room_id, handled)
            self._cancel_inputs(room_id)

            report: dict[str, Any] = {"at": dt_util.now().isoformat(timespec="seconds")}
            done: set[str] = set()
            if current:
                done |= await self._end_activity(room_id, current, hold, report)
            # _switch_off leaves buttons alone (an activity's "projector on"
            # button must not be pressed at Room Off); buttons on the room's own
            # off list are pressed.
            # Devices the room's activities use: their own "turn off with" command,
            # else switched off. A device that was switched on by a button and has
            # no off command can't be switched off and is reported.
            off_list = set(entity_list(room.get("off_extra")))
            await self._power_off(room_id, rest - off_list - done - hold, report)
            # The room's own "also turn off" list: buttons / scripts on it are pressed.
            await self._power_off(room_id, off_list - done - hold, report, listed=True)
            for eid, secs in delayed.items():
                self._schedule_off(room_id, eid, secs)
            report["kept_running_minutes"] = {e: round(v / 60, 1) for e, v in delayed.items()}
            cant = report.get("cannot_power_off") or []
            report["note"] = (
                "Nothing listed to switch off — add the room's devices to its activities or lists"
                if not report.get("switched_off") and not report.get("pressed")
                else f"{len(cant)} device(s) can't be powered off by Home Assistant" if cant else "ok"
            )
            self.last_room_off[room_id] = report
            _LOGGER.info("AR Remote: Room Off %s: %s", room_id, report)
            if room.get("off_action"):
                await self.run_action(room["off_action"])
            await self._set_current(room_id, None)

    def _sources_of(self, entity_id: str) -> list[str]:
        state = self.hass.states.get(entity_id)
        listed = state.attributes.get("source_list") if state is not None else None
        return [str(x) for x in listed] if isinstance(listed, (list, tuple)) else []

    async def _select_source(self, entity_id: str, source: str) -> None:
        """Select an input; if this entity doesn't list it but another entity of the
        same receiver does (Denon AVR vs HEOS, main zone vs zone 2), ask that one."""
        target = entity_id
        if source not in self._sources_of(entity_id):
            target = next(
                (sib for sib in self._siblings(entity_id) if source in self._sources_of(sib)), entity_id
            )
        await self._call("media_player", "select_source", target, {"source": source})

    async def _turn_on(self, entity_id: str) -> None:
        domain = entity_id.split(".", 1)[0]
        if domain in PRESS_DOMAINS:
            await self.run_action(entity_id)  # e.g. an IR "projector on" button
            return
        if domain == "media_player":
            await self._call(domain, "turn_on", self._power_entity(entity_id, MP_TURN_ON) or entity_id)
        elif domain in ("remote", "switch", "light", "fan", "siren"):
            await self._call(domain, "turn_on", entity_id)
        else:
            await self._call("homeassistant", "turn_on", entity_id)

    async def _set_current(self, room_id: str, act_id: str | None) -> None:
        self.current[room_id] = act_id
        await self._save()
        self._pending.setdefault("rooms", {})[room_id] = {"activity": act_id}
        self._schedule_push()
        async_dispatcher_send(self.hass, self.signal)

    async def _save(self) -> None:
        try:
            await self._store.async_save({"current": self.current, "timers": self._timers})
        except Exception as err:  # noqa: BLE001 - a full disk must not break the room
            _LOGGER.warning("AR Remote: could not save state: %s", err)

    # ----------------------------------------------------------------- timers

    @property
    def timers(self) -> dict[str, dict]:
        """Pending switch-offs (read-only view for diagnostics)."""
        return dict(self._timers)

    @callback
    def _schedule_off(self, room_id: str, entity_id: str, delay: float) -> None:
        """Switch entity_id off in `delay` seconds (replaces any timer it had)."""
        key = f"{room_id}|{entity_id}"
        self._timers[key] = {"room": room_id, "entity": entity_id, "at": time.time() + delay}
        self._arm(key)

    @callback
    def _cancel_timers(self, room_id: str, entity_ids: set[str] | None = None) -> None:
        for key, timer in list(self._timers.items()):
            if timer.get("room") != room_id:
                continue
            if entity_ids is not None and timer.get("entity") not in entity_ids:
                continue
            self._timers.pop(key, None)
            unsub = self._timer_unsubs.pop(key, None)
            if unsub:
                unsub()

    @callback
    def _arm(self, key: str) -> None:
        timer = self._timers.get(key)
        old = self._timer_unsubs.pop(key, None)
        if old:
            old()
        if timer is None:
            return
        delay = max(0.0, float(timer["at"]) - time.time())

        @callback
        def _fire(_now: Any) -> None:
            self._timer_unsubs.pop(key, None)
            if self._timers.get(key) is not timer:
                return  # replaced or cancelled meanwhile
            self._timers.pop(key, None)
            self.hass.async_create_task(
                self._timer_fired(str(timer.get("room") or ""), timer["entity"]), "ar_remote timer"
            )

        self._timer_unsubs[key] = async_call_later(self.hass, delay, _fire)

    async def _timer_fired(self, room_id: str, entity_id: str) -> None:
        await self._power_off(room_id, {entity_id}, listed=True)
        await self._save()

    @callback
    def _rearm_timers(self, _hass: HomeAssistant | None = None) -> None:
        for key in list(self._timers):
            self._arm(key)

    # ---------------------------------------------------------------- actions

    def _activity_for_key(self, room_id: str | None, reported: str | None, slot: str) -> dict | None:
        """The activity a colour key belongs to: HA's record, else what the remote
        reports, else the room's only activity with something on that key."""
        act = self.activity(room_id, self.current.get(room_id or "")) or self.activity(room_id, reported)
        if act is not None:
            return act
        room = self.room(room_id)
        with_slot = [
            a for a in (room or {}).get("activities") or []
            if ((a.get("slots") or {}).get(slot) or {}).get("action")
        ]
        return with_slot[0] if len(with_slot) == 1 else None

    @callback
    def _note_press(self, room_id: str | None, info: dict) -> None:
        if not room_id:
            return
        info["at"] = dt_util.now().isoformat(timespec="seconds")
        self.last_button[room_id] = info
        async_dispatcher_send(self.hass, self.signal)
        # Show the outcome on the remote itself, so a key that "does nothing" says why.
        name = str(info.get("button") or "").capitalize()
        result = str(info.get("result") or "")
        text = f"{name} ✓" if result.startswith("ok") else f"{name}: {result}"
        self._pending["toast"] = text[:120]
        self._schedule_push()

    async def _slot(self, room_id: str | None, act: dict | None, slot: str, via: str) -> None:
        """A custom / colour button: run its action and record what happened."""
        info: dict[str, Any] = {"button": slot, "via": via}
        if act is None:
            info["result"] = "activity not found (save and program the remote again)"
            self._note_press(room_id, info)
            return
        info["activity"] = act.get("name")
        action = ((act.get("slots") or {}).get(slot) or {}).get("action")
        info["action"] = action
        if not action:
            info["result"] = "no action set for this button"
            _LOGGER.warning("AR Remote: %s button in %s has no action set", slot, act.get("name"))
            self._note_press(room_id, info)
            return
        domain, service = self._action_service(action)
        try:
            await self.hass.services.async_call(domain, service, {"entity_id": action}, blocking=True)
            info["result"] = f"ok ({domain}.{service})"
        except Exception as err:  # noqa: BLE001 - show it, never break the remote
            info["result"] = f"failed: {err}"
            _LOGGER.warning("AR Remote: %s button (%s) failed: %s", slot, action, err)
        self._note_press(room_id, info)

    @staticmethod
    def _action_service(entity_id: str) -> tuple[str, str]:
        """script/scene → turn_on, button → press, automation → trigger, anything else → toggle."""
        domain = entity_id.split(".", 1)[0]
        service = {
            "script": "turn_on",
            "scene": "turn_on",
            "button": "press",
            "input_button": "press",
            "automation": "trigger",
        }.get(domain)
        return (domain, service) if service else ("homeassistant", "toggle")

    async def run_action(self, entity_id: str | None) -> None:
        if not entity_id or "." not in entity_id:
            return
        domain = entity_id.split(".", 1)[0]
        service = {
            "script": "turn_on",
            "scene": "turn_on",
            "button": "press",
            "input_button": "press",
            "automation": "trigger",
        }.get(domain)
        if service is None:
            await self._call("homeassistant", "toggle", entity_id)
        else:
            await self._call(domain, service, entity_id)

    async def _light(self, msg: dict) -> None:
        entity = msg.get("entity")
        if entity and not entity.startswith("light."):
            # A relay / switch in the room's lights: on or off only.
            if msg.get("action") == "set":
                on = _int(msg.get("brightness")) > 0
                await self._call("homeassistant", "turn_on" if on else "turn_off", entity)
            else:
                await self._call("homeassistant", "toggle", entity)
            return
        if msg.get("action") == "set":
            level = max(0, min(100, _int(msg.get("brightness"))))
            if level == 0:
                await self._call("light", "turn_off", entity)
            else:
                await self._call("light", "turn_on", entity, {"brightness_pct": level})
        else:
            await self._call("light", "toggle", entity)

    async def _climate(self, msg: dict) -> None:
        entity = msg.get("entity")
        state = self.hass.states.get(entity) if entity else None
        if state is None:
            return
        action = msg.get("action")
        if action in ("temp_up", "temp_down"):
            try:
                target = float(state.attributes.get("temperature"))
                step = float(state.attributes.get("target_temp_step") or 1.0)
            except (TypeError, ValueError):
                return  # no target temperature (e.g. the unit is off)
            target = target + (step if action == "temp_up" else -step)
            await self._call("climate", "set_temperature", entity, {"temperature": target})
        elif action == "mode" and msg.get("value"):
            await self._call("climate", "set_hvac_mode", entity, {"hvac_mode": msg["value"]})

    async def _media(self, entity: str | None, action: str | None) -> None:
        if not entity:
            return
        if action == "mute":
            state = self.hass.states.get(entity)
            muted = bool(state and state.attributes.get("is_volume_muted"))
            await self._call("media_player", "volume_mute", entity, {"is_volume_muted": not muted})
            return
        service = {
            "volume_up": "volume_up",
            "volume_down": "volume_down",
            "play_pause": "media_play_pause",
            "next": "media_next_track",
            "previous": "media_previous_track",
        }.get(action or "")
        if service:
            await self._call("media_player", service, entity)

    async def _call(self, domain: str, service: str, entity_id: str | None, data: dict | None = None) -> None:
        if not entity_id:
            return
        payload = {"entity_id": entity_id, **(data or {})}
        try:
            await self.hass.services.async_call(domain, service, payload, blocking=False)
        except Exception as err:  # noqa: BLE001 - one bad entity must not break the remote
            _LOGGER.warning("AR Remote: %s.%s on %s failed: %s", domain, service, entity_id, err)
