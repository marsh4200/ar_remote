"""Config flow (find the remote) and options flow (the 'Composer')."""

from __future__ import annotations

import copy
import hashlib
import os
import shutil
import time
from typing import Any

import voluptuous as vol

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.selector import (
    ActionSelector,
    ActionSelectorConfig,
    BooleanSelector,
    EntitySelector,
    EntitySelectorConfig,
    FileSelector,
    FileSelectorConfig,
    NumberSelector,
    NumberSelectorConfig,
    NumberSelectorMode,
    SelectOptionDict,
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)
from homeassistant.util import slugify

from .api import RemoteClient, RemoteError
from .const import (
    ACTION_DOMAINS,
    COMMAND_SETS,
    CONF_ADB_PORT,
    CONF_HOST,
    CONF_PORT,
    CONF_PROJECT,
    CONF_WEBHOOK_ID,
    DEFAULT_ADB_PORT,
    DEFAULT_PORT,
    DEVICE_DOMAINS,
    DOMAIN,
    KINDS,
    MEDIA_DIR,
    MEDIA_URL,
    SLOTS_COLOR,
    SLOTS_CUSTOM,
)


def _ent(domain: str | list[str], multiple: bool = False) -> EntitySelector:
    return EntitySelector(EntitySelectorConfig(domain=domain, multiple=multiple))


def _select(
    options: list[SelectOptionDict] | list[str], dropdown: bool = True, key: str | None = None
) -> SelectSelector:
    config = SelectSelectorConfig(
        options=options,
        mode=SelectSelectorMode.DROPDOWN if dropdown else SelectSelectorMode.LIST,
    )
    if key:
        config["translation_key"] = key
    return SelectSelector(config)


def _opt(key: str, current: dict, default: Any = None):
    """vol.Optional that pre-fills from the item being edited."""
    value = current.get(key, default)
    if value in (None, "", []):
        return vol.Optional(key)
    return vol.Optional(key, description={"suggested_value": value})


def _pickup(value: str | None) -> str:
    """'always' from 2.1 is now plain 'auto'."""
    return value if value in ("auto", "saver", "off") else "auto"


def _devices(act: dict) -> list[dict]:
    """The activity's 'Also turn on' list: [{"entity": ..., "source": ... | None}]."""
    return [d for d in act.get("devices") or [] if isinstance(d, dict) and d.get("entity")]


# What Room Off / "turn off when the activity starts" can switch off.
OFF_PICK_DOMAINS = [
    "light", "switch", "media_player", "fan", "input_boolean", "climate",
    "remote", "siren", "humidifier",
]


def _timed(items: Any) -> list[dict]:
    """A stored per-device timer list, junk dropped."""
    if not isinstance(items, list):
        return []
    return [d for d in items if isinstance(d, dict) and isinstance(d.get("entity"), str)]


