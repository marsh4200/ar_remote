#!/usr/bin/env python3
"""Built-in icon library for AR Remote.

One source for both sides:
  * android/app/src/main/res/drawable/lib_<name>.xml  — the remote's vector icons
  * custom_components/ar_remote/icon_library.py        — names + labels for the Composer
  * tools/icon_library.html                            — a contact sheet to look at them

Run it after adding or changing an icon:  python3 tools/make_icon_library.py
All icons: 24×24 grid, 1.8 stroke, round caps and joins, no fill — the same
style as the app's own icons, so they take the accent colour when active.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def circle(cx: float, cy: float, r: float) -> str:
    return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0"


def rect(x: float, y: float, w: float, h: float, r: float = 0) -> str:
    if not r:
        return f"M{x},{y}h{w}v{h}h{-w}z"
    return (
        f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0,1 {r},{r}v{h - 2 * r}a{r},{r} 0 0,1 {-r},{r}"
        f"h{-(w - 2 * r)}a{r},{r} 0 0,1 {-r},{-r}v{-(h - 2 * r)}a{r},{r} 0 0,1 {r},{-r}z"
    )


# name: (label, group, [paths])
ICONS: dict[str, tuple[str, str, list[str]]] = {
    # ------------------------------------------------------------ watch
    "tv": ("TV", "Watch", [rect(2, 5, 20, 13, 2), "M8,21h8"]),
    "tv_wall": ("TV on the wall", "Watch", [rect(3, 4, 18, 12, 1.5), "M12,16v3", "M7,20h10"]),
    "projector": ("Projector", "Watch", [rect(2, 8, 20, 9, 2), circle(16, 12.5, 2.5), "M5,11.5h4", "M5,14h4", "M5,17v2", "M19,17v2"]),
    "screen": ("Projector screen", "Watch", ["M2,4h20", rect(4, 4, 16, 11), "M12,15v3", "M9,21l3,-3l3,3"]),
    "movie": ("Movie", "Watch", [rect(3, 9, 18, 12, 1.5), "M3,9l17,-4.5l1,3.5", "M8,7.7l2.2,2.8", "M13,6.4l2.2,2.8"]),
    "film": ("Film", "Watch", [rect(3, 3, 18, 18, 2), "M7,3v18", "M17,3v18", "M3,8h4", "M3,16h4", "M17,8h4", "M17,16h4", "M3,12h18"]),
    "play": ("Play", "Watch", [circle(12, 12, 9.5), "M10,8.5v7l5.5,-3.5z"]),
    "video": ("Video app", "Watch", [rect(2, 5, 20, 14, 4), "M10,9v6l5,-3z"]),
    "streamer": ("Streaming box", "Watch", [rect(3, 9, 18, 7, 2), "M7,19h10", circle(17, 12.5, 0.6)]),
    "satellite": ("Satellite / DStv", "Watch", ["M5,19a9,9 0 0,1 0,-13l13,13a9,9 0 0,1 -13,0z", "M12,12l4,-4", circle(17, 7, 1.5), "M15,3a6,6 0 0,1 6,6", "M3,22l3,-3"]),
    "antenna": ("Antenna", "Watch", ["M12,10v12", "M8,22h8", "M6,3l6,7l6,-7", circle(12, 10, 1), "M4.5,8a8,8 0 0,0 2,3", "M19.5,8a8,8 0 0,1 -2,3"]),
    "disc": ("Blu-ray / DVD", "Watch", [circle(12, 12, 9.5), circle(12, 12, 2.5), "M12,5.5a6.5,6.5 0 0,1 6.5,6.5"]),
    "game": ("Games", "Watch", ["M7,7h10a5,5 0 0,1 4.8,6.4l-1,3.3a2.6,2.6 0 0,1 -4.4,1l-2,-2.2h-4.8l-2,2.2a2.6,2.6 0 0,1 -4.4,-1l-1,-3.3A5,5 0 0,1 7,7z", "M7,10v4", "M5,12h4", circle(16, 11, 0.6), circle(18, 13, 0.6)]),
    "news": ("News", "Watch", [rect(3, 4, 15, 16, 1.5), "M18,8h3v10a2,2 0 0,1 -2,2", "M6,8h9", "M6,12h4", "M6,16h4", rect(12, 11, 3, 5)]),
    "sport": ("Sport", "Watch", [circle(12, 12, 9.5), "M12,7.5l4.3,3.1l-1.6,5h-5.4l-1.6,-5z", "M12,2.5v5", "M16.3,10.6l4.7,-1.6", "M14.7,15.6l2.9,4", "M9.3,15.6l-2.9,4", "M7.7,10.6l-4.7,-1.6"]),
    "kids": ("Kids", "Watch", [circle(12, 13, 8), circle(9.2, 12, 0.6), circle(14.8, 12, 0.6), "M9.5,16a3.5,3.5 0 0,0 5,0", "M12,5c0,-2 1.5,-3 3,-2.5"]),
    "camera": ("Camera / CCTV", "Watch", ["M3,7l12,-3l2,6l-12,3z", "M17,8.5l3,-1v6l-3,-1", "M7,12.5l1,4.5h-4", "M4,14v6"]),
    "cast": ("Cast / screen share", "Watch", ["M2,8V6a2,2 0 0,1 2,-2h16a2,2 0 0,1 2,2v12a2,2 0 0,1 -2,2h-6", "M2,12a8,8 0 0,1 8,8", "M2,16a4,4 0 0,1 4,4", "M2,20h0.01"]),
    # ------------------------------------------------------------ listen
    "music": ("Music", "Listen", ["M9,18V5l12,-2v13", circle(6, 18, 3), circle(18, 16, 3)]),
    "speaker": ("Speaker", "Listen", [rect(5, 2, 14, 20, 2), circle(12, 14, 4), circle(12, 6.5, 1)]),
    "soundbar": ("Soundbar", "Listen", [rect(2, 9, 20, 6, 3), circle(7, 12, 0.6), circle(12, 12, 0.6), circle(17, 12, 0.6)]),
    "amp": ("Amplifier", "Listen", [rect(2, 7, 20, 10, 2), circle(7, 12, 2.5), circle(17, 12, 2.5), "M11,10h2", "M11,14h2"]),
    "headphones": ("Headphones", "Listen", ["M3,18v-6a9,9 0 0,1 18,0v6", rect(3, 14, 4, 7, 1.5), rect(17, 14, 4, 7, 1.5)]),
    "radio": ("Radio", "Listen", [rect(2, 8, 20, 13, 2), "M6,8l12,-5", circle(8, 14.5, 3), "M15,12h3", "M15,15h3", "M15,18h3"]),
    "vinyl": ("Records", "Listen", [circle(12, 12, 9.5), circle(12, 12, 3), "M12,5.5a6.5,6.5 0 0,0 -6.5,6.5", "M12,18.5a6.5,6.5 0 0,0 6.5,-6.5"]),
    "mic": ("Karaoke / mic", "Listen", [rect(9, 2, 6, 12, 3), "M5,10v1a7,7 0 0,0 14,0v-1", "M12,18v4", "M8,22h8"]),
    "podcast": ("Podcast", "Listen", [circle(12, 11, 2), "M7.8,15.5a6,6 0 1,1 8.4,0", "M4.9,18.5a10,10 0 1,1 14.2,0", "M12,13v8"]),
    "bluetooth": ("Bluetooth", "Listen", ["M7,7l10,10l-5,5V2l5,5L7,17"]),
    "equalizer": ("Sound / EQ", "Listen", ["M4,21v-7", "M4,10V3", "M12,21v-9", "M12,8V3", "M20,21v-5", "M20,12V3", "M1,14h6", "M9,8h6", "M17,16h6"]),
    # ------------------------------------------------------------ lights
    "bulb": ("Light bulb", "Lights", ["M9,18h6", "M10,22h4", "M12,2a7,7 0 0,0 -4,12.7V16h8v-1.3A7,7 0 0,0 12,2z"]),
    "lamp": ("Floor lamp", "Lights", ["M8,2h8l3,8H5z", "M12,10v11", "M8,22h8"]),
    "table_lamp": ("Table lamp", "Lights", ["M7,3h10l3,9H4z", "M12,12v6", "M7,21h10l-1,-3H8z"]),
    "downlight": ("Downlights", "Lights", ["M4,4h16", "M7,4a5,5 0 0,0 10,0", "M12,13v2", "M7.5,12l-1.5,1.5", "M16.5,12l1.5,1.5", "M9,19l3,3l3,-3"]),
    "pendant": ("Pendant", "Lights", ["M12,2v6", "M5,16a7,7 0 0,1 14,0z", "M10,19a2,2 0 0,0 4,0"]),
    "strip": ("LED strip", "Lights", [rect(2, 10, 20, 4, 2), circle(6, 12, 0.5), circle(10, 12, 0.5), circle(14, 12, 0.5), circle(18, 12, 0.5), "M6,6v1.5", "M12,6v1.5", "M18,6v1.5", "M6,16.5V18", "M12,16.5V18", "M18,16.5V18"]),
    "scene": ("Scene", "Lights", ["M12,3l1.8,4.7l4.7,1.8l-4.7,1.8L12,16l-1.8,-4.7L5.5,9.5l4.7,-1.8z", "M19,15l0.9,2.1l2.1,0.9l-2.1,0.9L19,21l-0.9,-2.1L16,18l2.1,-0.9z", "M5,16v4", "M3,18h4"]),
    "dimmer": ("Dimmer", "Lights", [circle(12, 12, 9.5), "M12,2.5v19", "M12,2.5a9.5,9.5 0 0,1 0,19"]),
    "switch": ("Switch", "Lights", [rect(6, 2, 12, 20, 2), rect(9, 6, 6, 6, 1), circle(12, 16, 0.6)]),
    # ------------------------------------------------------------ comfort
    "thermometer": ("Temperature", "Comfort", ["M14,14.8V4.5a2.5,2.5 0 0,0 -5,0v10.3a4.5,4.5 0 1,0 5,0z", "M11.5,9v8"]),
    "snowflake": ("Aircon / cool", "Comfort", ["M12,2v20", "M3.3,7l17.4,10", "M3.3,17l17.4,-10", "M9,3.5l3,2.5l3,-2.5", "M9,20.5l3,-2.5l3,2.5"]),
    "aircon": ("Wall aircon", "Comfort", [rect(2, 4, 20, 9, 2), "M5,10h14", "M7,16v3", "M12,16v5", "M17,16v3"]),
    "fan": ("Fan", "Comfort", [circle(12, 12, 2), "M12,10c0,-4 1,-7 4,-7c3,0 3,4 -2,7", "M14,12c4,0 7,1 7,4c0,3 -4,3 -7,-2", "M12,14c0,4 -1,7 -4,7c-3,0 -3,-4 2,-7", "M10,12c-4,0 -7,-1 -7,-4c0,-3 4,-3 7,2"]),
    "flame": ("Heating / fireplace", "Comfort", ["M12,22a7,7 0 0,0 7,-7c0,-4.5 -3.5,-7 -4,-12c-2.5,2 -4,4.5 -4,7c-1,-1 -1.5,-2 -1.5,-3.5C7.5,8.5 5,11.5 5,15a7,7 0 0,0 7,7z"]),
    "humidity": ("Humidity", "Comfort", ["M12,2.5l6,8.5a7,7 0 1,1 -12,0z"]),
    "sun": ("Sun", "Comfort", [circle(12, 12, 4), "M12,2v2", "M12,20v2", "M4.9,4.9l1.4,1.4", "M17.7,17.7l1.4,1.4", "M2,12h2", "M20,12h2", "M4.9,19.1l1.4,-1.4", "M17.7,6.3l1.4,-1.4"]),
    "moon": ("Night", "Comfort", ["M21,12.8A9,9 0 1,1 11.2,3a7,7 0 0,0 9.8,9.8z"]),
    # ------------------------------------------------------------ shades & access
    "blinds": ("Blinds", "Shades", ["M3,3h18", "M4,3v11h16V3", "M4,7h16", "M4,10.5h16", "M12,14v4", circle(12, 19.5, 1.5)]),
    "curtains": ("Curtains", "Shades", ["M2,3h20", "M4,3c0,6 1,12 4,18H4", "M20,3c0,6 -1,12 -4,18h4", "M4,3v18", "M20,3v18"]),
    "shutter": ("Roller shutter", "Shades", [rect(3, 3, 18, 18, 1.5), "M3,7h18", "M3,11h18", "M3,15h18"]),
    "window": ("Window", "Shades", [rect(4, 2, 16, 20, 1.5), "M12,2v20", "M4,12h16"]),
    "garage": ("Garage", "Shades", ["M3,21V9l9,-6l9,6v12", "M7,21v-9h10v9", "M7,15h10", "M7,18h10"]),
    "gate": ("Gate", "Shades", ["M3,21V5", "M21,21V5", "M3,9h18", "M3,17h18", "M7,9v8", "M11,9v8", "M15,9v8", "M19,9v8"]),
    "door": ("Door", "Shades", ["M5,21V4a1,1 0 0,1 1,-1h12a1,1 0 0,1 1,1v17", "M3,21h18", circle(15, 12, 0.6)]),
    "lock": ("Lock", "Shades", [rect(4, 11, 16, 10, 2), "M8,11V7a4,4 0 0,1 8,0v4", "M12,15v2"]),
    "shield": ("Security", "Shades", ["M12,22s8,-4 8,-10V5l-8,-3l-8,3v7c0,6 8,10 8,10z", "M9,12l2,2l4,-4"]),
    "doorbell": ("Doorbell", "Shades", [rect(7, 2, 10, 20, 3), circle(12, 8, 2), circle(12, 15.5, 2.5)]),
    # ------------------------------------------------------------ rooms & home
    "home": ("Home", "Home", ["M3,10.5l9,-7.5l9,7.5", "M5,9v12h14V9", "M10,21v-6h4v6"]),
    "sofa": ("Lounge", "Home", ["M4,10V7a2,2 0 0,1 2,-2h12a2,2 0 0,1 2,2v3", "M2,12a2,2 0 0,1 4,0v3h12v-3a2,2 0 0,1 4,0v5a2,2 0 0,1 -2,2H4a2,2 0 0,1 -2,-2z", "M5,19v2", "M19,19v2"]),
    "bed": ("Bedroom", "Home", ["M2,20V5", "M2,17h20v3", "M22,17v-5a3,3 0 0,0 -3,-3h-8v8", circle(6.5, 11.5, 2)]),
    "kitchen": ("Kitchen", "Home", ["M5,2v8a2,2 0 0,0 4,0V2", "M7,2v20", "M17,22V2c-2.5,1 -4,4 -4,8h4"]),
    "dining": ("Dining", "Home", [circle(12, 12, 6), "M3,3v6a1.5,1.5 0 0,0 3,0V3", "M4.5,9v12", "M20,3v18", "M20,3c-1.5,1 -2,3 -2,6h2"]),
    "bath": ("Bathroom", "Home", ["M4,12h17v3a5,5 0 0,1 -5,5H8a5,5 0 0,1 -5,-5v-3", "M6,12V5a2,2 0 0,1 4,0", "M7,20l-1,2", "M17,20l1,2"]),
    "office": ("Office", "Home", [rect(2, 4, 20, 13, 1.5), "M8,21h8", "M12,17v4"]),
    "pool": ("Pool", "Home", ["M2,17c2,1.3 4,1.3 6,0s4,-1.3 6,0s4,1.3 6,0", "M2,21c2,1.3 4,1.3 6,0s4,-1.3 6,0s4,1.3 6,0", "M8,14V5a2,2 0 0,1 4,0", "M16,14V5a2,2 0 0,1 4,0", "M8,9h8"]),
    "garden": ("Garden", "Home", ["M11,20A7,7 0 0,1 9.8,6.1C15.5,5 17,4.5 19,2c1,2 2,4.2 2,8c0,5.5 -4.8,10 -10,10z", "M2,21c0,-3 1.9,-5.4 5.1,-6.2C9.5,14.2 12,13 13,11"]),
    "sprinkler": ("Irrigation", "Home", ["M12,2.5l4.5,6.5a5.5,5.5 0 1,1 -9,0z", "M4,20h16", "M12,17v3"]),
    "car": ("Car", "Home", ["M5,17h14v-5l-2,-5H7l-2,5z", "M3,12h18", circle(7.5, 17, 2), circle(16.5, 17, 2)]),
    "wine": ("Bar", "Home", ["M8,2h8l-1,8a3,3 0 0,1 -6,0z", "M12,13v8", "M8,21h8"]),
    "coffee": ("Coffee", "Home", ["M4,8h13v7a5,5 0 0,1 -5,5H9a5,5 0 0,1 -5,-5z", "M17,10h1.5a2.5,2.5 0 0,1 0,5H17", "M8,2v3", "M12,2v3"]),
    "gym": ("Gym", "Home", ["M6.5,6.5v11", "M17.5,6.5v11", "M3,9v6", "M21,9v6", "M6.5,12h11"]),
    # ------------------------------------------------------------ everyday
    "power": ("Power", "General", ["M12,2v10", "M18.4,6.6a9,9 0 1,1 -12.8,0"]),
    "plug": ("Plug", "General", ["M9,2v5", "M15,2v5", "M6,7h12v4a6,6 0 0,1 -12,0z", "M12,17v5"]),
    "bolt": ("Energy", "General", ["M13,2L4,14h7l-1,8l9,-12h-7z"]),
    "battery": ("Battery", "General", [rect(2, 7, 18, 10, 2), "M22,11v2", "M6,10v4", "M10,10v4"]),
    "star": ("Favourite", "General", ["M12,3l2.7,5.6l6.1,0.9l-4.4,4.3l1,6.1l-5.4,-2.9l-5.4,2.9l1,-6.1l-4.4,-4.3l6.1,-0.9z"]),
    "heart": ("Heart", "General", ["M12,20s-8,-4.8 -8,-10.5A4.5,4.5 0 0,1 12,6.6A4.5,4.5 0 0,1 20,9.5C20,15.2 12,20 12,20z"]),
    "bell": ("Alerts", "General", ["M6,9a6,6 0 0,1 12,0c0,6 3,8 3,8H3s3,-2 3,-8", "M10.3,21a1.9,1.9 0 0,0 3.4,0"]),
    "clock": ("Timer", "General", [circle(12, 12, 9.5), "M12,7v5l3,2"]),
    "calendar": ("Schedule", "General", [rect(3, 4, 18, 18, 2), "M3,10h18", "M8,2v4", "M16,2v4"]),
    "wifi": ("Wi-Fi", "General", ["M5,12.5a10,10 0 0,1 14,0", "M8.5,16a5,5 0 0,1 7,0", "M2,9a15,15 0 0,1 20,0", "M12,20h0.01"]),
    "settings": ("Settings", "General", [rect(2, 4, 20, 7, 3.5), circle(17.5, 7.5, 1.8), rect(2, 13, 20, 7, 3.5), circle(6.5, 16.5, 1.8)]),
    "remote": ("Remote", "General", [rect(7, 2, 10, 20, 3), circle(12, 7.5, 2), "M10,13h0.01", "M14,13h0.01", "M10,16.5h0.01", "M14,16.5h0.01"]),
    "phone": ("Phone", "General", [rect(6, 2, 12, 20, 2.5), "M11,18h2"]),
    "grid": ("All / more", "General", [rect(3, 3, 7, 7, 1.5), rect(14, 3, 7, 7, 1.5), rect(3, 14, 7, 7, 1.5), rect(14, 14, 7, 7, 1.5)]),
}

VECTOR = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- Generated by tools/make_icon_library.py — edit there, not here. -->\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="24dp" android:height="24dp"\n'
    '    android:viewportWidth="24" android:viewportHeight="24">\n{paths}</vector>\n'
)
PATH = (
    '    <path android:pathData="{d}" android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" '
    'android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
)


# Realistic icons (tools/realistic/icons.svg.html → PNGs by tools/realistic/render.js):
# their group in the Composer's list. Labels come from each icon's caption.
REAL_GROUPS = {
    "Watch": "tv screen projector bluray movie popcorn streamer media_box satellite game cctv sport",
    "Listen": "music speaker ceiling_speaker soundbar subwoofer amp headphones radio turntable mic smart_speaker",
    "Lights": "bulb downlight pendant floor_lamp led_strip scene",
    "Comfort": "aircon thermostat fan fireplace heater",
    "Shades": "blinds curtains shutter garage gate door lock alarm doorbell",
    "Home": "home sofa bed kitchen pool garden bar gym coffee",
    "General": "star power plug energy",
}


def realistic() -> dict[str, dict[str, str]]:
    """name -> {label, group} for every rendered realistic icon, in the Composer's order."""
    html = (ROOT / "tools/realistic/icons.svg.html").read_text()
    captions = dict(re.findall(r'<figure data-name="([a-z0-9_]+)">.*?<figcaption>(.*?)</figcaption>', html, re.S))
    rendered = {p.stem[5:] for p in (ROOT / "android/app/src/main/res/drawable-nodpi").glob("real_*.png")}
    out: dict[str, dict[str, str]] = {}
    for group, names in REAL_GROUPS.items():
        for name in names.split():
            if name in captions and name in rendered:
                out[f"real_{name}"] = {"label": captions[name], "group": f"Realistic · {group}"}
    missing = (set(captions) & rendered) - {k[5:] for k in out}
    if missing:
        raise SystemExit(f"Add these realistic icons to REAL_GROUPS: {sorted(missing)}")
    return out


