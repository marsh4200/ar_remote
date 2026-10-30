"""AR Remote — Halo-style control for the Astrion remote, programmed from Home Assistant."""

from __future__ import annotations

import logging

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv

from .adb import async_install_or_update
from .api import RemoteError
from .const import APK_URL, CONF_ADB_PORT, DOMAIN, MEDIA_DIR, MEDIA_URL
from .runtime import ArRemoteRuntime

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.EVENT,
    Platform.SELECT,
    Platform.SENSOR,
]

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

_LOGGER = logging.getLogger(__name__)

SERVICE_PROGRAM = "program"
SERVICE_INSTALL = "install_app"
SERVICE_START = "start_activity"
SERVICE_ROOM_OFF = "room_off"

type ArRemoteConfigEntry = ConfigEntry[ArRemoteRuntime]


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Register services once."""

    def _runtimes(call: ServiceCall) -> list[ArRemoteRuntime]:
        wanted = call.data.get("config_entry_id")
        out = []
        for entry in hass.config_entries.async_entries(DOMAIN):
            if getattr(entry, "runtime_data", None) is None:
                continue
            if wanted and entry.entry_id != wanted:
                continue
            out.append(entry.runtime_data)
        if not out:
            raise HomeAssistantError("No loaded AR Remote matches")
        return out

    async def program(call: ServiceCall) -> None:
        for rt in _runtimes(call):
            try:
                await rt.async_program()
            except RemoteError as err:
                raise HomeAssistantError(f"{rt.client.host} did not answer: {err}") from err

    async def install(call: ServiceCall) -> None:
        for rt in _runtimes(call):
            await async_install_or_update(
                hass, rt.client, rt.entry.data[CONF_ADB_PORT], call.data.get("apk_url", APK_URL)
            )

    async def start(call: ServiceCall) -> None:
        for rt in _runtimes(call):
            await rt.start_activity(call.data["room"], call.data["activity"])

    async def room_off(call: ServiceCall) -> None:
        for rt in _runtimes(call):
            await rt.room_off(call.data["room"])

    await _serve_media(hass)

    base = {vol.Optional("config_entry_id"): cv.string}
    hass.services.async_register(DOMAIN, SERVICE_PROGRAM, program, vol.Schema(base))
    hass.services.async_register(
        DOMAIN, SERVICE_INSTALL, install,
        vol.Schema({**base, vol.Optional("apk_url"): cv.url}),
    )
    hass.services.async_register(
        DOMAIN, SERVICE_START, start,
        vol.Schema({**base, vol.Required("room"): cv.string, vol.Required("activity"): cv.string}),
    )
    hass.services.async_register(
        DOMAIN, SERVICE_ROOM_OFF, room_off,
        vol.Schema({**base, vol.Required("room"): cv.string}),
    )
    return True


async def _serve_media(hass: HomeAssistant) -> None:
    """Serve uploaded logos at /ar_remote_media (no login, like /local)."""
    import os

    if getattr(hass, "http", None) is None:
        return
    folder = hass.config.path(MEDIA_DIR)
    await hass.async_add_executor_job(lambda: os.makedirs(folder, exist_ok=True))
    try:
        from homeassistant.components.http import StaticPathConfig

        await hass.http.async_register_static_paths([StaticPathConfig(MEDIA_URL, folder, False)])
    except ImportError:  # Home Assistant before 2024.7
        hass.http.register_static_path(MEDIA_URL, folder, False)


async def async_setup_entry(hass: HomeAssistant, entry: ArRemoteConfigEntry) -> bool:
    runtime = ArRemoteRuntime(hass, entry)
    try:
        await runtime.async_setup()
    except Exception:
        # Undo the webhook / listeners, otherwise every retry fails with
        # "webhook already registered" and the remote stays dead until a restart.
        await runtime.async_unload()
        raise
    entry.runtime_data = runtime
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    return True


async def _options_updated(hass: HomeAssistant, entry: ArRemoteConfigEntry) -> None:
    """Composer saved: reload so new rooms get entities, then program the remote."""
    if not await hass.config_entries.async_reload(entry.entry_id):
        return  # setup failed; HA shows the error on the integration card
    runtime: ArRemoteRuntime | None = getattr(entry, "runtime_data", None)
    if runtime is None:
        return
    try:
        await runtime.async_program()
    except RemoteError:
        # Remote asleep — it picks the new project up on its next hello/poll.
        pass
    except Exception:  # noqa: BLE001 - never let a save crash the options flow
        _LOGGER.exception("AR Remote: programming after save failed")


async def async_unload_entry(hass: HomeAssistant, entry: ArRemoteConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    runtime = getattr(entry, "runtime_data", None)
    if unloaded and runtime is not None:
        await runtime.async_unload()
    return unloaded
