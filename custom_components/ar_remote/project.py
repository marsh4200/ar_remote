"""Compile the stored composer config into what the remote renders."""

from __future__ import annotations

import hashlib
import json
from typing import Any

from homeassistant.core import HomeAssistant, State

from .const import HOME_ICONS, HOME_STYLES, ICON_SETS, IR_KEYS, KEY_MODES, MEDIA_KEYS, SLOTS_COLOR, SLOTS_CUSTOM


def _name(hass: HomeAssistant, entity_id: str) -> str:
    state = hass.states.get(entity_id)
    if state is not None and state.name:
        return str(state.name)
    return entity_id.split(".", 1)[-1].replace("_", " ").title()


def _dimmable(hass: HomeAssistant, entity_id: str) -> bool:
    if not entity_id.startswith("light."):
        return False  # relays / switches are on-off
    state = hass.states.get(entity_id)
    if state is None:
        return True
    modes = state.attributes.get("supported_color_modes") or []
    return any(m != "onoff" for m in modes) if modes else "brightness" in state.attributes


def _ids(value: Any) -> list[str]:
    """A stored entity list; anything that isn't an entity id is dropped."""
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return []
    return [e for e in value if isinstance(e, str) and "." in e]


def _items(hass: HomeAssistant, ids: Any) -> list[dict]:
    return [{"entity": e, "name": _name(hass, e)} for e in _ids(ids)]


# ----------------------------------------------------------------- Room Off plan
#
# Every device in a room is in one of four groups at Room Off:
#   turns off       — always switched off (activity devices, "also turns off")
#   your choice     — off or left on by default; the client can flip it on the
#                     remote's Room Off sheet (the lights, the aircon, a list)
#   stays on        — keeps running for its own minutes, then switches off
#   never touched   — Room Off leaves it alone, even if an activity used it

OFF_MODES = ["always", "ask_off", "ask_on", "never"]


def off_mode(room: dict, what: str) -> str:
    """How Room Off treats the room's lights / climate (older rooms stored a bool)."""
    mode = room.get(f"off_{what}_mode")
    if mode in OFF_MODES:
        return mode
    if what == "lights":
        return "always" if room.get("off_lights", True) else "never"
    return "always" if room.get("off_climate") else "never"


def activity_core(act: dict) -> set[str]:
    """Source, amp and every 'Also turn on' device of an activity."""
    ents = {e for e in (act.get("source"), act.get("volume")) if isinstance(e, str) and e}
    for dev in act.get("devices") or []:
        if isinstance(dev, dict) and isinstance(dev.get("entity"), str) and dev["entity"]:
            ents.add(dev["entity"])
    for item in act.get("pulse") or []:
        if isinstance(item, dict) and isinstance(item.get("entity"), str) and "." in item["entity"]:
            ents.add(item["entity"])
    return ents


def delayed_minutes(room: dict) -> dict[str, float]:
    out: dict[str, float] = {}
    for item in room.get("off_delayed") or []:
        if not isinstance(item, dict) or not isinstance(item.get("entity"), str):
            continue
        try:
            minutes = float(item.get("minutes"))
        except (TypeError, ValueError):
            continue
        if minutes > 0:
            out[item["entity"]] = minutes
    return out


def room_off_plan(room: dict) -> dict:
    """The four Room Off groups of a room.

    {"plain": set, "listed": set, "choices": [{"id", "entities", "default_off", "listed"}],
     "delayed": {entity: minutes}, "never": set}
    plain = switched off the normal way; listed = the installer's own off list
    (buttons / scripts on it are pressed).
    """
    never = set(_ids(room.get("off_never")))
    plain: set[str] = set()
    for act in room.get("activities") or []:
        plain |= activity_core(act)
    choices: list[dict] = []
    lights = _ids(room.get("lights"))
    mode = off_mode(room, "lights")
    if lights and mode == "always":
        plain |= set(lights)
    elif lights and mode in ("ask_off", "ask_on"):
        choices.append({"id": "lights", "entities": lights, "default_off": mode == "ask_off", "listed": False})
    climate = room.get("climate")
    mode = off_mode(room, "climate")
    if climate and mode == "always":
        plain.add(climate)
    elif climate and mode in ("ask_off", "ask_on"):
        choices.append({"id": "climate", "entities": [climate], "default_off": mode == "ask_off", "listed": False})
    taken = {e for c in choices for e in c["entities"]}
    for key, default_off in (("off_choice_off", True), ("off_choice_on", False)):
        for eid in _ids(room.get(key)):
            if eid not in taken:
                taken.add(eid)
                choices.append({"id": eid, "entities": [eid], "default_off": default_off, "listed": True})
    delayed = {e: m for e, m in delayed_minutes(room).items() if e not in never}
    listed = set(_ids(room.get("off_extra"))) - never
    plain -= never
    for c in choices:
        c["entities"] = [e for e in c["entities"] if e not in never]
    choices = [c for c in choices if c["entities"]]
    # A device the client decides about is not also on the "always off" lists.
    plain -= taken
    listed -= taken
    return {"plain": plain, "listed": listed, "choices": choices, "delayed": delayed, "never": never}


