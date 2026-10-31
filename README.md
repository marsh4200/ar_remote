# AR Remote

Turns the Astrion HA100 touch remote into a Control4 Halo Touch–style handheld,
programmed entirely from Home Assistant.

- **The app** (`android/`) — a launcher APK for the remote. Pick a room, pick an
  activity, and the hard buttons instantly retarget: D-pad and OK go to the Apple
  TV, volume goes to the amp, colour keys run whatever you assigned. Lights,
  comfort, shades and favourites are one tap away.
- **The integration** (`custom_components/ar_remote`) — the "Composer". Build
  rooms, activities and button assignments in the HA UI, press **Program remote**,
  and it pushes everything to the remote. It also pushes app updates to it.

## Install

1. **HACS** → Integrations → ⋮ → Custom repositories → `marsh4200/ar_remote`
   (category *Integration*). Install **AR Remote**, restart Home Assistant.
2. **First install of the app — over USB, once.** On the remote turn on USB
   debugging, plug it into a PC and run `tools/install-usb.bat` (Windows) or
   `tools/install-usb.sh` (Mac/Linux). Accept *Allow USB debugging* on the
   remote. The script fetches adb if you don't have it, installs the latest
   release, makes AR Remote the home screen, grants it permission to install its
   own updates, and prints the remote's IP.
3. **Settings → Devices & services → Add integration → AR Remote**, enter that IP.
   The remote links within a few seconds.
4. **Configure** the integration to open the Composer.

### Updates — no ADB, no cable

Press **Install / update app** in HA. The app downloads the new release itself
and Android shows its install dialog on the remote — tap **Install**. (If the
remote ever does have network ADB on, the same button installs over ADB
instead when the app isn't running.)

## Composer

| Step | What you set |
| --- | --- |
| **Add a room** | lights and switches (relays), lighting scenes, climate, shades, favourites, temperature sensor · **Room Off**: lights off (on by default), climate off, *also turn off* list, *keep running* list with its own minutes per device, optional Room Off action |
| **Add an activity** | Watch or Listen · logo · source media player (+ input or app) · volume device (+ input) · navigation remote + command set · **Also turn on**: any number of extra devices (projector, amp, screen, power switches) · optional start action |
| **Inputs** | dropdowns of the inputs the source, the amp and each extra media player actually report — leave one empty and that device just switches on |
| **What else it does** | **Turn off when it starts** (each device: now or after N s) · **Turn on for a few seconds, then off** (each device its own time) · HA action editor for start and end: other devices + sources, lights (dim/colour), switches, delays between steps |
| **Buttons** | 3 on-screen custom buttons and the red/green/blue/yellow keys — label + script/scene/button |
| **Copy an activity** | duplicates an activity (devices, inputs, steps, buttons) — set up Netflix once, copy it to Prime and change the name, logo and app |
| **Remote settings** | your logo (start-up splash and charging screen), charging screen on/off, wake on pick-up (off / auto / always) and its sensitivity |
| **Save and program** | pushes the project to the remote |

Starting an activity, in order:

1. turns off anything the previous activity used that this one doesn't;
2. turns off everything on its **Turn off when the activity starts** list (each straight away or after its own delay);
3. switches on every **Turn on for a few seconds** device — each one switches itself off after its own time;
4. powers on its source, amp and every **Also turn on** device, runs the start action and start steps;
5. waits **7 s** (adjustable per activity on the Inputs page) for everything to wake up, then switches inputs and sets
   the volume — skipping any device already on the right input. If everything was already on, there's no wait.
   Room Off or another activity during the wait cancels it.

**Room Off** (or the remote's power key) is *everything off*: it pauses the music, turns off every source
and amp in the room, every **Also turn on** device, everything the activity's start steps turned on, the
room's lights and switches (untick *Room Off turns off the lights* to leave them), the climate if ticked and
everything on the room's **Also turns off** list, runs the activity's end steps, then your optional room off action.
Nothing is turned back on afterwards.

Devices on the room's **Keep running after Room Off** list are the exception: each stays on for its own
number of minutes and then switches itself off (an amp running down, a passage light to walk out by).
Starting an activity that uses one of them cancels its timer. All timers are saved, so a Home Assistant
restart doesn't leave anything running — an overdue timer fires as soon as HA is back.

### Command sets

The navigation remote gets `remote.send_command` with the names the platform
expects: `apple_tv`, `android_tv`, `roku`, `samsung_tv`, `lg_webos`,
`ir_generic` (Broadlink / IR learned codes named `up`, `down`, `ok`, `back` …).
Transport keys fall back to the source media player when the set has no command.

## Changes in 2.6.0

- Every Also-turn-on device can have a **turn off with** command (activity step 2): an IR "projector off"
  button, a script or a plug. Room Off and timers use it instead of the on-button.
- **Show what's playing from** (activity step 1): take the title and picture from another entity, e.g. the TV's
  Google Cast entity, when the source (Android TV Remote) doesn't report them.
- A colour / custom button press shows what happened on the remote's screen ("Yellow ✓" or the reason it didn't run).
- Home on a Watch / Listen screen: short press = the device's home screen (Android TV, Apple TV, Roku …),
  long press = back to the room page.
- Settings panel: **Touch sounds** now switches Android's own tap / key sounds (it only added an extra
  click before). **Vibrate** only shows on remotes that have a vibration motor. **Night dim** shows its
  hours (22:00–06:00).
