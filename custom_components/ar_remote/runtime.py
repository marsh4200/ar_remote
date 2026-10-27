"""The controller side: owns room activities, routes keys, keeps the remote in sync."""

from __future__ import annotations

from datetime import timedelta
import logging
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
from homeassistant.helpers.storage import Store

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
    SIGNAL_UPDATE,
    TRANSPORT_SERVICES,
)
from .project import compact_state, compile_project, tracked_entities

_LOGGER = logging.getLogger(__name__)

POLL_INTERVAL = timedelta(seconds=60)
PUSH_DELAY = 0.25

# Domains an activity's start sequence may have switched on, and that Room Off
# switches off again. Scenes, scripts and helpers are left alone.
OFF_DOMAINS = {
    "light", "switch", "media_player", "fan", "input_boolean", "climate",
    "remote", "siren", "humidifier",
}


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
        stored = await self._store.async_load() or {}
        self.current = stored.get("current", {})
        self.project = compile_project(self.hass, self.config)
        self.webhook_url()  # resolves base_url for artwork links

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

    async def async_unload(self) -> None:
        for unsub in self._unsubs:
            unsub()
        self._unsubs.clear()
        if self._push_handle:
            self._push_handle()

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
            "entities": {
                eid: compact_state(self.hass.states.get(eid), self.base_url)
                for eid in tracked_entities(self.config)
            },
        }

    @callback
    def _on_state_change(self, event: Event) -> None:
        entity_id = event.data["entity_id"]
        self._pending.setdefault("entities", {})[entity_id] = compact_state(
            event.data.get("new_state"), self.base_url
        )
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
        return next((r for r in self.config.get("rooms", []) if r["id"] == room_id), None)

    def activity(self, room_id: str | None, act_id: str | None) -> dict | None:
        room = self.room(room_id)
        if not room or not act_id:
            return None
        return next((a for a in room.get("activities", []) if a["id"] == act_id), None)

    # ---------------------------------------------------------------- webhook

    async def _handle_webhook(self, hass, webhook_id, request):
        from aiohttp import web

        try:
            msg = await request.json()
        except ValueError:
            return web.json_response({"ok": False, "error": "bad json"}, status=400)
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
            act = self.activity(msg.get("room"), msg.get("activity"))
            if act:
                await self.run_action(act.get("slots", {}).get(msg.get("slot"), {}).get("action"))
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
        key = msg.get("key", "UNKNOWN")
        press = msg.get("press", "short")
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
            for listener in self.button_listeners:
                listener(key, data)
        if msg.get("route") != "device" or press == "long":
            return

        room_id = msg.get("room")
        act = self.activity(room_id, self.current.get(room_id))
        if act is None:
            return

        if key in ("VOLUME_UP", "VOLUME_DOWN", "MUTE"):
            vol = act.get("volume") or act.get("source")
            await self._media(vol, key.lower())
            return
        if key in COLOR_KEYS:
            slot = act.get("slots", {}).get(COLOR_KEYS[key], {})
            await self.run_action(slot.get("action"))
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
        return [d for d in act.get("devices") or [] if isinstance(d, dict) and d.get("entity")]

    def _core_entities(self, act: dict) -> set[str]:
        """Source, amp and the extra devices the activity powers on."""
        ents = {e for e in (act.get("source"), act.get("volume")) if e}
        ents |= {d["entity"] for d in self._devices(act)}
        return ents

    async def _activity_entities(self, room_id: str, act: dict) -> set[str]:
        """Everything the activity switches on: source, amp, extra devices, start-step targets."""
        ents = self._core_entities(act)
        script = await self._script(room_id, act, "start_actions")
        if script is not None:
            ents |= set(script.referenced_entities)
        return ents

    def _run(self, script: Script | None) -> None:
        if script is not None:
            self.hass.async_create_task(script.async_run(context=Context()), "ar_remote sequence")

    async def _switch_off(self, entity_ids: set[str]) -> None:
        for eid in sorted(entity_ids):
            domain = eid.split(".", 1)[0]
            if domain == "media_player":
                await self._call("media_player", "media_pause", eid)
                await self._call("media_player", "turn_off", eid)
            elif domain in OFF_DOMAINS:
                await self._call("homeassistant", "turn_off", eid)

    async def _end_activity(self, room_id: str, act: dict, keep: set[str]) -> set[str]:
        """Stop the start sequence, run the stop sequence, switch off what it used."""
        start = await self._script(room_id, act, "start_actions")
        if start is not None and start.is_running:
            await start.async_stop()
        self._run(await self._script(room_id, act, "stop_actions"))
        if act.get("turn_off_on_end", True):
            off = await self._activity_entities(room_id, act) - keep
        else:
            off = self._core_entities(act) - keep
        await self._switch_off(off)
        return off

    async def start_activity(self, room_id: str | None, act_id: str | None) -> None:
        room = self.room(room_id)
        new = self.activity(room_id, act_id)
        if room is None or new is None:
            return
        old = self.activity(room_id, self.current.get(room_id))
        if old and old["id"] != new["id"]:
            keep = await self._activity_entities(room_id, new)
            await self._end_activity(room_id, old, keep)
        for eid in (new.get("volume"), new.get("source")):
            if eid:
                await self._call("media_player", "turn_on", eid)
        devices = self._devices(new)
        for dev in devices:
            await self._turn_on(dev["entity"])
        if new.get("input") and new.get("volume"):
            await self._call(
                "media_player", "select_source", new["volume"], {"source": new["input"]}
            )
        if new.get("source_input") and new.get("source"):
            await self._call(
                "media_player", "select_source", new["source"], {"source": new["source_input"]}
            )
        for dev in devices:
            if dev.get("source") and dev["entity"].startswith("media_player."):
                await self._call(
                    "media_player", "select_source", dev["entity"], {"source": dev["source"]}
                )
        await self.run_action(new.get("power_on"))
        self._run(await self._script(room_id, new, "start_actions"))
        await self._set_current(room_id, new["id"])

    async def room_off(self, room_id: str | None) -> None:
        """Everything the room's activities switched on goes off; music pauses first."""
        room = self.room(room_id)
        if room is None:
            return
        done: set[str] = set()
        current = self.activity(room_id, self.current.get(room_id))
        if current:
            done |= await self._end_activity(room_id, current, set())
        rest: set[str] = set()
        for act in room.get("activities", []):
            rest |= self._core_entities(act)
        await self._switch_off(rest - done)
        if room.get("off_action"):
            await self.run_action(room["off_action"])
        await self._set_current(room_id, None)

    async def _turn_on(self, entity_id: str) -> None:
        domain = entity_id.split(".", 1)[0]
        if domain in ("media_player", "remote", "switch", "light", "fan", "siren"):
            await self._call(domain, "turn_on", entity_id)
        else:
            await self._call("homeassistant", "turn_on", entity_id)

    async def _set_current(self, room_id: str, act_id: str | None) -> None:
        self.current[room_id] = act_id
        await self._store.async_save({"current": self.current})
        self._pending.setdefault("rooms", {})[room_id] = {"activity": act_id}
        self._schedule_push()
        async_dispatcher_send(self.hass, self.signal)

    # ---------------------------------------------------------------- actions

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
                on = int(msg.get("brightness", 0)) > 0
                await self._call("homeassistant", "turn_on" if on else "turn_off", entity)
            else:
                await self._call("homeassistant", "toggle", entity)
            return
        if msg.get("action") == "set":
            level = max(0, min(100, int(msg.get("brightness", 0))))
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
            target = state.attributes.get("temperature")
            if target is None:
                return
            step = float(state.attributes.get("target_temp_step") or 1.0)
            target = float(target) + (step if action == "temp_up" else -step)
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
