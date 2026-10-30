# AR Remote — device protocol

The contract between the `ar_remote` Home Assistant integration (the "Composer"
and the brains) and the AR Remote app on the Astrion (the "Halo"). The app is a
thin renderer: it draws what the integration compiled, routes buttons, and
reports presses. All device logic — which entity gets the D-pad, what a colour
key does, what "Room Off" turns off — lives in the integration.

```
 HA (ar_remote)                                  Remote (AR Remote app)
 ─────────────                                  ─────────────────────
 POST http://<remote>:8080/link     ─────────▶   stores webhook URL
 POST http://<remote>:8080/project  ─────────▶   renders rooms/activities
 POST http://<remote>:8080/state    ─────────▶   live status on screen
 GET  http://<remote>:8080/info     ─────────▶   version, battery, linked?

 /api/webhook/<id>                  ◀─────────   hello, key, activity, light …
   (response carries project/state when the app is out of date)
```

## 1. Remote HTTP server (port 8080)

| Method | Path        | Body / reply |
| ------ | ----------- | ------------ |
| GET    | `/info`     | `{"app":"ar_remote","version":"1.0.0","versionCode":1,"project_version":"…","webhook_url":"…","battery":87,"charging":false,"screen":"home","room":"living","self_update":true,"can_install":true}` |
| POST   | `/link`     | `{"webhook_url":"http://ha:8123/api/webhook/<id>"}` |
| POST   | `/project`  | a project (section 3); replaces the stored one and re-renders |
| POST   | `/state`    | a state message (section 4) |
| POST   | `/update`   | `{"url":"https://…/ar-remote.apk"}` — the app downloads it and opens Android's install dialog |
| POST   | `/navigate` | `{"screen":"home"\|"activity"\|"lights"\|"comfort"\|"shades"\|"favourites"\|"rooms","room":"living"}` |

Every reply is `{"ok":true}` or `{"ok":false,"error":"…"}`.

## 2. Webhook messages (remote → HA)

`POST` JSON to the linked webhook. Every message carries `"remote"` (the
device's Android ID). The reply is JSON.

| `type`        | Fields | HA does |
| ------------- | ------ | ------- |
| `hello`       | `version`, `project_version` | replies `{"project":{…}\|null,"state":{full state}}` — project only when the app's version differs |
| `key`         | `key`, `code`, `press` (`short`\|`long`\|`repeat`), `route` (`device`\|`ui`), `room`, `activity`, `screen` | fires `ar_remote_button` + event entity; if `route` is `device`, runs the mapped command |
| `activity`    | `room`, `activity` | starts the activity (powers on source, amp and extra devices, inputs, power-on script, turns off what the previous activity used) |
| `room_off`    | `room` | everything in the room off (sources, amps, activity devices, lights, off list); keep-running devices switch off after their own timer; then the room off action |
| `slot`        | `room`, `activity`, `slot` (`custom1..3`, `red`, `green`, `blue`, `yellow`) | runs that slot's action |
| `run`         | `entity` | scene/script/button/automation |
| `light`       | `entity`, `action` (`toggle`\|`set`), `brightness` 0–100 | light service |
| `cover`       | `entity`, `action` (`open`\|`close`\|`stop`) | cover service |
| `climate`     | `entity`, `action` (`temp_up`\|`temp_down`\|`mode`), `value` | climate service |
| `media`       | `entity`, `action` (`play_pause`\|`next`\|`previous`\|`volume_up`\|`volume_down`\|`mute`) | media service |
| `battery`     | `level`, `charging` | battery sensor |
| `screen`      | `screen`, `room` | attribute only |
| `update`      | `status` | logged (self-update progress) |

## 3. Project (HA → remote)

```json
{
  "version": "3f2a9c…",
  "default_room": "living",
  "rooms": [{
    "id": "living",
    "name": "Living Room",
    "temperature": "sensor.lounge_temp",
    "activities": [{
      "id": "apple_tv", "name": "Apple TV", "kind": "watch",
      "source": "media_player.lounge_apple_tv", "volume": "media_player.denon",
      "custom": ["Menu", "Home", "Movie Mode"],
      "colors": {"red": "Lights 50%", "green": "", "blue": "Subtitles", "yellow": ""},
      "has_dpad": true
    }],
    "lights":     [{"entity": "light.downlights", "name": "Downlights", "dimmable": true}],
    "scenes":     [{"entity": "scene.evening", "name": "Evening"}],
    "climate":    {"entity": "climate.lounge_ac", "name": "Lounge AC"},
    "covers":     [{"entity": "cover.lounge", "name": "Lounge blinds"}],
    "favourites": [{"entity": "script.movie_night", "name": "Movie Night"}]
  }]
}
```

Labels only — the actions behind custom and colour slots stay in HA.

The project also carries the remote's own settings:

```json
"device": {
  "logo": "/local/logo.png?v=1730000000",
  "splash_seconds": 2,
  "charging_screen": true,
  "pickup": "auto",
  "pickup_sensitivity": "normal"
}
```

`logo` is resolved against the webhook URL's host and cached on the remote.
`pickup` is `off`, `auto` (low-power sensors only) or `always` (keeps the CPU awake while
the screen is off). `/info` reports `wake_sensor`, `pickup` and `logo`.

## 4. State (HA → remote)

```json
{
  "full": true,
  "rooms": {"living": {"activity": "apple_tv"}},
  "entities": {
    "light.downlights": {"s": "on", "b": 60},
    "media_player.denon": {"s": "on", "v": 38, "m": false},
    "media_player.lounge_apple_tv": {"s": "playing", "t": "Title", "a": "Artist"},
    "climate.lounge_ac": {"s": "cool", "c": 24.0, "t": 22.0},
    "cover.lounge": {"s": "open", "p": 100},
    "sensor.lounge_temp": {"s": "23.4"}
  }
}
```

`full: false` merges into what the app already holds.

## 5. Hardware keys (Astrion HA100)

| Key | Code | In an activity (`route: device`) |
| --- | --- | --- |
| `UP` `DOWN` `LEFT` `RIGHT` `CENTER` | 19 20 21 22 23 | D-pad command set on the activity's remote entity |
| `BACK` | 4 | back / menu command |
| `MAIN` | 82 | menu command |
| `HOME` | 131 | app: home screen (never sent as device) |
| `POWER` | 132 | app: Room Off |
| `VOICE` | 133 | event only |
| `REWIND` `PLAY` `STOP` `FASTFORWARD` | 134 135 136 137 | transport on the source |
| `RED_BUTTON` `GREEN_BUTTON` `BLUE_BUTTON` `YELLOW_BUTTON` | 138–141 | colour slot actions |
| `VOLUME_UP` `VOLUME_DOWN` | 24 25 | the room's amp — on every screen while an activity runs |
| `MUTE` | 164 (91 on some units) | the room's amp |
| `PAGE_UP` `PAGE_DOWN` | 92 93 | channel up/down where the command set has one |

Every press is reported, bound or not, so any key can be automated from HA.