- Colour / custom buttons can toggle a light or switch directly; the room's activity entity shows
  **last_button** (what was pressed and what happened). Composer pages are numbered and explain themselves.
- **Also turn on** takes buttons, scripts and scenes too (an IR "projector on" button is pressed).
  A button on a Room Off list is pressed (a "projector off" button, now or after its minutes).
- Inputs page: shows the input the device is on now and inputs typed before; type any input that
  isn't listed (e.g. `Game`); empty = leave the device as it is; new **set the volume to %** (empty = don't change).
- ⏪ ⏵ ⏹ ⏩ keys: open Lights / Shades / Music / Comfort by default (the icons on the Astrion), or play
  controls, or run any action — per key under **Remote settings**.
- Inputs switch after a 7 s wake-up wait (adjustable; skipped when everything was already on), and a
  device already on the right input is left alone.
- Room Off powers a receiver off through whichever of its entities can (e.g. the Denon AVR entity when
  the activity uses the HEOS one). The room's activity entity shows **last_room_off**: what was switched
  off, pressed, kept running, and anything Home Assistant can't power off.
- Per-device timers in activities: *turn off when the activity starts* (now or after N s) and
  *turn on for a few seconds, then off*.
- Room Off turns the whole room off (lights included, climate optional, extra off list), with an
  optional *keep running for N minutes* timer per device. Timers survive restarts.
