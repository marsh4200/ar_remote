"""Compile the stored composer config into what the remote renders."""

from __future__ import annotations

import hashlib
import json
from typing import Any

from homeassistant.core import HomeAssistant, State

from .const import SLOTS_COLOR, SLOTS_CUSTOM


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


def _items(hass: HomeAssistant, ids: list[str] | None) -> list[dict]:
    return [{"entity": e, "name": _name(hass, e)} for e in ids or []]


def compile_project(hass: HomeAssistant, config: dict) -> dict:
    """Build the project JSON (labels and entity ids, never actions)."""
    rooms = []
    for room in config.get("rooms", []):
        activities = []
        for act in room.get("activities", []):
            slots = act.get("slots", {})
            activities.append(
                {
                    "id": act["id"],
                    "name": act["name"],
                    "kind": act.get("kind", "watch"),
                    "logo": _versioned(act.get("logo"), act.get("logo_rev")),
                    "source": act.get("source"),
                    "volume": act.get("volume") or act.get("source"),
                    "custom": [slots.get(s, {}).get("label", "") for s in SLOTS_CUSTOM],
                    "colors": {s: slots.get(s, {}).get("label", "") for s in SLOTS_COLOR},
                    "has_dpad": bool(act.get("dpad")),
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
                    {"entity": e, "name": _name(hass, e), "dimmable": _dimmable(hass, e)}
                    for e in room.get("lights", [])
                ],
                "scenes": _items(hass, room.get("scenes")),
                "climate": {"entity": climate, "name": _name(hass, climate)} if climate else None,
                "covers": _items(hass, room.get("covers")),
                "favourites": _items(hass, room.get("favourites")),
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
    if url and rev:
        url = f"{url}{'&' if '?' in url else '?'}v={rev}"
    return url


def compile_device(hass: HomeAssistant, dev: dict) -> dict:
    """Remote settings. The logo gets ?v=<when it was saved> so a replaced file re-downloads."""
    logo = _versioned(dev.get("logo"), dev.get("logo_rev"))
    pickup = dev.get("pickup", "auto")
    sensitivity = dev.get("pickup_sensitivity", "normal")
    return {
        "logo": logo,
        "splash_seconds": max(0, min(10, int(dev.get("splash_seconds", 2)))),
        "charging_screen": bool(dev.get("charging_screen", True)),
        "pickup": pickup if pickup in ("off", "auto", "saver") else "auto",
        "pickup_sensitivity": sensitivity if sensitivity in ("low", "normal", "high") else "normal",
        "pin_hash": dev.get("pin_hash") or None,
        "clock": dev.get("clock") if dev.get("clock") in ("off", "charging", "always") else "charging",
        "clock_after": max(10, min(600, int(dev.get("clock_after", 30)))),
        "clock_x": dev.get("clock_x"),
        "clock_y": dev.get("clock_y"),
    }


def tracked_entities(config: dict) -> set[str]:
    """Every entity whose state the remote shows."""
    ids: set[str] = set()
    for room in config.get("rooms", []):
        for key in ("lights", "covers"):
            ids.update(room.get(key, []))
        for key in ("climate", "temperature"):
            if room.get(key):
                ids.add(room[key])
        for act in room.get("activities", []):
            for key in ("source", "volume"):
                if act.get(key):
                    ids.add(act[key])
    return ids


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
        bright = attrs.get("brightness")
        out["b"] = round(bright * 100 / 255) if bright is not None else (100 if state.state == "on" else 0)
    elif domain == "media_player":
        vol = attrs.get("volume_level")
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
        if pic:
            if pic.startswith("/") and base_url:
                pic = f"{base_url}{pic}"
            if pic.startswith("http"):
                out["pic"] = pic
    elif domain == "climate":
        out["c"] = _num(attrs.get("current_temperature"))
        out["t"] = _num(attrs.get("temperature"))
        modes = attrs.get("hvac_modes")
        if modes:
            out["modes"] = [str(m) for m in modes]
    elif domain == "cover":
        pos = attrs.get("current_position")
        if pos is not None:
            out["p"] = int(pos)
    elif domain == "sensor":
        unit = attrs.get("unit_of_measurement")
        if unit:
            out["u"] = str(unit)
    return out
