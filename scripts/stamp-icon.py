#!/usr/bin/env python3
"""Burn the build version across the launcher icons, so the home screen answers "did the
update land?" without opening anything.

    python3 scripts/stamp-icon.py                  # stamp with the version in build.gradle.kts
    python3 scripts/stamp-icon.py --version 2.0.0
    python3 scripts/stamp-icon.py --restore

A sideloaded build arrives with no notification and no prompt, so one debug APK looks exactly
like the next. The stamp answers it from the launcher, before the app is even open.

WHAT ACTUALLY GETS DRAWN. mipmap-anydpi-v26/ic_launcher.xml composes the icon from a vector
background and a vector foreground, so on Android 8+ every launcher draws the VECTOR and never
these PNGs. Stamping only the legacy rasters -- the obvious thing to do -- is invisible on every
modern phone. So both are stamped: the PNGs for API 26's fallback path, and a generated
<path> overlay appended to the foreground vector for everything after.

Originals are copied to scripts/.icon-backup/ (gitignored) and restored afterwards, because
res/** is tracked and a stamped icon must never be committable.

Deliberately stdlib-only: the release workflow already relies on python3, and an icon script is
not worth a Pillow install on every contributor's machine.
"""

import argparse
import re
import shutil
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
BACKUP = ROOT / "scripts/.icon-backup"
FOREGROUND = RES / "drawable/ic_launcher_foreground.xml"
GRADLE = ROOT / "app/build.gradle.kts"

# 5x7 bitmap font. A real text renderer would be a new dependency for one line of digits.
GLYPHS = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}
GLYPH_W, GLYPH_H = 5, 7

BAND_RGB = (12, 11, 16)
BAND_ALPHA = 0.82
TEXT_RGB = (255, 255, 255)
# Where the stamp sits, as a fraction of icon height. The mark is composed to leave this clear.
BAND_Y = 0.62
# The launcher masks the adaptive foreground to roughly a circle of this radius.
MASK_RADIUS = 0.305

MARKER = "<!-- version-stamp -->"


# --- minimal PNG read/write (RGBA8, non-interlaced, which is what these icons are) ----------

