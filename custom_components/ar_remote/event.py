"""Every hardware button as an event entity."""

from __future__ import annotations

from homeassistant.components.event import EventEntity
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import KEYS
from .entity import ArRemoteEntity


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([ButtonEvent(entry.runtime_data)])


class ButtonEvent(ArRemoteEntity, EventEntity):
    _attr_translation_key = "button"
    _attr_event_types = [f"{k.lower()}_{p}" for k in KEYS for p in ("short", "long")]

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "button")

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.runtime.button_listeners.append(self._on_press)
        self.async_on_remove(lambda: self.runtime.button_listeners.remove(self._on_press))

    @callback
    def _on_press(self, key: str, data: dict) -> None:
        event_type = f"{key.lower()}_{data.get('press', 'short')}"
        if event_type not in self._attr_event_types:
            return
        self._trigger_event(
            event_type,
            {k: data.get(k) for k in ("key", "press", "room", "activity", "screen")},
        )
        self.async_write_ha_state()
