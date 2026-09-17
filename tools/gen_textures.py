"""Generator for GregScope's 16x16 RGBA textures (design-v0.2 section 12.2). Original art, MIT like the rest of the repo.

The PNGs are written with the standard library only (struct + zlib; no PIL), deterministically, so running the script
again must reproduce the committed files byte for byte. The script is dev-only; it is not part of the mod jar.

Usage (from the repository root; Python 3):
    python tools/gen_textures.py src/main/resources/assets/gregscope/textures
Then check: `git diff --exit-code src/main/resources/assets/gregscope/textures`.

Icon names are fixed by the code (`GregScopeAssets`) and by errata E1: GT's custom block icon key is appended
straight after `textures/blocks/`, so the cover overlay lives under `blocks/iconsets/`.
"""
import os
import struct
import sys
import zlib

SIZE = 16

TRANSPARENT = (0, 0, 0, 0)
BEZEL_DARK = (38, 41, 46, 255)
BEZEL_LIGHT = (92, 98, 107, 255)
SCREEN = (16, 44, 48, 255)
TRACE = (84, 232, 164, 255)
TRACE_DIM = (40, 120, 92, 255)
LED = (255, 176, 32, 255)
# MV-ish aluminium plate for the item icon.
PLATE = (170, 186, 204, 255)
PLATE_LIGHT = (214, 224, 236, 255)
PLATE_DARK = (104, 118, 136, 255)


def blank(color=TRANSPARENT):
    return [[color for _ in range(SIZE)] for _ in range(SIZE)]


def rect(img, x0, y0, x1, y1, color):
    """Fill the inclusive rectangle [x0..x1] x [y0..y1]."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            img[y][x] = color


def frame(img, x0, y0, x1, y1, light, dark):
    """A one-pixel bevelled border: light on top/left, dark on bottom/right."""
    for x in range(x0, x1 + 1):
        img[y0][x] = light
        img[y1][x] = dark
    for y in range(y0, y1 + 1):
        img[y][x0] = light
        img[y][x1] = dark


# A small oscilloscope trace, one y per column of the screen interior (x 4..11), screen-relative rows 0..5.
TRACE_ROWS = [3, 3, 1, 4, 0, 5, 2, 3]


def scope(img, x0, y0):
    """A 10x8 scope screen with a bezel, whose top-left corner is (x0, y0)."""
    frame(img, x0, y0, x0 + 9, y0 + 7, BEZEL_LIGHT, BEZEL_DARK)
    rect(img, x0 + 1, y0 + 1, x0 + 8, y0 + 6, SCREEN)
    # Dim grid line through the middle, then the bright trace over it.
    for x in range(x0 + 1, x0 + 9):
        img[y0 + 4][x] = TRACE_DIM
    for i, row in enumerate(TRACE_ROWS):
        img[y0 + 1 + row][x0 + 1 + i] = TRACE
    # Status LED in the bezel's top-right corner.
    img[y0][x0 + 9] = LED


def sensor_overlay():
    """Cover overlay: drawn over the machine's own face texture, so the background stays transparent."""
    img = blank()
    scope(img, 3, 4)
    return img


def machine_sensor_item():
    """Inventory icon: the same scope mounted on an aluminium cover plate."""
    img = blank()
    rect(img, 1, 1, 14, 14, PLATE)
    frame(img, 1, 1, 14, 14, PLATE_LIGHT, PLATE_DARK)
    # Corner screws.
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        img[y][x] = PLATE_DARK
    scope(img, 3, 4)
    return img


def png_bytes(img):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(channel for pixel in row for channel in pixel) for row in img)
    # IHDR: width, height, bit depth 8, colour type 6 (RGBA), compression 0, filter 0, interlace 0.
    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


TEXTURES = {
    # GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY (errata E1 path)
    "blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png": sensor_overlay,
    # GregScopeAssets.ITEM_ICON_MACHINE_SENSOR
    "items/machine_sensor.png": machine_sensor_item,
}


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: python tools/gen_textures.py src/main/resources/assets/gregscope/textures")
    out_dir = sys.argv[1]
    for relative, draw in sorted(TEXTURES.items()):
        img = draw()
        assert len(img) == SIZE and all(len(row) == SIZE for row in img)
        path = os.path.join(out_dir, *relative.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(png_bytes(img))
        print("wrote", path)


if __name__ == "__main__":
    main()
