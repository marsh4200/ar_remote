"""One activity selector per room — pick 'Off' or an activity, like a C4 room."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import ArRemoteEntity

OFF = "Off"


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    rt = entry.runtime_data
    async_add_entities(RoomActivity(rt, room) for room in rt.config.get("rooms", []))


class RoomActivity(ArRemoteEntity, SelectEntity):
    _attr_translation_key = "room_activity"

    def __init__(self, runtime, room: dict) -> None:
        super().__init__(runtime, f"room_{room['id']}")
        self._room_id = room["id"]
        self._attr_translation_placeholders = {"room": room["name"]}
        self._by_name = {a["name"]: a["id"] for a in room.get("activities", [])}
        self._attr_options = [OFF, *self._by_name]

    @property
    def current_option(self) -> str:
        act = self.runtime.activity(self._room_id, self.runtime.current.get(self._room_id))
        return act["name"] if act else OFF

    @property
    def extra_state_attributes(self) -> dict:
        """What the last Room Off did — check here when something didn't go off."""
        return {
            "last_room_off": self.runtime.last_room_off.get(self._room_id),
            "last_button": self.runtime.last_button.get(self._room_id),
        }

    async def async_select_option(self, option: str) -> None:
        if option == OFF:
            await self.runtime.room_off(self._room_id)
        elif option in self._by_name:
            await self.runtime.start_activity(self._room_id, self._by_name[option])
