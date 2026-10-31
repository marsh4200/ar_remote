"""Constants for AR Remote."""

from __future__ import annotations

DOMAIN = "ar_remote"
NAME = "AR Remote"
VERSION = "2.7.0"

CONF_HOST = "host"
CONF_PORT = "port"
CONF_ADB_PORT = "adb_port"
CONF_WEBHOOK_ID = "webhook_id"
CONF_PROJECT = "project"

# Logos uploaded in the Composer: saved to <config>/ar_remote_media and served
# (like /local) at this URL, so the remote can download them.
MEDIA_DIR = "ar_remote_media"
MEDIA_URL = "/ar_remote_media"

DEFAULT_PORT = 8080
DEFAULT_ADB_PORT = 5555

EVENT_BUTTON = "ar_remote_button"
SIGNAL_UPDATE = "ar_remote_update_{}"

APP_PACKAGE = "com.arsmarthome.remote"
APP_ACTIVITY = f"{APP_PACKAGE}/.MainActivity"
APK_URL = "https://github.com/marsh4200/ar_remote/releases/latest/download/ar-remote.apk"

KINDS = ["watch", "listen"]
SLOTS_CUSTOM = ["custom1", "custom2", "custom3"]
SLOTS_COLOR = ["red", "green", "blue", "yellow"]

# Astrion HA100 keys (see docs/PROTOCOL.md section 5).
KEYS = [
    "UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK", "MAIN", "HOME", "POWER",
    "VOICE", "REWIND", "PLAY", "STOP", "FASTFORWARD", "RED_BUTTON",
    "GREEN_BUTTON", "BLUE_BUTTON", "YELLOW_BUTTON", "VOLUME_UP", "VOLUME_DOWN",
    "MUTE", "PAGE_UP", "PAGE_DOWN",
]

COLOR_KEYS = {
    "RED_BUTTON": "red",
    "GREEN_BUTTON": "green",
    "BLUE_BUTTON": "blue",
    "YELLOW_BUTTON": "yellow",
}

# Command sets: how a hardware key becomes remote.send_command on the
# activity's "D-pad" remote entity. Keys missing from a set fall back to the
# source media_player (transport) or do nothing.
COMMAND_SETS: dict[str, dict[str, str]] = {
    "apple_tv": {
        "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
        "CENTER": "select", "BACK": "menu", "MAIN": "home", "HOME": "home",
        "REWIND": "skip_backward", "FASTFORWARD": "skip_forward",
        "PLAY": "play_pause",
    },
    "android_tv": {
        "UP": "DPAD_UP", "DOWN": "DPAD_DOWN", "LEFT": "DPAD_LEFT",
        "RIGHT": "DPAD_RIGHT", "CENTER": "DPAD_CENTER", "BACK": "BACK",
        "MAIN": "MENU", "HOME": "HOME", "PAGE_UP": "CHANNEL_UP", "PAGE_DOWN": "CHANNEL_DOWN",
        "REWIND": "MEDIA_REWIND", "FASTFORWARD": "MEDIA_FAST_FORWARD",
        "PLAY": "MEDIA_PLAY_PAUSE", "STOP": "MEDIA_STOP",
    },
    "roku": {
        "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
        "CENTER": "select", "BACK": "back", "MAIN": "home", "HOME": "home",
        "REWIND": "reverse", "FASTFORWARD": "forward", "PLAY": "play",
    },
    "samsung_tv": {
        "UP": "KEY_UP", "DOWN": "KEY_DOWN", "LEFT": "KEY_LEFT",
        "RIGHT": "KEY_RIGHT", "CENTER": "KEY_ENTER", "BACK": "KEY_RETURN",
        "MAIN": "KEY_MENU", "HOME": "KEY_HOME", "PAGE_UP": "KEY_CHUP", "PAGE_DOWN": "KEY_CHDOWN",
    },
    "lg_webos": {
        "UP": "UP", "DOWN": "DOWN", "LEFT": "LEFT", "RIGHT": "RIGHT",
        "CENTER": "ENTER", "BACK": "BACK", "MAIN": "MENU", "HOME": "HOME",
        "PAGE_UP": "CHANNELUP", "PAGE_DOWN": "CHANNELDOWN",
    },
    "ir_generic": {
        "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
        "CENTER": "ok", "BACK": "back", "MAIN": "menu", "HOME": "home",
        "PAGE_UP": "channel_up", "PAGE_DOWN": "channel_down",
        "REWIND": "rewind", "FASTFORWARD": "fast_forward",
        "PLAY": "play", "STOP": "stop",
    },
}

TRANSPORT_SERVICES = {
    "PLAY": "media_play_pause",
    "STOP": "media_stop",
    "REWIND": "media_previous_track",
    "FASTFORWARD": "media_next_track",
}

ACTION_DOMAINS = ["script", "scene", "button", "input_button", "automation"]

# What an activity's "Also turn on" list can hold: projectors, amps, screens,
# IR blasters, power switches. Media players can also be sent to an input.
DEVICE_DOMAINS = [
    "media_player", "remote", "switch", "light", "fan", "input_boolean", "siren",
]

# Things that are pressed / run rather than switched: an IR "projector on"
# button, a script, a scene. They can be in "Also turn on" and in the off lists
# (a "projector off" button), but never get switched off themselves.
PRESS_DOMAINS = ["button", "input_button", "script", "scene", "automation"]

# The ⏪ ⏵ ⏹ ⏩ keys. On the Astrion they carry light / curtain / music / aircon
# icons, so by default they open those screens.
MEDIA_KEYS = {
    "REWIND": "lights",
    "PLAY": "shades",
    "STOP": "music",
    "FASTFORWARD": "comfort",
}
KEY_MODES = ["lights", "shades", "music", "comfort", "transport", "action"]
