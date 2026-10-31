"""End-to-end tests: config flow, composer, programming, webhook routing."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_mock_service

from custom_components.ar_remote.const import DOMAIN, EVENT_BUTTON

HOST = "192.168.88.6"


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    yield


@pytest.fixture
def client():
    with patch("custom_components.ar_remote.runtime.RemoteClient") as cls:
        inst = cls.return_value
        inst.host = HOST
        inst.port = 8080
        inst.info = AsyncMock(return_value={"version": "1.0.0", "project_version": "", "webhook_url": "", "battery": 80})
        inst.link = AsyncMock()
        inst.push_project = AsyncMock()
        inst.push_state = AsyncMock()
        yield inst


PROJECT = {
    "rooms": [
        {
            "id": "living_room",
            "name": "Living Room",
            "lights": ["light.downlights"],
            "scenes": ["scene.evening"],
            "climate": "climate.lounge",
            "covers": [],
            "favourites": ["script.movie"],
            "temperature": None,
            "off_action": None,
            "activities": [
                {
                    "id": "apple_tv", "name": "Apple TV", "kind": "watch",
                    "source": "media_player.atv", "source_input": None,
                    "volume": "media_player.denon", "input": "MEDIA PLAYER", "input_delay": 0,
                    "dpad": "remote.atv", "command_set": "apple_tv", "power_on": None,
                    "slots": {"red": {"label": "Lights 50%", "action": "script.dim"},
                              "custom1": {"label": "Menu", "action": "script.menu"}},
                },
                {
                    "id": "dstv", "name": "DStv", "kind": "watch",
                    "source": "media_player.dstv", "volume": "media_player.denon",
                    "input": "SAT/CBL", "dpad": None, "command_set": "ir_generic", "slots": {},
                },
            ],
        }
    ]
}


async def _setup(hass: HomeAssistant, client) -> MockConfigEntry:
    hass.config.internal_url = "http://192.168.88.2:8123"
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN,
        title=f"AR Remote ({HOST})",
        data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "abc123"},
        options={"project": PROJECT},
        unique_id=HOST,
    )
    entry.add_to_hass(hass)
    hass.states.async_set("light.downlights", "on", {"brightness": 128, "friendly_name": "Downlights", "supported_color_modes": ["brightness"]})
    hass.states.async_set("media_player.denon", "on", {"volume_level": 0.38, "is_volume_muted": False})
    hass.states.async_set("media_player.atv", "playing", {"media_title": "Dune", "friendly_name": "Apple TV"})
    hass.states.async_set("climate.lounge", "cool", {"temperature": 22, "current_temperature": 24, "hvac_modes": ["off", "cool"]})
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_config_flow_creates_entry_without_app(hass: HomeAssistant) -> None:
    with patch("custom_components.ar_remote.config_flow.RemoteClient") as cls:
        from custom_components.ar_remote.api import RemoteError

        cls.return_value.info = AsyncMock(side_effect=RemoteError("down"))
        result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
        assert result["type"] is FlowResultType.FORM
        with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
            result = await hass.config_entries.flow.async_configure(
                result["flow_id"], {"host": HOST, "port": 8080, "adb_port": 5555}
            )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["data"]["host"] == HOST
    assert result["data"]["webhook_id"]


async def test_composer_builds_room_activity_buttons(hass: HomeAssistant, client) -> None:
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": {"rooms": []}},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        assert flow["type"] is FlowResultType.MENU
        assert flow["menu_options"] == ["add_room", "save"]

        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "add_room"})
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"name": "Living Room", "lights": ["light.a"]}
        )
        assert flow["type"] is FlowResultType.MENU
        assert "add_activity" in flow["menu_options"]

        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "add_activity"})
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"],
            {"room": "living_room", "name": "Apple TV", "kind": "watch", "source": "media_player.atv",
             "volume": "media_player.denon", "dpad": "remote.atv", "command_set": "apple_tv"},
        )
        assert flow["step_id"] == "sources"
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"input": "HDMI 2"})
        assert flow["step_id"] == "start_steps"
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"],
            {"step_1": {"entities": ["light.a"], "do": "dim", "level": 30, "wait": 2}},
        )
        assert flow["step_id"] == "end_steps"
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"],
            {"advanced": {"start_actions": [{"action": "light.turn_on", "target": {"entity_id": "light.a"}}],
                          "turn_off_on_end": True}},
        )
        assert flow["step_id"] == "buttons"
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"red_label": "Dim", "red_action": "script.dim"}
        )
        assert flow["type"] is FlowResultType.MENU
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "save"})
    assert flow["type"] is FlowResultType.CREATE_ENTRY
    room = flow["data"]["project"]["rooms"][0]
    assert room["id"] == "living_room"
    act = room["activities"][0]
    assert act["id"] == "apple_tv" and act["dpad"] == "remote.atv"
    assert act["slots"]["red"] == {"label": "Dim", "action": "script.dim"}
    assert act["input"] == "HDMI 2"
    assert act["start_actions"][0]["action"] == "light.turn_on"
    assert act["start_steps"] == [{"entities": ["light.a"], "do": "dim", "level": 30, "wait": 2.0}]


async def test_setup_programs_and_entities(hass: HomeAssistant, client) -> None:
    await _setup(hass, client)
    client.link.assert_awaited()
    client.push_project.assert_awaited()
    project = client.push_project.await_args.args[0]
    act = project["rooms"][0]["activities"][0]
    assert act["colors"]["red"] == "Lights 50%" and act["custom"][0] == "Menu"
    assert "script.dim" not in str(project)  # actions never leave HA
    state = client.push_state.await_args.args[0]
    assert state["entities"]["light.downlights"] == {"s": "on", "b": 50}
    assert state["entities"]["media_player.denon"]["v"] == 38

    select = hass.states.get("select.ar_remote_192_168_88_6_living_room_activity")
    assert select is not None and select.state == "Off"
    assert hass.states.get("sensor.ar_remote_192_168_88_6_battery").state == "80"


async def test_webhook_activity_keys_and_events(hass: HomeAssistant, client, hass_client_no_auth) -> None:
    await _setup(hass, client)
    http = await hass_client_no_auth()
    on = async_mock_service(hass, "media_player", "turn_on")
    off = async_mock_service(hass, "media_player", "turn_off")
    select_source = async_mock_service(hass, "media_player", "select_source")
    send_cmd = async_mock_service(hass, "remote", "send_command")
    vol_up = async_mock_service(hass, "media_player", "volume_up")
    script = async_mock_service(hass, "script", "turn_on")
    events = []
    hass.bus.async_listen(EVENT_BUTTON, lambda e: events.append(e.data))

    async def post(msg):
        resp = await http.post("/api/webhook/abc123", json=msg)
        assert resp.status == 200
        body = await resp.json()
        await hass.async_block_till_done()
        return body

    hello = await post({"type": "hello", "project_version": "old"})
    assert hello["ok"] and hello["project"]["rooms"][0]["id"] == "living_room"
    hello = await post({"type": "hello", "project_version": hello["project"]["version"]})
    assert hello["project"] is None

    await post({"type": "activity", "room": "living_room", "activity": "apple_tv"})
    assert {c.data["entity_id"] for c in on} == {"media_player.denon", "media_player.atv"}
    assert select_source[0].data == {"entity_id": "media_player.denon", "source": "MEDIA PLAYER"}
    assert hass.states.get("select.ar_remote_192_168_88_6_living_room_activity").state == "Apple TV"

    await post({"type": "key", "key": "UP", "press": "short", "route": "device", "room": "living_room"})
    assert send_cmd[-1].data == {"entity_id": "remote.atv", "command": "up"}
    await post({"type": "key", "key": "VOLUME_UP", "press": "repeat", "route": "device", "room": "living_room"})
    assert vol_up[-1].data == {"entity_id": "media_player.denon"}
    await post({"type": "key", "key": "RED_BUTTON", "press": "short", "route": "device", "room": "living_room"})
    assert script[-1].data == {"entity_id": "script.dim"}
    await post({"type": "key", "key": "LEFT", "press": "short", "route": "ui", "room": "living_room"})
    assert len(send_cmd) == 1  # UI-routed keys only fire events
    assert [e["key"] for e in events] == ["UP", "RED_BUTTON", "LEFT"]  # repeats aren't events

    # Switching to DStv turns off the Apple TV but keeps the shared amp on.
    await post({"type": "activity", "room": "living_room", "activity": "dstv"})
    assert [c.data["entity_id"] for c in off] == ["media_player.atv"]

    await post({"type": "room_off", "room": "living_room"})
    assert {c.data["entity_id"] for c in off[1:]} == {"media_player.atv", "media_player.denon", "media_player.dstv"}
    assert hass.states.get("select.ar_remote_192_168_88_6_living_room_activity").state == "Off"

    await post({"type": "battery", "level": 41, "charging": True})
    assert hass.states.get("sensor.ar_remote_192_168_88_6_battery").state == "41"


async def test_state_changes_are_pushed(hass: HomeAssistant, client) -> None:
    await _setup(hass, client)
    client.push_state.reset_mock()
    hass.states.async_set("light.downlights", "off", {})
    await hass.async_block_till_done()
    from datetime import timedelta
    from homeassistant.util import dt as dt_util
    from pytest_homeassistant_custom_component.common import async_fire_time_changed

    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(seconds=1))
    await hass.async_block_till_done()
    payload = client.push_state.await_args.args[0]
    assert payload == {"entities": {"light.downlights": {"s": "off", "b": 0}}, "full": False}


async def test_install_button_uses_self_update(hass: HomeAssistant, client) -> None:
    await _setup(hass, client)
    from custom_components.ar_remote.adb import async_install_or_update

    client.info = AsyncMock(return_value={"self_update": True, "can_install": True})
    client.update = AsyncMock()
    with patch("custom_components.ar_remote.adb.async_install_app") as adb:
        result = await async_install_or_update(hass, client, 5555, "https://x/ar-remote.apk")
    assert result == "self-update started"
    client.update.assert_awaited_once_with("https://x/ar-remote.apk")
    adb.assert_not_called()


async def test_install_without_app_or_adb_points_to_usb(hass: HomeAssistant, client) -> None:
    await _setup(hass, client)
    from homeassistant.exceptions import HomeAssistantError
    from custom_components.ar_remote.adb import async_install_or_update
    from custom_components.ar_remote.api import RemoteError

    client.info = AsyncMock(side_effect=RemoteError("down"))
    with patch(
        "custom_components.ar_remote.adb.async_install_app",
        side_effect=HomeAssistantError("ADB connect to x failed"),
    ), pytest.raises(HomeAssistantError, match="install-usb"):
        await async_install_or_update(hass, client, 5555, "https://x/ar-remote.apk")


async def test_source_dropdown_comes_from_device(hass: HomeAssistant, client) -> None:
    hass.states.async_set("media_player.denon", "on", {"source_list": ["HDMI 1", "MEDIA PLAYER"]})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": {"rooms": [{"id": "lounge", "name": "Lounge", "activities": []}]}},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "add_activity"})
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"room": "lounge", "name": "Music", "kind": "listen",
                              "volume": "media_player.denon", "command_set": "apple_tv"},
        )
    assert flow["step_id"] == "sources"
    selector = flow["data_schema"].schema["input"]
    assert selector.config["options"] == ["HDMI 1", "MEDIA PLAYER"]


async def test_start_sequence_runs_and_room_off_cleans_up(hass: HomeAssistant, client, hass_client_no_auth) -> None:
    project = {"rooms": [{
        "id": "lounge", "name": "Lounge", "activities": [{
            "id": "music", "name": "Music", "kind": "listen",
            "source": "media_player.spotify", "volume": "media_player.amp", "slots": {},
            "start_actions": [
                {"action": "switch.turn_on", "target": {"entity_id": "switch.amp_power"}},
                {"delay": {"seconds": 2}},
                {"action": "light.turn_on", "target": {"entity_id": "light.cove"}, "data": {"brightness_pct": 30}},
            ],
            "stop_actions": [{"action": "script.turn_on", "target": {"entity_id": "script.goodnight"}}],
            "turn_off_on_end": True,
        }],
    }]}
    hass.config.internal_url = "http://192.168.88.2:8123"
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "seq"},
        options={"project": project},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()

    switch_on = async_mock_service(hass, "switch", "turn_on")
    light_on = async_mock_service(hass, "light", "turn_on")
    pause = async_mock_service(hass, "media_player", "media_pause")
    mp_off = async_mock_service(hass, "media_player", "turn_off")
    ha_off = async_mock_service(hass, "homeassistant", "turn_off")
    goodnight = async_mock_service(hass, "script", "turn_on")
    async_mock_service(hass, "media_player", "turn_on")

    rt = entry.runtime_data
    import time
    started = time.monotonic()
    await rt.start_activity("lounge", "music")
    await hass.async_block_till_done()
    assert len(switch_on) == 1
    assert light_on[0].data == {"entity_id": ["light.cove"], "brightness_pct": 30}
    assert time.monotonic() - started >= 1.9  # the 2 s delay between steps was honoured

    await rt.room_off("lounge")
    await hass.async_block_till_done()
    assert {c.data["entity_id"] for c in pause} == {"media_player.spotify", "media_player.amp"}
    assert {c.data["entity_id"] for c in mp_off} == {"media_player.spotify", "media_player.amp"}
    assert {c.data["entity_id"] for c in ha_off} == {"switch.amp_power", "light.cove"}
    assert len(goodnight) == 1


async def test_also_turn_on_devices_in_composer(hass: HomeAssistant, client) -> None:
    hass.states.async_set("media_player.projector", "off", {"friendly_name": "Projector", "source_list": ["HDMI 1", "HDMI 2"]})
    hass.states.async_set("media_player.amp", "off", {"friendly_name": "Amp", "source_list": ["BD", "SAT"]})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": {"rooms": [{"id": "cinema", "name": "Cinema", "activities": []}]}},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "add_activity"})
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"room": "cinema", "name": "Movie Time", "kind": "watch",
                              "source": "media_player.atv", "command_set": "apple_tv",
                              "devices": ["media_player.projector", "switch.screen", "media_player.amp"]},
        )
        assert flow["step_id"] == "sources"
        schema = flow["data_schema"].schema
        assert schema["Projector input"].config["options"] == ["HDMI 1", "HDMI 2"]
        assert "Amp input" in schema
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"Projector input": "HDMI 2"},  # amp left empty: power on only
        )
        assert flow["step_id"] == "start_steps"
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "save"})
    act = entry.options["project"]["rooms"][0]["activities"][0]
    assert act["devices"] == [
        {"entity": "media_player.projector", "source": "HDMI 2", "off": None},
        {"entity": "switch.screen", "source": None, "off": None},
        {"entity": "media_player.amp", "source": None, "off": None},
    ]


async def test_also_turn_on_devices_power_on_and_off(hass: HomeAssistant, client) -> None:
    project = {"rooms": [{
        "id": "cinema", "name": "Cinema", "activities": [
            {"id": "movie", "name": "Movie Time", "kind": "watch", "source": "media_player.atv", "slots": {}, "input_delay": 0,
             "devices": [{"entity": "media_player.projector", "source": "HDMI 2"},
                         {"entity": "switch.screen", "source": None},
                         {"entity": "media_player.amp", "source": None}]},
            {"id": "music", "name": "Music", "kind": "listen", "source": "media_player.spotify", "slots": {}, "input_delay": 0,
             "devices": [{"entity": "media_player.amp", "source": "NET"}]},
        ],
    }]}
    hass.config.internal_url = "http://192.168.88.2:8123"
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "dev"},
        options={"project": project},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()

    mp_on = async_mock_service(hass, "media_player", "turn_on")
    sw_on = async_mock_service(hass, "switch", "turn_on")
    select_source = async_mock_service(hass, "media_player", "select_source")
    async_mock_service(hass, "media_player", "media_pause")
    mp_off = async_mock_service(hass, "media_player", "turn_off")
    ha_off = async_mock_service(hass, "homeassistant", "turn_off")

    rt = entry.runtime_data
    await rt.start_activity("cinema", "movie")
    await hass.async_block_till_done()
    assert {c.data["entity_id"] for c in mp_on} == {"media_player.atv", "media_player.projector", "media_player.amp"}
    assert [c.data["entity_id"] for c in sw_on] == ["switch.screen"]
    assert [c.data for c in select_source] == [{"entity_id": "media_player.projector", "source": "HDMI 2"}]

    # Switching to Music keeps the amp on (and moves its input), turns the rest off.
    await rt.start_activity("cinema", "music")
    await hass.async_block_till_done()
    assert {c.data["entity_id"] for c in mp_off} == {"media_player.atv", "media_player.projector"}
    assert [c.data["entity_id"] for c in ha_off] == ["switch.screen"]
    assert select_source[-1].data == {"entity_id": "media_player.amp", "source": "NET"}

    await rt.room_off("cinema")
    await hass.async_block_till_done()
    assert "media_player.amp" in {c.data["entity_id"] for c in mp_off}


async def test_remote_settings_logo_charging_pickup(hass: HomeAssistant, client) -> None:
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": {"rooms": []}},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        assert "remote_settings" in flow["menu_options"]
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "remote_settings"})
        assert flow["step_id"] == "remote_settings"
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"],
            {"logo": "/local/logo.png", "splash_seconds": 3, "charging_screen": True,
             "pickup": "saver", "pickup_sensitivity": "high"},
        )
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "save"})
    dev = entry.options["project"]["device"]
    assert dev["pickup"] == "saver" and dev["splash_seconds"] == 3 and dev["logo_rev"]

    from custom_components.ar_remote.project import compile_project

    compiled = compile_project(hass, entry.options["project"])["device"]
    assert compiled["logo"] == f"/local/logo.png?v={dev['logo_rev']}"
    assert compiled == {**compiled, "charging_screen": True, "pickup": "saver", "pickup_sensitivity": "high"}
    # 2.1's "always" is plain "auto" now.
    assert compile_project(hass, {"rooms": [], "device": {"pickup": "always"}})["device"]["pickup"] == "auto"
    # Old projects without settings get the defaults.
    assert compile_project(hass, {"rooms": []})["device"]["pickup"] == "auto"


async def test_copy_activity_and_logo(hass: HomeAssistant, client) -> None:
    project = {"rooms": [{"id": "lounge", "name": "Lounge", "activities": [{
        "id": "netflix", "name": "Netflix", "kind": "watch", "source": "media_player.atv",
        "source_input": "Netflix", "command_set": "apple_tv", "logo": "/local/netflix.png", "logo_rev": 1,
        "devices": [{"entity": "media_player.projector", "source": "HDMI 2"}],
        "slots": {"red": {"label": "Lights", "action": "script.dim"}},
    }]}]}
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": project},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        assert "copy_activity" in flow["menu_options"]
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "copy_activity"})
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"activity": "lounge/netflix", "name": "Prime"},
        )
        assert flow["step_id"] == "edit_activity"
        flow = await hass.config_entries.options.async_configure(
            flow["flow_id"], {"name": "Prime", "kind": "watch", "source": "media_player.atv",
                              "command_set": "apple_tv", "logo": "/local/prime.png",
                              "devices": ["media_player.projector"]},
        )
        assert flow["step_id"] == "sources"
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"source_input": "Prime Video", "media_player.projector input": "HDMI 2"})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {})  # start steps
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {})  # off steps
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"red_label": "Lights", "red_action": "script.dim"})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "save"})
    acts = entry.options["project"]["rooms"][0]["activities"]
    prime = acts[1]
    assert prime["id"] == "prime" and prime["source_input"] == "Prime Video"
    assert prime["devices"] == [{"entity": "media_player.projector", "source": "HDMI 2", "off": None}]
    assert prime["slots"]["red"]["action"] == "script.dim"
    assert acts[0]["logo"] == "/local/netflix.png"  # original untouched

    from custom_components.ar_remote.project import compile_project

    compiled = compile_project(hass, entry.options["project"])["rooms"][0]["activities"]
    assert compiled[0]["logo"] == "/local/netflix.png?v=1"
    assert compiled[1]["logo"].startswith("/local/prime.png?v=")


async def test_switch_in_lights_is_on_off(hass: HomeAssistant, client) -> None:
    from custom_components.ar_remote.project import compile_project

    hass.states.async_set("switch.porch_relay", "off", {"friendly_name": "Porch"})
    compiled = compile_project(hass, {"rooms": [{"id": "r", "name": "R", "lights": ["switch.porch_relay"], "activities": []}]})
    assert compiled["rooms"][0]["lights"][0]["dimmable"] is False

    hass.config.internal_url = "http://192.168.88.2:8123"
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "sw"},
        options={"project": {"rooms": [{"id": "r", "name": "R", "lights": ["switch.porch_relay"], "activities": []}]}},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    toggle = async_mock_service(hass, "homeassistant", "toggle")
    on = async_mock_service(hass, "homeassistant", "turn_on")
    rt = entry.runtime_data
    await rt.handle_message({"type": "light", "entity": "switch.porch_relay", "action": "toggle"})
    await rt.handle_message({"type": "light", "entity": "switch.porch_relay", "action": "set", "brightness": 60})
    await hass.async_block_till_done()
    assert toggle[0].data == {"entity_id": "switch.porch_relay"}
    assert on[0].data == {"entity_id": "switch.porch_relay"}


async def test_installer_pin_is_hashed(hass: HomeAssistant, client) -> None:
    from custom_components.ar_remote.config_flow import pin_hash
    from custom_components.ar_remote.project import compile_project

    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "w"},
        options={"project": {"rooms": []}},
    )
    entry.add_to_hass(hass)
    with patch("custom_components.ar_remote.async_setup_entry", return_value=True):
        flow = await hass.config_entries.options.async_init(entry.entry_id)
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "remote_settings"})
        bad = await hass.config_entries.options.async_configure(flow["flow_id"], {"installer_pin": "12"})
        assert bad["errors"] == {"installer_pin": "pin_length"}
        flow = await hass.config_entries.options.async_configure(bad["flow_id"], {"installer_pin": "2580"})
        flow = await hass.config_entries.options.async_configure(flow["flow_id"], {"next_step_id": "save"})
    dev = entry.options["project"]["device"]
    assert "2580" not in str(dev)
    # Same formula as the app (PinScreen.pinHash): sha256("ar_remote:" + pin)
    assert dev["pin_hash"] == pin_hash("2580")
    assert dev["pin_hash"].startswith("fe262987")
    assert compile_project(hass, entry.options["project"])["device"]["pin_hash"] == pin_hash("2580")


async def test_pulse_turn_off_on_start_and_room_off_timers(hass: HomeAssistant, client) -> None:
    """Per-device timers: pulse, off-on-start, Room Off everything + keep-running minutes."""
    from datetime import timedelta
    from homeassistant.util import dt as dt_util
    from pytest_homeassistant_custom_component.common import async_fire_time_changed

    project = {"rooms": [{
        "id": "lounge", "name": "Lounge", "lights": ["light.down"], "off_lights": True,
        "off_extra": ["fan.ceiling"],
        "off_delayed": [{"entity": "switch.amp_power", "minutes": 2}],
        "activities": [{
            "id": "tv", "name": "TV", "kind": "watch", "source": "media_player.atv", "slots": {},
            "devices": [{"entity": "switch.amp_power", "source": None}],
            "off_on_start": [{"entity": "light.kitchen", "after": 0}],
            "pulse": [{"entity": "light.flash", "seconds": 3}],
        }],
    }]}
    hass.config.internal_url = "http://192.168.88.2:8123"
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN, data={"host": HOST, "port": 8080, "adb_port": 5555, "webhook_id": "tmr"},
        options={"project": project},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()

    async_mock_service(hass, "media_player", "turn_on")
    async_mock_service(hass, "media_player", "media_pause")
    async_mock_service(hass, "media_player", "turn_off")
    async_mock_service(hass, "switch", "turn_on")
    light_on = async_mock_service(hass, "light", "turn_on")
    ha_off = async_mock_service(hass, "homeassistant", "turn_off")

    rt = entry.runtime_data
    await rt.start_activity("lounge", "tv")
    await hass.async_block_till_done()
    assert [c.data["entity_id"] for c in light_on] == ["light.flash"]
    assert [c.data["entity_id"] for c in ha_off] == ["light.kitchen"]

    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(seconds=4))
    await hass.async_block_till_done()
    assert [c.data["entity_id"] for c in ha_off] == ["light.kitchen", "light.flash"]

    ha_off.clear()
    await rt.room_off("lounge")
    await hass.async_block_till_done()
    off = {c.data["entity_id"] for c in ha_off}
    assert {"light.down", "fan.ceiling", "light.flash"} <= off
    assert "switch.amp_power" not in off  # keeps running for 2 minutes

    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(minutes=3))
    await hass.async_block_till_done()
    assert "switch.amp_power" in {c.data["entity_id"] for c in ha_off}
    assert rt.timers == {}