def _number(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def pin_hash(pin: str) -> str:
    """What the remote checks: sha256("ar_remote:" + PIN). The PIN itself isn't stored."""
    return hashlib.sha256(f"ar_remote:{pin}".encode()).hexdigest()


def _upload() -> FileSelector:
    return FileSelector(FileSelectorConfig(accept="image/png,image/jpeg,image/webp,image/gif"))


def _save_upload(hass, file_id: str, stem: str) -> str:
    """Move an uploaded image into <config>/ar_remote_media and return its URL."""
    from homeassistant.components.file_upload import process_uploaded_file

    folder = hass.config.path(MEDIA_DIR)
    os.makedirs(folder, exist_ok=True)
    with process_uploaded_file(hass, file_id) as src:
        ext = src.suffix.lower() if src.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp", ".gif") else ".png"
        name = f"{slugify(stem) or 'logo'}{ext}"
        shutil.copyfile(src, os.path.join(folder, name))
    return f"{MEDIA_URL}/{name}"


def _unique_id(name: str, taken: set[str]) -> str:
    base = slugify(name) or "item"
    candidate, n = base, 2
    while candidate in taken:
        candidate, n = f"{base}_{n}", n + 1
    return candidate


class ArRemoteConfigFlow(ConfigFlow, domain=DOMAIN):
    """Add a remote by IP. The app doesn't have to be installed yet."""

    VERSION = 1

    async def async_step_user(self, user_input: dict | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            await self.async_set_unique_id(host)
            self._abort_if_unique_id_configured()
            client = RemoteClient(async_get_clientsession(self.hass), host, user_input[CONF_PORT])
            try:
                await client.info()
            except RemoteError:
                pass  # App not installed yet: the Install button flashes it over ADB.
            return self.async_create_entry(
                title=f"AR Remote ({host})",
                data={
                    CONF_HOST: host,
                    CONF_PORT: user_input[CONF_PORT],
                    CONF_ADB_PORT: user_input[CONF_ADB_PORT],
                    CONF_WEBHOOK_ID: webhook.async_generate_id(),
                },
                options={CONF_PROJECT: {"rooms": []}},
            )

        schema = vol.Schema(
            {
                vol.Required(CONF_HOST): str,
                vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                vol.Required(CONF_ADB_PORT, default=DEFAULT_ADB_PORT): int,
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return ArRemoteComposer()


class ArRemoteComposer(OptionsFlow):
    """Rooms → activities → buttons, then Save & Program."""

    def __init__(self) -> None:
        self._project: dict | None = None
        self._room_id: str | None = None
        self._act_id: str | None = None
        self._device_keys: dict[str, str] = {}
        self._timer_keys: dict[str, tuple[str, str]] = {}

    # ----------------------------------------------------------------- helpers

    @property
    def project(self) -> dict:
        if self._project is None:
            self._project = copy.deepcopy(
                self.config_entry.options.get(CONF_PROJECT) or {"rooms": []}
            )
        return self._project

    @property
    def rooms(self) -> list[dict]:
        return self.project.setdefault("rooms", [])

    def _room(self, room_id: str | None) -> dict | None:
        return next((r for r in self.rooms if r["id"] == room_id), None)

    def _activity(self, room_id: str | None, act_id: str | None) -> dict | None:
        room = self._room(room_id)
        if room is None:
            return None
        return next((a for a in room.get("activities", []) if a["id"] == act_id), None)

    def _room_options(self) -> list[SelectOptionDict]:
        return [SelectOptionDict(value=r["id"], label=r["name"]) for r in self.rooms]

    def _activity_options(self) -> list[SelectOptionDict]:
        return [
            SelectOptionDict(value=f"{r['id']}/{a['id']}", label=f"{r['name']} — {a['name']}")
            for r in self.rooms
            for a in r.get("activities", [])
        ]

    def _summary(self) -> str:
        if not self.rooms:
            return "No rooms yet — start with **Add a room**."
        lines = []
        for room in self.rooms:
            acts = ", ".join(a["name"] for a in room.get("activities", [])) or "no activities"
            lines.append(f"**{room['name']}** — {acts} · {len(room.get('lights', []))} lights")
        return "\n".join(lines)

    # -------------------------------------------------------------------- menu

    async def async_step_init(self, user_input: dict | None = None) -> ConfigFlowResult:
        menu = ["add_room"]
        if self.rooms:
            menu += ["pick_room", "add_activity"]
        if self._activity_options():
            menu += ["pick_activity", "copy_activity"]
        if self.rooms:
            menu += ["pick_delete"]
        menu += ["remote_settings", "save"]
        return self.async_show_menu(
            step_id="init",
            menu_options=menu,
            description_placeholders={"summary": self._summary()},
        )

    async def async_step_save(self, user_input: dict | None = None) -> ConfigFlowResult:
        return self.async_create_entry(data={CONF_PROJECT: self.project})

    # ---------------------------------------------------------- remote itself

    async def async_step_remote_settings(self, user_input: dict | None = None) -> ConfigFlowResult:
        """Logo, charging screen and wake on pick-up."""
        dev = self.project.setdefault("device", {})
        if user_input is not None:
            pin = "".join(ch for ch in str(user_input.get("installer_pin") or "") if ch.isdigit())
            if pin and not user_input.get("clear_pin") and not 4 <= len(pin) <= 8:
                return self.async_show_form(
                    step_id="remote_settings",
                    data_schema=self._remote_settings_schema(dev),
                    errors={"installer_pin": "pin_length"},
                )
            if user_input.get("logo_upload"):
                user_input["logo"] = await self.hass.async_add_executor_job(
                    _save_upload, self.hass, user_input["logo_upload"], "remote_logo"
                )
            logo = (user_input.get("logo") or "").strip()
            dev["logo"] = logo or None
            dev["logo_rev"] = int(time.time()) if logo else None  # re-download on every Submit
            dev["splash_seconds"] = int(user_input.get("splash_seconds", 2))
            dev["charging_screen"] = bool(user_input.get("charging_screen", True))
            dev["pickup"] = user_input.get("pickup", "auto")
            dev["pickup_sensitivity"] = user_input.get("pickup_sensitivity", "normal")
            if user_input.get("clear_pin"):
                dev["pin_hash"] = None
            elif pin:
                dev["pin_hash"] = pin_hash(pin)
            dev["clock"] = user_input.get("clock", "charging")
            dev["clock_after"] = int(user_input.get("clock_after", 30))
            # Clock hands centre: both empty = automatic.
            cx, cy = user_input.get("clock_x"), user_input.get("clock_y")
            dev["clock_x"], dev["clock_y"] = (float(cx), float(cy)) if cx is not None and cy is not None else (None, None)
            return await self.async_step_init()
        return self.async_show_form(step_id="remote_settings", data_schema=self._remote_settings_schema(dev))

    def _remote_settings_schema(self, dev: dict) -> vol.Schema:
        return vol.Schema(
            {
                vol.Optional("installer_pin"): TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD)),
                vol.Optional("clear_pin", default=False): BooleanSelector(),
                vol.Optional("logo_upload"): _upload(),
                _opt("logo", dev): TextSelector(),
                vol.Required("splash_seconds", default=dev.get("splash_seconds", 2)): NumberSelector(
                    NumberSelectorConfig(min=0, max=10, step=1, mode=NumberSelectorMode.SLIDER, unit_of_measurement="s")
                ),
                vol.Required("charging_screen", default=dev.get("charging_screen", True)): BooleanSelector(),
                vol.Required("clock", default=dev.get("clock", "charging")): _select(
                    ["charging", "always", "off"], dropdown=False, key="clock"
                ),
                vol.Required("clock_after", default=dev.get("clock_after", 30)): NumberSelector(
                    NumberSelectorConfig(min=10, max=600, step=10, mode=NumberSelectorMode.BOX, unit_of_measurement="s")
                ),
                _opt("clock_x", dev): NumberSelector(
                    NumberSelectorConfig(min=0, max=100, step=0.5, mode=NumberSelectorMode.SLIDER, unit_of_measurement="%")
                ),
                _opt("clock_y", dev): NumberSelector(
                    NumberSelectorConfig(min=0, max=100, step=0.5, mode=NumberSelectorMode.SLIDER, unit_of_measurement="%")
                ),
                vol.Required("pickup", default=_pickup(dev.get("pickup"))): _select(
                    ["auto", "saver", "off"], dropdown=False, key="pickup"
                ),
                vol.Required("pickup_sensitivity", default=dev.get("pickup_sensitivity", "normal")): _select(
                    ["low", "normal", "high"], dropdown=False, key="pickup_sensitivity"
                ),
            }
        )

    # ------------------------------------------------------------------- rooms

    def _room_schema(self, room: dict) -> vol.Schema:
        return vol.Schema(
            {
                vol.Required("name", default=room.get("name", "")): TextSelector(),
                _opt("lights", room, []): _ent(["light", "switch", "input_boolean", "fan"], multiple=True),
                _opt("scenes", room, []): _ent(["scene", "script"], multiple=True),
                _opt("climate", room): _ent("climate"),
                _opt("covers", room, []): _ent("cover", multiple=True),
                _opt("favourites", room, []): _ent(ACTION_DOMAINS, multiple=True),
                _opt("temperature", room): _ent("sensor"),
                vol.Required("off_lights", default=room.get("off_lights", True)): BooleanSelector(),
                vol.Required("off_climate", default=room.get("off_climate", False)): BooleanSelector(),
                _opt("off_extra", room, []): _ent(OFF_PICK_DOMAINS, multiple=True),
                _opt("off_delayed", {"off_delayed": [d["entity"] for d in _timed(room.get("off_delayed"))]}, []): _ent(
                    OFF_PICK_DOMAINS, multiple=True
                ),
                _opt("off_action", room): _ent(ACTION_DOMAINS),
            }
        )

    def _apply_room(self, room: dict, data: dict) -> None:
        room["name"] = (data.get("name") or "").strip() or room.get("name") or "Room"
        for key in ("lights", "scenes", "covers", "favourites", "off_extra"):
            room[key] = list(data.get(key) or [])
        for key in ("climate", "temperature", "off_action"):
            room[key] = data.get(key) or None
        room["off_lights"] = bool(data.get("off_lights", True))
        room["off_climate"] = bool(data.get("off_climate", False))
        # Keep the minutes already set for devices still on the list.
        minutes = {d["entity"]: d.get("minutes") for d in _timed(room.get("off_delayed"))}
        room["off_delayed"] = [
            {"entity": eid, "minutes": minutes.get(eid) or 10}
            for eid in dict.fromkeys(data.get("off_delayed") or [])
            if eid
        ]

    async def async_step_add_room(self, user_input: dict | None = None) -> ConfigFlowResult:
        if user_input is not None:
            room = {"id": _unique_id(user_input["name"], {r["id"] for r in self.rooms}), "activities": []}
            self._apply_room(room, user_input)
            self.rooms.append(room)
            self._room_id = room["id"]
            return await self.async_step_room_timers()
        return self.async_show_form(step_id="add_room", data_schema=self._room_schema({}))

    async def async_step_pick_room(self, user_input: dict | None = None) -> ConfigFlowResult:
        if user_input is not None:
            self._room_id = user_input["room"]
            return await self.async_step_edit_room()
        return self.async_show_form(
            step_id="pick_room",
            data_schema=vol.Schema({vol.Required("room"): _select(self._room_options())}),
        )

    async def async_step_edit_room(self, user_input: dict | None = None) -> ConfigFlowResult:
        room = self._room(self._room_id)
        if room is None:
            return await self.async_step_init()
        if user_input is not None:
            self._apply_room(room, user_input)
            return await self.async_step_room_timers()
        return self.async_show_form(
            step_id="edit_room",
            data_schema=self._room_schema(room),
            description_placeholders={"room": room["name"]},
        )

    def _timer_field_keys(self, groups: list[tuple[str, str, list[str]]]) -> dict[str, tuple[str, str]]:
        """Form field name -> (list, entity). The field name is its label: '<device> — <what>'."""
        keys: dict[str, tuple[str, str]] = {}
        for group, what, entities in groups:
            for eid in entities:
                key = f"{self._name_of(eid)} — {what}"
                if key in keys:
                    key = f"{self._name_of(eid)} ({eid}) — {what}"
                keys[key] = (group, eid)
        return keys

    async def async_step_room_timers(self, user_input: dict | None = None) -> ConfigFlowResult:
        """How long each 'keep running after Room Off' device stays on."""
        room = self._room(self._room_id)
        if room is None:
            return await self.async_step_init()
        delayed = _timed(room.get("off_delayed"))
        if not delayed:
            return await self.async_step_init()
        if user_input is not None:
            minutes = {eid: user_input.get(key) for key, (_g, eid) in self._timer_keys.items()}
            for item in delayed:
                item["minutes"] = max(0.5, _number(minutes.get(item["entity"]), item.get("minutes") or 10))
            room["off_delayed"] = delayed
            return await self.async_step_init()

        self._timer_keys = self._timer_field_keys(
            [("off_delayed", "minutes after Room Off", [d["entity"] for d in delayed])]
        )
        current = {d["entity"]: _number(d.get("minutes"), 10) for d in delayed}
        fields = {
            vol.Required(key, default=current.get(eid, 10)): NumberSelector(
                NumberSelectorConfig(min=0.5, max=720, step=0.5, mode=NumberSelectorMode.BOX, unit_of_measurement="min")
            )
            for key, (_g, eid) in self._timer_keys.items()
        }
        return self.async_show_form(
            step_id="room_timers",
            data_schema=vol.Schema(fields),
            description_placeholders={"room": room["name"]},
        )

    # -------------------------------------------------------------- activities

    def _activity_schema(self, act: dict, with_room: bool) -> vol.Schema:
        fields: dict = {}
        if with_room:
            fields[vol.Required("room", default=self.rooms[0]["id"])] = _select(self._room_options())
        fields.update(
            {
                vol.Required("name", default=act.get("name", "")): TextSelector(),
                vol.Required("kind", default=act.get("kind", "watch")): _select(KINDS, dropdown=False, key="kind"),
                vol.Optional("logo_upload"): _upload(),
                _opt("logo", act): TextSelector(),
                _opt("source", act): _ent("media_player"),
                _opt("volume", act): _ent("media_player"),
                _opt("dpad", act): _ent("remote"),
                vol.Required("command_set", default=act.get("command_set", "apple_tv")): _select(
                    list(COMMAND_SETS)
                ),
                _opt("devices", {"devices": [d["entity"] for d in _devices(act)]}, []): _ent(
                    DEVICE_DOMAINS, multiple=True
                ),
                _opt("power_on", act): _ent(ACTION_DOMAINS),
            }
        )
        return vol.Schema(fields)

    def _apply_activity(self, act: dict, data: dict) -> None:
        act["name"] = data["name"].strip()
        act["kind"] = data.get("kind", "watch")
        act["command_set"] = data.get("command_set", "apple_tv")
        logo = (data.get("logo") or "").strip() or None
        if logo != act.get("logo") or data.get("logo_upload") or (logo and not act.get("logo_rev")):
            act["logo_rev"] = int(time.time()) if logo else None
        act["logo"] = logo
        for key in ("source", "volume", "dpad", "power_on"):
            value = data.get(key)
            act[key] = value.strip() if isinstance(value, str) and value.strip() else None
        # Extra devices: keep the input already chosen for any device still listed.
        # The source and volume device are handled by their own fields.
        chosen = {d["entity"]: d.get("source") for d in _devices(act)}
        skip = {act.get("source"), act.get("volume")}
        devices: list[dict] = []
        for eid in data.get("devices") or []:
            if eid and eid not in skip and all(d["entity"] != eid for d in devices):
                devices.append({"entity": eid, "source": chosen.get(eid)})
        act["devices"] = devices
        act.setdefault("slots", {})

    async def _take_upload(self, user_input: dict, stem: str) -> None:
        """An uploaded logo wins over the typed address."""
        if user_input.get("logo_upload"):
            user_input["logo"] = await self.hass.async_add_executor_job(
                _save_upload, self.hass, user_input["logo_upload"], stem
            )

    async def async_step_add_activity(self, user_input: dict | None = None) -> ConfigFlowResult:
        if user_input is not None:
            room = self._room(user_input["room"])
            if room is None:
                return await self.async_step_init()
            acts = room.setdefault("activities", [])
            act = {"id": _unique_id(user_input["name"], {a["id"] for a in acts})}
            await self._take_upload(user_input, f"{room['id']}_{act['id']}")
            self._apply_activity(act, user_input)
            acts.append(act)
            self._room_id, self._act_id = room["id"], act["id"]
            return await self.async_step_sources()
        return self.async_show_form(
            step_id="add_activity", data_schema=self._activity_schema({}, with_room=True)
        )

    async def async_step_pick_activity(self, user_input: dict | None = None) -> ConfigFlowResult:
        if user_input is not None:
            self._room_id, self._act_id = user_input["activity"].split("/", 1)
            return await self.async_step_edit_activity()
        return self.async_show_form(
            step_id="pick_activity",
            data_schema=vol.Schema({vol.Required("activity"): _select(self._activity_options())}),
        )

    async def async_step_copy_activity(self, user_input: dict | None = None) -> ConfigFlowResult:
        """Duplicate an activity (devices, inputs, steps, buttons) — e.g. Netflix → Prime."""
        if user_input is not None:
            room_id, act_id = user_input["activity"].split("/", 1)
            source = self._activity(room_id, act_id)
            target = self._room(user_input.get("room") or room_id)
            if source is None or target is None:
                return await self.async_step_init()
            acts = target.setdefault("activities", [])
            copy_ = copy.deepcopy(source)
            copy_["name"] = user_input["name"].strip()
            copy_["id"] = _unique_id(copy_["name"], {a["id"] for a in acts})
            copy_["logo"] = None
            copy_["logo_rev"] = None
            acts.append(copy_)
            self._room_id, self._act_id = target["id"], copy_["id"]
            return await self.async_step_edit_activity()
        return self.async_show_form(
            step_id="copy_activity",
            data_schema=vol.Schema(
                {
                    vol.Required("activity"): _select(self._activity_options()),
                    vol.Required("name"): TextSelector(),
                    vol.Optional("room"): _select(self._room_options()),
                }
            ),
        )

    async def async_step_edit_activity(self, user_input: dict | None = None) -> ConfigFlowResult:
        act = self._activity(self._room_id, self._act_id)
        if act is None:
            return await self.async_step_init()
        if user_input is not None:
            await self._take_upload(user_input, f"{self._room_id}_{act['id']}")
            self._apply_activity(act, user_input)
            return await self.async_step_sources()
        return self.async_show_form(
            step_id="edit_activity",
            data_schema=self._activity_schema(act, with_room=False),
            description_placeholders={"activity": act["name"]},
        )

    def _source_list(self, entity_id: str | None) -> list[str]:
        state = self.hass.states.get(entity_id) if entity_id else None
        if state is None:
            return []
        return [str(s) for s in state.attributes.get("source_list") or []]

    def _name_of(self, entity_id: str | None) -> str:
        state = self.hass.states.get(entity_id) if entity_id else None
        return state.name if state is not None else (entity_id or "")

    def _input_field(self, key: str, entity: str, current: str | None) -> tuple:
        """An input dropdown filled from the device's own source list (free text if none)."""
        options = self._source_list(entity)
        if current and current not in options:
            options.append(current)
        marker = _opt(key, {key: current})
        if not options:
            return marker, TextSelector()
        return marker, SelectSelector(
            SelectSelectorConfig(options=options, custom_value=True, mode=SelectSelectorMode.DROPDOWN)
        )

    def _device_input_keys(self, act: dict) -> dict[str, str]:
        """Form field name -> media player, one per extra media player.

        The field name doubles as its label, so it is the device's name.
        """
        keys: dict[str, str] = {}
        for dev in _devices(act):
            eid = dev["entity"]
            if not eid.startswith("media_player."):
                continue
            key = f"{self._name_of(eid)} input"
            if key in keys or key in ("source_input", "input"):
                key = f"{self._name_of(eid)} ({eid}) input"
            keys[key] = eid
        return keys

    async def async_step_sources(self, user_input: dict | None = None) -> ConfigFlowResult:
        """Pick inputs from what the source, amp and extra devices actually offer."""
        act = self._activity(self._room_id, self._act_id)
        if act is None:
            return await self.async_step_init()
        devices = {d["entity"]: d for d in _devices(act)}
        if user_input is not None:
            for key in ("source_input", "input"):
                value = user_input.get(key)
                act[key] = value.strip() if isinstance(value, str) and value.strip() else None
            for key, eid in self._device_keys.items():
                value = user_input.get(key)
                devices[eid]["source"] = value.strip() if isinstance(value, str) and value.strip() else None
            return await self.async_step_actions()

        fields: dict = {}
        for key, entity in (("source_input", act.get("source")), ("input", act.get("volume"))):
            if not entity:
                act[key] = None
                continue
            marker, selector = self._input_field(key, entity, act.get(key))
            fields[marker] = selector
        self._device_keys = self._device_input_keys(act)
        for key, eid in self._device_keys.items():
            marker, selector = self._input_field(key, eid, devices[eid].get("source"))
            fields[marker] = selector
        if not fields:
            return await self.async_step_actions()
        return self.async_show_form(
            step_id="sources",
            data_schema=vol.Schema(fields),
            description_placeholders={
                "activity": act["name"],
                "source": self._name_of(act.get("source")) or "—",
                "volume": self._name_of(act.get("volume")) or "—",
                "devices": ", ".join(self._name_of(e) for e in devices) or "—",
            },
        )

    async def async_step_actions(self, user_input: dict | None = None) -> ConfigFlowResult:
        """Extra things the activity does: lights, switches, other devices, delays."""
        act = self._activity(self._room_id, self._act_id)
        if act is None:
            return await self.async_step_init()
        if user_input is not None:
            act["start_actions"] = list(user_input.get("start_actions") or [])
            act["stop_actions"] = list(user_input.get("stop_actions") or [])
            act["turn_off_on_end"] = bool(user_input.get("turn_off_on_end", True))
            # Keep the times already set for devices still listed.
            after = {d["entity"]: d.get("after") for d in _timed(act.get("off_on_start"))}
            pulse = {d["entity"]: d.get("seconds") for d in _timed(act.get("pulse"))}
            act["off_on_start"] = [
                {"entity": eid, "after": after.get(eid) or 0}
                for eid in dict.fromkeys(user_input.get("off_on_start") or []) if eid
            ]
            act["pulse"] = [
                {"entity": eid, "seconds": pulse.get(eid) or 5}
                for eid in dict.fromkeys(user_input.get("pulse") or []) if eid
            ]
            return await self.async_step_timers()
        schema = vol.Schema(
            {
                _opt("off_on_start", {"off_on_start": [d["entity"] for d in _timed(act.get("off_on_start"))]}, []): _ent(
                    OFF_PICK_DOMAINS, multiple=True
                ),
                _opt("pulse", {"pulse": [d["entity"] for d in _timed(act.get("pulse"))]}, []): _ent(
                    DEVICE_DOMAINS, multiple=True
                ),
                _opt("start_actions", act, []): ActionSelector(ActionSelectorConfig()),
                _opt("stop_actions", act, []): ActionSelector(ActionSelectorConfig()),
                vol.Required("turn_off_on_end", default=act.get("turn_off_on_end", True)): BooleanSelector(),
            }
        )
        return self.async_show_form(
            step_id="actions",
            data_schema=schema,
            description_placeholders={"activity": act["name"]},
        )

    async def async_step_timers(self, user_input: dict | None = None) -> ConfigFlowResult:
        """A time for each device picked on the previous page."""
        act = self._activity(self._room_id, self._act_id)
        if act is None:
            return await self.async_step_init()
        offs, pulses = _timed(act.get("off_on_start")), _timed(act.get("pulse"))
        if not offs and not pulses:
            return await self.async_step_buttons()
        if user_input is not None:
            for key, (group, eid) in self._timer_keys.items():
                value = user_input.get(key)
                if group == "off_on_start":
                    for item in offs:
                        if item["entity"] == eid:
                            item["after"] = max(0.0, _number(value, item.get("after") or 0))
                else:
                    for item in pulses:
                        if item["entity"] == eid:
                            item["seconds"] = max(0.5, _number(value, item.get("seconds") or 5))
            act["off_on_start"], act["pulse"] = offs, pulses
            return await self.async_step_buttons()

        self._timer_keys = self._timer_field_keys(
            [
                ("off_on_start", "turn off after (0 = straight away)", [d["entity"] for d in offs]),
                ("pulse", "stay on for, then off", [d["entity"] for d in pulses]),
            ]
        )
        current = {
            ("off_on_start", d["entity"]): _number(d.get("after"), 0) for d in offs
        } | {("pulse", d["entity"]): _number(d.get("seconds"), 5) for d in pulses}
        fields: dict = {}
        for key, ref in self._timer_keys.items():
            low = 0 if ref[0] == "off_on_start" else 0.5
            fields[vol.Required(key, default=current.get(ref, low))] = NumberSelector(
                NumberSelectorConfig(min=low, max=3600, step=0.5, mode=NumberSelectorMode.BOX, unit_of_measurement="s")
            )
        return self.async_show_form(
            step_id="timers",
            data_schema=vol.Schema(fields),
            description_placeholders={"activity": act["name"]},
        )

    async def async_step_buttons(self, user_input: dict | None = None) -> ConfigFlowResult:
        act = self._activity(self._room_id, self._act_id)
        if act is None:
            return await self.async_step_init()
        slots = act.setdefault("slots", {})
        if user_input is not None:
            for slot in SLOTS_CUSTOM + SLOTS_COLOR:
                label = (user_input.get(f"{slot}_label") or "").strip()
                action = user_input.get(f"{slot}_action") or None
                slots[slot] = {"label": label, "action": action} if (label or action) else {}
            return await self.async_step_init()

        fields: dict = {}
        for slot in SLOTS_CUSTOM + SLOTS_COLOR:
            current = slots.get(slot, {})
            fields[_opt(f"{slot}_label", {f"{slot}_label": current.get("label")})] = TextSelector()
            fields[_opt(f"{slot}_action", {f"{slot}_action": current.get("action")})] = _ent(
                ACTION_DOMAINS
            )
        return self.async_show_form(
            step_id="buttons",
            data_schema=vol.Schema(fields),
            description_placeholders={"activity": act["name"]},
        )

    # ------------------------------------------------------------------ delete

    async def async_step_pick_delete(self, user_input: dict | None = None) -> ConfigFlowResult:
        if user_input is not None:
            target = user_input["item"]
            if "/" in target:
                room_id, act_id = target.split("/", 1)
                room = self._room(room_id)
                if room:
                    room["activities"] = [a for a in room.get("activities", []) if a["id"] != act_id]
            else:
                self.project["rooms"] = [r for r in self.rooms if r["id"] != target]
            return await self.async_step_init()
        options = [
            SelectOptionDict(value=r["id"], label=f"Room: {r['name']} (and its activities)")
            for r in self.rooms
        ] + [
            SelectOptionDict(value=o["value"], label=f"Activity: {o['label']}")
            for o in self._activity_options()
        ]
        return self.async_show_form(
            step_id="pick_delete",
            data_schema=vol.Schema({vol.Required("item"): _select(options)}),
        )
