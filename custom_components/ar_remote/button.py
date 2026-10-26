"""Program remote / install app."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .adb import async_install_or_update
from .api import RemoteError
from .const import APK_URL, CONF_ADB_PORT
from .entity import ArRemoteEntity


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    rt = entry.runtime_data
    async_add_entities([ProgramButton(rt), InstallButton(rt)])


class ProgramButton(ArRemoteEntity, ButtonEntity):
    _attr_translation_key = "program"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "program")

    async def async_press(self) -> None:
        try:
            await self.runtime.async_program()
        except RemoteError as err:
            raise HomeAssistantError(f"Remote did not answer — wake it and try again ({err})") from err


class InstallButton(ArRemoteEntity, ButtonEntity):
    _attr_translation_key = "install_app"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "install_app")

    async def async_press(self) -> None:
        rt = self.runtime
        await async_install_or_update(self.hass, rt.client, rt.entry.data[CONF_ADB_PORT], APK_URL)
