"""Install the AR Remote app over ADB — the 'flash it from HA' path."""

from __future__ import annotations

import logging
import os
import tempfile

from homeassistant.components import persistent_notification
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import RemoteClient, RemoteError
from .const import APP_ACTIVITY, DOMAIN

USB_HELP = (
    "The app isn't on the remote yet and network ADB is off. Plug the remote into a "
    "PC with USB debugging on and run tools/install-usb.bat (Windows) or "
    "tools/install-usb.sh (Mac/Linux) from the ar_remote repo. After that, updates "
    "install from Home Assistant without ADB."
)

_LOGGER = logging.getLogger(__name__)

DEVICE_APK = "/data/local/tmp/ar-remote.apk"


def _load_signer(key_path: str):
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner

    if not os.path.isfile(key_path):
        keygen(key_path)
    with open(key_path, encoding="utf-8") as priv, open(f"{key_path}.pub", encoding="utf-8") as pub:
        return PythonRSASigner(pub.read(), priv.read())


def _write_temp(data: bytes) -> str:
    fd, path = tempfile.mkstemp(suffix=".apk")
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
    return path


async def async_install_app(hass: HomeAssistant, host: str, port: int, apk_url: str) -> str:
    """Download the APK, push it, install it, make it the launcher, start it."""
    from adb_shell.adb_device_async import AdbDeviceTcpAsync

    session = async_get_clientsession(hass)
    try:
        async with session.get(apk_url, timeout=120) as resp:
            resp.raise_for_status()
            apk = await resp.read()
    except Exception as err:
        raise HomeAssistantError(f"Could not download the AR Remote app: {err}") from err
    if len(apk) < 10_000:
        raise HomeAssistantError("Downloaded APK is too small — check the release asset name")

    key_path = hass.config.path(".storage", f"{DOMAIN}_adbkey")
    signer = await hass.async_add_executor_job(_load_signer, key_path)
    local = await hass.async_add_executor_job(_write_temp, apk)

    device = AdbDeviceTcpAsync(host, port, default_transport_timeout_s=10.0)
    try:
        try:
            await device.connect(rsa_keys=[signer], auth_timeout_s=30.0)
        except Exception as err:
            raise HomeAssistantError(
                f"ADB connect to {host}:{port} failed — enable network ADB on the remote "
                f"and accept the 'Allow USB debugging' prompt on its screen ({err})"
            ) from err

        await device.push(local, DEVICE_APK, read_timeout_s=120.0)
        out = str(
            await device.shell(f"pm install -r -g {DEVICE_APK}", read_timeout_s=180.0, timeout_s=180.0)
        )
        if "Success" not in out:
            raise HomeAssistantError(f"Install failed: {out.strip()}")
        await device.shell(f"rm {DEVICE_APK}")
        home = str(await device.shell(f"cmd package set-home-activity {APP_ACTIVITY}"))
        _LOGGER.debug("set-home-activity: %s", home.strip())
        # Keep pick-up detection working in deep sleep (Doze).
        await device.shell(f"dumpsys deviceidle whitelist +{APP_ACTIVITY.split('/')[0]}")
        await device.shell(f"am start -n {APP_ACTIVITY}")
        _LOGGER.info("AR Remote app installed on %s", host)
        return out.strip()
    finally:
        await device.close()
        await hass.async_add_executor_job(os.remove, local)


async def async_install_or_update(
    hass: HomeAssistant, client: RemoteClient, adb_port: int, apk_url: str
) -> str:
    """Update through the app itself when it's running; ADB only for a first install."""
    try:
        info = await client.info()
    except RemoteError:
        info = None

    if info and info.get("self_update"):
        if info.get("can_install") is False:
            raise HomeAssistantError(
                "The app may not install updates yet. Run tools/install-usb once more over USB "
                "(it grants the install permission), or allow 'Install unknown apps' for "
                "AR Remote in the remote's settings."
            )
        await client.update(apk_url)
        persistent_notification.async_create(
            hass,
            f"The remote at {client.host} is downloading the update. Tap **Install** on its "
            "screen when Android asks.",
            title="AR Remote update",
            notification_id=f"{DOMAIN}_update_{client.host}",
        )
        return "self-update started"

    try:
        return await async_install_app(hass, client.host, adb_port, apk_url)
    except HomeAssistantError as err:
        if "ADB connect" in str(err):
            raise HomeAssistantError(USB_HELP) from err
        raise
