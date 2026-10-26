"""HTTP client for the AR Remote app running on the remote."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

TIMEOUT = aiohttp.ClientTimeout(total=6)


class RemoteError(Exception):
    """The remote did not answer."""


class RemoteClient:
    """Talks to the app's built-in server on port 8080."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int) -> None:
        self._session = session
        self.host = host
        self.port = port

    @property
    def base(self) -> str:
        return f"http://{self.host}:{self.port}"

    async def _request(self, method: str, path: str, payload: Any = None) -> dict:
        try:
            async with self._session.request(
                method, f"{self.base}{path}", json=payload, timeout=TIMEOUT
            ) as resp:
                data = await resp.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as err:
            raise RemoteError(f"{method} {path}: {err}") from err
        if not isinstance(data, dict):
            raise RemoteError(f"{method} {path}: unexpected reply")
        if data.get("ok") is False:
            raise RemoteError(f"{method} {path}: {data.get('error')}")
        return data

    async def info(self) -> dict:
        return await self._request("GET", "/info")

    async def link(self, webhook_url: str) -> None:
        await self._request("POST", "/link", {"webhook_url": webhook_url})

    async def push_project(self, project: dict) -> None:
        await self._request("POST", "/project", project)

    async def push_state(self, state: dict) -> None:
        await self._request("POST", "/state", state)

    async def update(self, apk_url: str) -> None:
        await self._request("POST", "/update", {"url": apk_url})

    async def navigate(self, screen: str, room: str | None = None) -> None:
        await self._request("POST", "/navigate", {"screen": screen, "room": room})
