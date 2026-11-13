#!/usr/bin/env python3
"""Convert the T70 badminton launcher icon into an LVGL 9 image for the watch.

Source: android-t70/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png (192x192 RGBA)
Output:
  * src/badminton_icon.c  - LVGL 9 lv_image_dsc_t + pixel data (B,G,R,A byte order)
  * src/badminton_icon.h  - extern declaration
  * badminton_icon_preview.png (next to this script, for visual inspection)

Pure stdlib (no PIL required): minimal PNG decoder + box downsample.
"""

import os
import struct
import zlib

SIZE = 64  # output icon edge in pixels
SRC_PNG = os.path.join("..", "android-t70", "app", "src", "main", "res",
                       "mipmap-xxxhdpi", "ic_launcher.png")


# ---------------------------------------------------------------------------
# Minimal PNG decoder (8-bit RGB/RGBA, non-interlaced)
# ---------------------------------------------------------------------------

def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG file")
    pos = 8
    idat = bytearray()
    width = height = bit_depth = color_type = None
    while pos < len(data):
        length, tag = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, bit_depth, color_type, comp, filt, inter = \
                struct.unpack(">IIBBBBB", body)
            if bit_depth != 8 or color_type not in (2, 6) or inter != 0:
                raise ValueError(
                    f"unsupported PNG: bitdepth={bit_depth} "
                    f"colortype={color_type} interlace={inter}")
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
        pos += 12 + length

    channels = 4 if color_type == 6 else 3
    stride = width * channels
    raw = zlib.decompress(bytes(idat))

    pixels = bytearray(width * height * 4)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        ftype = raw[src]
        src += 1
        line = bytearray(raw[src:src + stride])
        src += stride
        if ftype == 1:    # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + (a + prev[i]) // 2) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif ftype != 0:
            raise ValueError(f"unsupported PNG filter {ftype}")
        prev = line
        for x in range(width):
            o = (y * width + x) * 4
            i = x * channels
            pixels[o:o + 4] = bytes((line[i], line[i + 1], line[i + 2],
                                     line[i + 3] if channels == 4 else 255))
    return width, height, pixels


# ---------------------------------------------------------------------------
# Downsample (premultiplied-alpha box filter) and emit LVGL data
# ---------------------------------------------------------------------------

def downsample(w, h, src, out_size):
    if w % out_size or h % out_size:
        raise ValueError(f"source {w}x{h} not an integer multiple of {out_size}")
    fx, fy = w // out_size, h // out_size
    out = bytearray(out_size * out_size * 4)  # B,G,R,A
    for oy in range(out_size):
        for ox in range(out_size):
            pr = pg = pb = pa = 0
            for sy in range(fy):
                for sx in range(fx):
                    i = ((oy * fy + sy) * w + ox * fx + sx) * 4
                    r, g, b, a = src[i:i + 4]
                    pr += r * a
                    pg += g * a
                    pb += b * a
                    pa += a
            n = fx * fy
            a = pa // n
            if pa:
                # premultiplied average -> straight alpha: (sum c*a) / (sum a)
                r = min(255, pr // pa)
                g = min(255, pg // pa)
                b = min(255, pb // pa)
            else:
                r = g = b = 0
            o = (oy * out_size + ox) * 4
            out[o:o + 4] = bytes((b, g, r, a))
    return out


def write_png(path, pixels, size, scale=1):
    """Preview PNG composited over a dark background."""

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    out = size * scale
    raw = bytearray()
    for py in range(out):
        raw.append(0)  # filter: none
        for px in range(out):
            i = ((py // scale) * size + (px // scale)) * 4
            b, g, r, a = pixels[i:i + 4]
            bg = 17
            raw += bytes(((r * a + bg * (255 - a)) // 255,
                          (g * a + bg * (255 - a)) // 255,
                          (b * a + bg * (255 - a)) // 255))

    ihdr = struct.pack(">IIBBBBB", out, out, 8, 2, 0, 0, 0)  # RGB
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def write_c(path, pixels, size):
    rows = []
    for i in range(0, len(pixels), 16):
        rows.append("    " + ", ".join(f"0x{v:02X}" for v in pixels[i:i + 16]))
    data = ",\n".join(rows)
    content = f"""/**
 * @file      badminton_icon.c
 * @brief     Badminton launcher icon, {size}x{size} ARGB8888, converted from the
 *            T70 launcher icon (android-t70 .../mipmap-xxxhdpi/ic_launcher.png).
 *            Generated by support/gen_badminton_icon.py - do not edit by hand.
 */
#include "badminton_icon.h"

static const uint8_t badminton_icon_map[] = {{
{data}
}};

const lv_image_dsc_t badminton_icon = {{
    .header.magic = LV_IMAGE_HEADER_MAGIC,
    .header.cf = LV_COLOR_FORMAT_ARGB8888,
    .header.flags = 0,
    .header.w = {size},
    .header.h = {size},
    .header.stride = {size} * 4,
    .data_size = sizeof(badminton_icon_map),
    .data = badminton_icon_map,
}};
"""
    with open(path, "w", newline="\n") as f:
        f.write(content)


def write_h(path):
    content = """/**
 * @file      badminton_icon.h
 * @brief     Badminton launcher icon (see badminton_icon.c).
 */
#pragma once

#include <lvgl.h>

extern const lv_image_dsc_t badminton_icon;
"""
    with open(path, "w", newline="\n") as f:
        f.write(content)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    w, h, src = read_png(os.path.normpath(os.path.join(here, SRC_PNG)))
    print(f"source icon: {w}x{h}")
    pixels = downsample(w, h, src, SIZE)
    out_src = os.path.normpath(os.path.join(here, "..", "src"))
    write_c(os.path.join(out_src, "badminton_icon.c"), pixels, SIZE)
    write_h(os.path.join(out_src, "badminton_icon.h"))
    write_png(os.path.join(here, "badminton_icon_preview.png"), pixels, SIZE,
              scale=4)
    print(f"icon written ({len(pixels)} bytes of pixel data)")


if __name__ == "__main__":
    main()