def png_read(path):
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG")
    pos, idat, meta = 8, bytearray(), None
    while pos < len(raw):
        (length,) = struct.unpack(">I", raw[pos:pos + 4])
        kind = raw[pos + 4:pos + 8]
        body = raw[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            meta = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    w, h, depth, color, comp, filt, interlace = meta
    if (depth, color, interlace) != (8, 6, 0):
        raise SystemExit(f"{path}: expected 8-bit RGBA non-interlaced")

    data = zlib.decompress(bytes(idat))
    stride = w * 4
    out = bytearray(h * stride)
    prev = bytearray(stride)
    at = 0
    for y in range(h):
        ftype = data[at]; at += 1
        line = bytearray(data[at:at + stride]); at += stride
        if ftype == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif ftype != 0:
            raise SystemExit(f"{path}: unknown filter {ftype}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def png_write(path, w, h, pixels):
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0; these icons are tiny, so this costs nothing worth saving
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(kind, body):
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


# --- stamping -------------------------------------------------------------------------------

def icon_files():
    if not RES.is_dir():
        return []
    return sorted(
        p.relative_to(RES)
        for d in RES.iterdir()
        if d.is_dir() and d.name.startswith("mipmap-") and d.name != "mipmap-anydpi-v26"
        for p in d.glob("*.png")
    )


def blend(pixels, w, h, x, y, rgb, alpha):
    if x < 0 or y < 0 or x >= w or y >= h:
        return
    i = (w * y + x) << 2
    for c in range(3):
        pixels[i + c] = round(pixels[i + c] * (1 - alpha) + rgb[c] * alpha)
    pixels[i + 3] = max(pixels[i + 3], round(255 * alpha))


def layout(w, h, text):
    """Where the band and glyphs go, in pixels. Shared by the raster and vector stampers."""
    band_y = round(h * BAND_Y)
    # A band of constant width gets its ends bitten off down here, where the launcher's circle
    # has already narrowed -- so take the width from the circle AT THIS HEIGHT, not from the icon.
    dy = abs(band_y + GLYPH_H * 2 / 2 - h / 2)
    radius = w * MASK_RADIUS
    half = int(max(radius * radius - dy * dy, 0) ** 0.5)
    safe = max(round(w * 0.3), half * 2)
    safe_left = round((w - safe) / 2)

    glyphs = [GLYPHS.get(c, GLYPHS[" "]) for c in text]
    advance = GLYPH_W + 1
    scale = max(1, safe // (len(glyphs) * advance))
    text_w = len(glyphs) * advance * scale - scale
    return glyphs, scale, advance, safe, safe_left, safe_left + round((safe - text_w) / 2), band_y


def stamp_png(path, text):
    w, h, pixels = png_read(path)
    glyphs, scale, advance, safe, safe_left, x0, y0 = layout(w, h, text)
    text_h = GLYPH_H * scale

    pad = max(2, scale * 2)
    for y in range(y0 - pad, y0 + text_h + pad):
        for x in range(safe_left, safe_left + safe):
            blend(pixels, w, h, x, y, BAND_RGB, BAND_ALPHA)

    for gi, glyph in enumerate(glyphs):
        for row in range(GLYPH_H):
            for col in range(GLYPH_W):
                if glyph[row][col] != "1":
                    continue
                px = x0 + gi * advance * scale + col * scale
                py = y0 + row * scale
                for dy in range(scale):
                    for dx in range(scale):
                        blend(pixels, w, h, px + dx, py + dy, TEXT_RGB, 1.0)
    png_write(path, w, h, pixels)


def stamp_vector(text):
    """Append the same stamp to the adaptive foreground, which is what modern launchers draw."""
    source = FOREGROUND.read_text()
    # The vector's viewport is 108x108; lay the stamp out in those units.
    glyphs, scale, advance, safe, safe_left, x0, y0 = layout(108, 108, text)
    text_h = GLYPH_H * scale
    pad = max(1, scale)

    parts = [
        f'  {MARKER}\n',
        f'  <path android:fillColor="#D10C0B10" android:pathData="'
        f'M{safe_left},{y0 - pad} h{safe} v{text_h + pad * 2} h{-safe} Z" />\n',
    ]
    for gi, glyph in enumerate(glyphs):
        for row in range(GLYPH_H):
            run = 0
            for col in range(GLYPH_W + 1):
                on = col < GLYPH_W and glyph[row][col] == "1"
                if on:
                    run += 1
                    continue
                if run:
                    px = x0 + gi * advance * scale + (col - run) * scale
                    py = y0 + row * scale
                    parts.append(
                        f'  <path android:fillColor="#FFFFFFFF" android:pathData="'
                        f'M{px},{py} h{run * scale} v{scale} h{-run * scale} Z" />\n'
                    )
                    run = 0
    FOREGROUND.write_text(source.replace("</vector>", "".join(parts) + "</vector>"))


def backup_paths():
    return [(RES / rel, BACKUP / rel) for rel in icon_files()] + [
        (FOREGROUND, BACKUP / "drawable/ic_launcher_foreground.xml")
    ]


def restore():
    if not BACKUP.is_dir():
        print("[icon] nothing to restore")
        return
    n = 0
    for live, saved in backup_paths():
        if saved.exists():
            shutil.copyfile(saved, live)
            n += 1
    shutil.rmtree(BACKUP, ignore_errors=True)
    print(f"[icon] restored {n} icons")


def gradle_version():
    text = GRADLE.read_text()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        raise SystemExit("[icon] could not read versionName/versionCode from build.gradle.kts")
    return f"{name.group(1)} {code.group(1)}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version")
    ap.add_argument("--restore", action="store_true")
    args = ap.parse_args()

    if args.restore:
        restore()
        return

    # Only the digits and the dots. Letters and parentheses cost glyphs and legibility at 48px,
    # and the number is the part that answers "is this the build I just shipped?".
    version = args.version or gradle_version()
    text = re.sub(r"\s+", " ", re.sub(r"[^0-9.]", " ", version)).strip()
    if not text:
        raise SystemExit(f"[icon] no digits in version {version!r}")

    files = icon_files()
    if not files:
        print("[icon] no launcher icons found — nothing stamped")
        return

    # Never back up over an existing backup: a second stamp without a restore in between would
    # otherwise back up the already-stamped icons and lose the originals for good.
    if BACKUP.is_dir():
        print("[icon] a backup already exists — restoring it before stamping again")
        restore()
    for live, saved in backup_paths():
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(live, saved)

    for rel in files:
        stamp_png(RES / rel, text)
    stamp_vector(text)
    print(f'[icon] stamped {len(files)} icons and the adaptive foreground with "{text}"')


if __name__ == "__main__":
    sys.exit(main())
