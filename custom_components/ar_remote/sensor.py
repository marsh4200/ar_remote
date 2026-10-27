"""Battery and app version."""

from __future__ import annotations

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorStateClass
from homeassistant.const import PERCENTAGE, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import ArRemoteEntity


async def async_setup_entry(hass: HomeAssistant, entry, async_add_entities: AddEntitiesCallback) -> None:
    rt = entry.runtime_data
    async_add_entities([BatterySensor(rt), VersionSensor(rt), WakeSensor(rt)])


class BatterySensor(ArRemoteEntity, SensorEntity):
    _attr_translation_key = "battery"
    _attr_device_class = SensorDeviceClass.BATTERY
    _attr_state_class = SensorStateClass.MEASUREMENT
    _attr_native_unit_of_measurement = PERCENTAGE

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "battery")

    @property
    def native_value(self):
        return self.runtime.battery

    @property
    def extra_state_attributes(self):
        return {"charging": self.runtime.charging}


class VersionSensor(ArRemoteEntity, SensorEntity):
    _attr_translation_key = "app_version"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "app_version")

    @property
    def native_value(self):
        return self.runtime.info.get("version")

    @property
    def extra_state_attributes(self):
        return {
            "project_version": self.runtime.info.get("project_version"),
            "programmed_version": self.runtime.project.get("version"),
            "screen": self.runtime.screen or self.runtime.info.get("screen"),
            "home_app": self.runtime.info.get("default_home"),
            "other_launchers": self.runtime.info.get("other_launchers"),
        }


class WakeSensor(ArRemoteEntity, SensorEntity):
    """Which sensor wakes the screen on pick-up (diagnostics)."""

    _attr_translation_key = "wake_sensor"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime, "wake_sensor")

    @property
    def native_value(self):
        return self.runtime.info.get("wake_sensor")

    @property
    def extra_state_attributes(self):
        return {
            "mode": self.runtime.info.get("pickup"),
            "motion_sensors": self.runtime.info.get("sensors"),
            "deep_sleep_exempt": self.runtime.info.get("doze_exempt"),
        }