def _off_for_remote(hass: HomeAssistant, room: dict) -> dict:
    """What the remote's Room Off sheet shows."""
    plan = room_off_plan(room)
    lights = set(_ids(room.get("lights")))
    always = (plan["plain"] | plan["listed"]) - set(plan["delayed"])
    names: list[str] = []
    if lights and lights <= always:
        names.append("Lights")
        always -= lights
    names += sorted({_name(hass, e) for e in always})
    choices = []
    for c in plan["choices"]:
        if c["id"] == "lights":
            name = "Lights"
            light_names = room.get("light_names") if isinstance(room.get("light_names"), dict) else {}
            hint = ", ".join((light_names.get(e) or _name(hass, e)) for e in c["entities"][:3])
            if len(c["entities"]) > 3:
                hint += f" +{len(c['entities']) - 3}"
        elif c["id"] == "climate":
            name, hint = "Aircon", _name(hass, c["entities"][0])
        else:
            name, hint = _name(hass, c["id"]), ""
        choices.append({"id": c["id"], "name": name, "hint": hint, "default_off": c["default_off"]})
    stays = [
        {"name": _name(hass, e), "minutes": m}
        for e, m in sorted(plan["delayed"].items(), key=lambda kv: _name(hass, kv[0]))
    ]
    return {"ask": bool(room.get("off_ask", True)), "turns_off": names, "choices": choices, "stays_on": stays}


def compile_project(hass: HomeAssistant, config: dict) -> dict:
    """Build the project JSON (labels and entity ids, never actions)."""
    rooms = []
    for room in config.get("rooms") or []:
        activities = []
        for act in room.get("activities") or []:
            slots = act.get("slots") or {}
            ir_keys = ir_key_codes(config, act)
            activities.append(
                {
                    "id": act["id"],
                    "name": act["name"],
                    "kind": act.get("kind", "watch"),
                    "logo": _versioned(act.get("logo"), act.get("logo_rev")),
                    "source": act.get("source"),
                    "volume": act.get("volume") or act.get("source"),
                    "custom": [(slots.get(s) or {}).get("label") or "" for s in SLOTS_CUSTOM],
                    "colors": {s: (slots.get(s) or {}).get("label") or "" for s in SLOTS_COLOR},
                    "has_dpad": bool(act.get("dpad")) or bool(ir_keys),
                    "ir": ir_keys,
                }
            )
        climate = room.get("climate")
        rooms.append(
            {
                "id": room["id"],
                "name": room["name"],
                "temperature": room.get("temperature"),
                "activities": activities,
                "lights": [
                    {
                        "entity": e,
                        "name": ((room.get("light_names") or {}).get(e) or "").strip() or _name(hass, e),
                        "dimmable": _dimmable(hass, e),
                    }
                    for e in _ids(room.get("lights"))
                ],
                "scenes": _items(hass, room.get("scenes")),
                "climate": {"entity": climate, "name": _name(hass, climate)} if climate else None,
                "covers": _items(hass, room.get("covers")),
                "favourites": _items(hass, room.get("favourites")),
                "off": _off_for_remote(hass, room),
            }
        )
    project: dict[str, Any] = {
        "default_room": rooms[0]["id"] if rooms else None,
        "rooms": rooms,
        "device": compile_device(hass, config.get("device") or {}),
    }
    digest = hashlib.sha1(json.dumps(project, sort_keys=True).encode()).hexdigest()
    project["version"] = digest[:12]
    return project


def _versioned(url: str | None, rev: int | None) -> str | None:
    """Add ?v=<save time> so the remote re-downloads a replaced image."""
    url = (url or "").strip() or None
    if url and rev and not url.startswith("icon:"):  # a built-in icon isn't downloaded
        url = f"{url}{'&' if '?' in url else '?'}v={rev}"
    return url


def _clamp_int(value: Any, default: int, low: int, high: int) -> int:
    try:
        return max(low, min(high, int(float(value))))
    except (TypeError, ValueError):
        return default


def _pct(value: Any) -> float | None:
    try:
        return max(0.0, min(100.0, float(value)))
    except (TypeError, ValueError):
        return None


def _key_modes(keys: Any) -> dict[str, str]:
    """What ⏪ ⏵ ⏹ ⏩ do on the remote. Actions stay in HA; the remote only needs the mode."""
    keys = keys if isinstance(keys, dict) else {}
    out: dict[str, str] = {}
    for key, default in MEDIA_KEYS.items():
        cfg = keys.get(key)
        mode = cfg.get("mode") if isinstance(cfg, dict) else None
        out[key] = mode if mode in KEY_MODES else default
    return out