def main() -> None:
    res = ROOT / "android/app/src/main/res/drawable"
    for old in res.glob("lib_*.xml"):
        old.unlink()
    for name, (_, _, paths) in ICONS.items():
        (res / f"lib_{name}.xml").write_text(VECTOR.format(paths="".join(PATH.format(d=d) for d in paths)))

    # Realistic (full colour, PNG) first, then the line icons (tinted like the app's own).
    lib = realistic()
    lib.update({name: {"label": label, "group": f"Line · {group}"} for name, (label, group, _) in ICONS.items()})
    (ROOT / "custom_components/ar_remote/icon_library.py").write_text(
        '"""Built-in icons the remote has (generated by tools/make_icon_library.py)."""\n\n'
        "# name -> {label, group}. Stored as \"icon:<name>\". real_<name>: drawable-nodpi/real_<name>.png,\n"
        "# shown in its own colours; any other name: drawable/lib_<name>, a line icon tinted like the app's.\n"
        f"ICON_LIBRARY: dict[str, dict[str, str]] = {json.dumps(lib, indent=4, ensure_ascii=False)}\n"
    )

    cells = "".join(
        f'<figure><svg viewBox="0 0 24 24" aria-hidden="true">'
        + "".join(f'<path d="{d}"/>' for d in paths)
        + f"</svg><figcaption>{label}<small>{name}</small></figcaption></figure>"
        for name, (label, _, paths) in ICONS.items()
    )
    (ROOT / "tools/icon_library.html").write_text(
        "<!doctype html><meta charset=utf-8><title>AR Remote icons</title><style>"
        "body{margin:0;padding:24px;background:#0E1116;color:#E6E9EE;font:13px Inter,Roboto,sans-serif}"
        "main{display:grid;grid-template-columns:repeat(auto-fill,minmax(112px,1fr));gap:18px 8px}"
        "figure{margin:0;display:flex;flex-direction:column;align-items:center;gap:8px}"
        "svg{width:56px;height:56px;fill:none;stroke:#E6E9EE;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}"
        "figcaption{text-align:center;line-height:1.3}small{display:block;color:#7D8693;font-size:11px}"
        f"</style><main>{cells}</main>"
    )
    print(f"{len(ICONS)} line icons, {len(lib) - len(ICONS)} realistic icons listed")


if __name__ == "__main__":
    main()