- Stability: one change at a time per room (a fast double-press can't interleave), a failed setup no
  longer blocks the webhook until a restart, odd state values and damaged settings can't break
  setup or state pushes, and the app no longer crashes when a request to it is cut off or a
  damaged project arrives.

## Button routing

| Key | Home / lights / other screens | Activity screen |
| --- | --- | --- |
| D-pad, OK | moves the on-screen focus | navigation remote |
| BACK | previous screen | back / menu command |
| HOME | room home screen (long press: settings panel) | **short**: the device's home screen (e.g. Android TV home, out of Netflix) · **long**: back to the room page |
| POWER | Room Off | Room Off |
| Volume, mute | the room's amp while an activity runs | the room's amp |
| ⏪ ⏵ ⏹ ⏩ (light / curtain / music / aircon icons) | open Lights / Shades / Music / Comfort (default) — or play controls, or any action, per key under **Remote settings** | same |
| Colour keys | the activity's colour actions | the activity's colour actions |
| Left / Right on a light | dims ±10% | — |

Every press is also reported to HA — as the `ar_remote_button` event and the
**Buttons** event entity (`<key>_short` / `<key>_long`) — so any key can drive an
automation, including ones nothing is mapped to.

## Entities

- `select.<remote>_<room>_activity` — per room, `Off` or an activity (start/stop from HA too)
- `event.<remote>_buttons`
- `sensor.<remote>_battery`, `sensor.<remote>_app_version`, `binary_sensor.<remote>_online`
- `button.<remote>_program_remote`, `button.<remote>_install_update_app`

Services: `ar_remote.program`, `ar_remote.install_app`, `ar_remote.start_activity`, `ar_remote.room_off`.

## Building the APK

GitHub Actions builds `ar-remote.apk` on every push (artifact) and attaches it to
the release when you push a `v*` tag — that release asset is what
**Install / update app** downloads.

Signing: the bundled `android/app/ar-remote.jks` (password `arremote`) keeps
every build update-compatible. To use your own key, add the secrets
`AR_KEYSTORE_B64`, `AR_KEYSTORE_PASSWORD`, `AR_KEY_ALIAS`, `AR_KEY_PASSWORD` —
then uninstall the old build once, since Android won't update across keys.

Local build: `cd android && gradle assembleRelease` (Android SDK required).

## Protocol

[`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the remote's HTTP API, webhook messages,
project and state format, and the HA100 keycode table.

### The remote itself

- **Your logo.** Copy it to `/config/www/logo.png` and enter `/local/logo.png` under
  **Remote settings** (leave it empty and the built-in A&R Smart Home logo is used). The remote keeps a copy and shows it for a moment every time the
  app starts, including straight after a reboot. (Android's own boot animation that plays
  before the app can't be replaced without rooting the remote.)
- **Logo clock.** On the charger, after 30 s without a key press (adjustable), the
  screen shows your logo as a clock face with live hands and the date, dimmed and
  drifting slightly so nothing burns in. Any key or tap goes back. Set it to
  *On the charger* (default), *Always* or *Off* under **Remote settings**.
- **Charging screen.** Put the remote on charge and the screen wakes to an animated ring
  that fills to the battery level. Any key or a tap clears it.
- **Wake on pick-up.** With the screen off, lifting the remote (or taking it off the
  charger) turns the screen on. *Auto* works on any remote (it keeps the motion sensor
  running while the screen is off); *Battery saver* only uses low-power sensors. The
  **Pick-up sensor** entity shows what is in use and which motion sensors the remote has.
- **Watch / Listen picker.** One activity of a kind starts straight away. Two or more
  (Netflix, Prime, DStv …) show a "What do you want to watch?" screen of logo tiles.
  Give each activity a logo: **Upload a logo** in the activity's form picks an image
  from your computer (saved to `/config/ar_remote_media`), or type an address like
  `/local/netflix.png`.
- **Rooms is the main page.** The app opens on it, and Home and Room Off go back to it
  (a single-room house opens straight into the room).
- **Lights.** Dimmable lights have a slider you can drag with a finger (Left / Right
  on the D-pad still dims in steps). Switches and relays are on / off.

### If RosCard comes back

If the remote shows a clock lock screen again, needs a swipe to unlock, or shows
RosCard for a moment after updates, the stock launcher has been switched back on
(Home Assistant also shows a notification). Plug the remote in over USB and run
`tools/fix-remote.bat` (Windows) or `tools/fix-remote.sh`. It switches RosCard off,
makes AR Remote the home screen, turns off the lock screen and clock screensaver,
and writes `tools/fix-report.txt`. `restore-apps` undoes it.

### Settings on the remote

Swipe down from the top edge of the screen (or long-press Home) for the settings
panel: brightness, quick toggles (logo clock, wake on lift, charging screen, night
dim, key sounds, vibrate), screen-off time, **Wi-Fi** (search, pick a network, type
the password, connect), Home Assistant status and the app version. Swipe up from
the bottom, press Back or the ⌃ button to close it.

**Installer settings** (Android settings, restart, check for update, pairing
address) are behind the PIN you set in Home Assistant → AR Remote → Configure →
**Remote settings**. Only a hash of the PIN is stored.

First start at a new house: the setup screen has a **Connect to Wi-Fi** button, so
you can join the client's Wi-Fi straight from the remote. On Android 9 and older the
app joins the network itself; on Android 10+ Android asks you to confirm it.
Run `tools/fix-remote` once on existing remotes so the Wi-Fi list and screen-off
time work without extra prompts.