def compile_device(hass: HomeAssistant, dev: dict) -> dict:
    """Remote settings. The logo gets ?v=<when it was saved> so a replaced file re-downloads."""
    if not isinstance(dev, dict):
        dev = {}
    logo = _versioned(dev.get("logo"), dev.get("logo_rev"))
    pickup = dev.get("pickup", "auto")
    sensitivity = dev.get("pickup_sensitivity", "normal")
    return {
        "logo": logo,
        "splash_seconds": _clamp_int(dev.get("splash_seconds"), 2, 0, 10),
        "charging_screen": bool(dev.get("charging_screen", True)),
        "pickup": pickup if pickup in ("off", "auto", "saver") else "auto",
        "pickup_sensitivity": sensitivity if sensitivity in ("low", "normal", "high") else "normal",
        "pin_hash": dev.get("pin_hash") or None,
        "clock": dev.get("clock") if dev.get("clock") in ("off", "charging", "always") else "charging",
        "clock_after": _clamp_int(dev.get("clock_after"), 30, 10, 600),
        "clock_for": _clamp_int(dev.get("clock_for"), 0, 0, 86_400),
        "keys": _key_modes(dev.get("keys")),
        "clock_x": _pct(dev.get("clock_x")),
        "clock_y": _pct(dev.get("clock_y")),
        "home_style": dev.get("home_style") if dev.get("home_style") in HOME_STYLES else "round",
        "icon_set": dev.get("icon_set") if dev.get("icon_set") in ICON_SETS else "realistic",
        "icons": {
            cat: _versioned(url, dev.get("icons_rev"))
            for cat, url in (dev.get("icons") or {}).items()
            if cat in HOME_ICONS and isinstance(url, str) and url.strip()
        } if isinstance(dev.get("icons"), dict) else {},
    }


def tracked_entities(config: dict) -> set[str]:
    """Every entity whose state the remote shows."""
    ids: set[str] = set()
    for room in config.get("rooms") or []:
        for key in ("lights", "covers"):
            ids.update(_ids(room.get(key)))
        for key in ("climate", "temperature"):
            if room.get(key):
                ids.add(room[key])
        for act in room.get("activities") or []:
            for key in ("source", "volume", "now_playing"):
                if isinstance(act.get(key), str) and act.get(key):
                    ids.add(act[key])
    return ids


def _float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _num(value: Any) -> float | None:
    try:
        return round(float(value), 1)
    except (TypeError, ValueError):
        return None


def compact_state(state: State | None, base_url: str | None = None) -> dict:
    """Small per-entity state the app understands (docs/PROTOCOL.md s4)."""
    if state is None:
        return {"s": "unavailable"}
    domain = state.domain
    attrs = state.attributes
    out: dict[str, Any] = {"s": state.state}
    if domain == "light":
        bright = _float(attrs.get("brightness"))
        out["b"] = round(bright * 100 / 255) if bright is not None else (100 if state.state == "on" else 0)
    elif domain == "media_player":
        vol = _float(attrs.get("volume_level"))
        if vol is not None:
            out["v"] = round(vol * 100)
        if attrs.get("is_volume_muted") is not None:
            out["m"] = bool(attrs["is_volume_muted"])
        title = attrs.get("media_title")
        if title:
            out["t"] = str(title)
        artist = attrs.get("media_artist") or attrs.get("media_series_title") or attrs.get("app_name")
        if artist:
            out["a"] = str(artist)
        if attrs.get("source"):
            out["src"] = str(attrs["source"])
        pic = attrs.get("entity_picture")
        if isinstance(pic, str) and pic:
            if pic.startswith("/") and base_url:
                pic = f"{base_url}{pic}"
            if pic.startswith("http"):
                out["pic"] = pic
    elif domain == "climate":
        out["c"] = _num(attrs.get("current_temperature"))
        out["t"] = _num(attrs.get("temperature"))
        modes = attrs.get("hvac_modes")
        if isinstance(modes, (list, tuple)) and modes:
            out["modes"] = [str(m) for m in modes]
    elif domain == "cover":
        pos = _float(attrs.get("current_position"))
        if pos is not None:
            out["p"] = int(pos)
    elif domain == "sensor":
        unit = attrs.get("unit_of_measurement")
        if unit:
            out["u"] = str(unit)
    return out


def ir_codes(config: dict, device: str | None) -> dict[str, str]:
    """The IR codes stored for one IR device (name → code)."""
    lib = config.get("ir") or {}
    codes = lib.get(device) if device else None
    return {str(k): str(v) for k, v in codes.items() if v} if isinstance(codes, dict) else {}


def ir_key_codes(config: dict, act: dict) -> dict[str, str]:
    """Key → code for the activity's IR device; the remote sends these itself."""
    codes = {k.lower(): v for k, v in ir_codes(config, act.get("ir_device")).items()}
    out: dict[str, str] = {}
    for key, names in IR_KEYS.items():
        for name in names:
            if name in codes:
                out[key] = codes[name]
                break
    return out


def parse_ir_lines(text: str) -> tuple[dict[str, str], list[str]]:
    """'name: code' per line → codes, plus the lines that could not be read."""
    codes: dict[str, str] = {}
    bad: list[str] = []
    for raw in (text or "").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, sep, code = line.partition(":")
        name = name.strip().lower().replace(" ", "_")
        code = code.strip()
        if not sep or not name or not code:
            bad.append(line[:40])
            continue
        codes[name] = code
    return codes, bad
