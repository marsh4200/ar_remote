"""Shared entity base."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import DOMAIN, NAME
from .runtime import ArRemoteRuntime


class ArRemoteEntity(Entity):
    """Belongs to the remote's device and refreshes on runtime updates."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, runtime: ArRemoteRuntime, key: str) -> None:
        self.runtime = runtime
        entry = runtime.entry
        self._attr_unique_id = f"{entry.entry_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=entry.title,
            manufacturer="AR Smart Home",
            model="Astrion HA100 · AR Remote",
            sw_version=runtime.info.get("version"),
            configuration_url=f"http://{runtime.client.host}:{runtime.client.port}/info",
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self.runtime.signal, self.async_write_ha_state)
        )
