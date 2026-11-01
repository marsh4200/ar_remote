"""The remote's own IR blaster as a Home Assistant remote entity.

    service: remote.send_command
    target: {entity_id: remote.ar_remote_ir_blaster}
    data: {device: "Samsung TV", command: "power"}

`command` can also be a code itself (Pronto, Broadlink base64, nec:…, raw:…).
"""

from __future__ import annotations

import asyncio
from collections.abc import Iterable
from typing import Any

from homeassistant.components.remote import RemoteEntity, RemoteEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .api import RemoteError
from .const import CONF_PROJECT
from .entity import ArRemoteEntity
from .project import ir_codes


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([IrBlaster(entry.runtime_data)])


def _looks_like_code(text: str) -> bool:
    t = text.strip().lower()
    return t.startswith(("nec:", "samsung:", "raw:", "pronto:", "b64:", "jg", "0000 "))


class IrBlaster(ArRemoteEntity, RemoteEntity):
    _attr_translation_key = "ir_blaster"
    _attr_supported_features = RemoteEntityFeature(0)

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "ir_blaster")

    @property
    def is_on(self) -> bool:
        return True

    @property
    def available(self) -> bool:
        return self.runtime.info.get("ir", True) is not False

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        lib = (self.runtime.entry.options.get(CONF_PROJECT) or {}).get("ir") or {}
        return {"devices": {name: sorted(codes) for name, codes in lib.items() if isinstance(codes, dict)}}

    def _resolve(self, device: str | None, command: str) -> str:
        config = self.runtime.entry.options.get(CONF_PROJECT) or {}
        if device:
            codes = {k.lower(): v for k, v in ir_codes(config, device).items()}
            if not codes and not (config.get("ir") or {}).get(device):
                raise HomeAssistantError(f"No IR device called {device!r} in AR Remote → Configure → IR devices")
            code = codes.get(command.strip().lower().replace(" ", "_"))
            if code:
                return code
            if _looks_like_code(command):
                return command
            raise HomeAssistantError(f"{device} has no IR code called {command!r}")
        if "/" in command and not _looks_like_code(command):
            dev, _, cmd = command.partition("/")
            return self._resolve(dev.strip(), cmd)
        return command

    async def async_send_command(self, command: Iterable[str], **kwargs: Any) -> None:
        device = kwargs.get("device")
        repeats = int(kwargs.get("num_repeats") or 1)
        delay = float(kwargs.get("delay_secs") or 0.4)
        commands = list(command)
        for i, cmd in enumerate(commands):
            code = self._resolve(device, cmd)
            try:
                await self.runtime.client.ir(code, repeats)
            except RemoteError as err:
                raise HomeAssistantError(f"Remote did not send IR: {err}") from err
            if i < len(commands) - 1:
                await asyncio.sleep(delay)
