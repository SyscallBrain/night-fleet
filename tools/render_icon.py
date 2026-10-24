#!/usr/bin/env python3
"""Renders the Night Fleet launcher icon, the F-Droid icon and the feature graphic.

One geometry, two outputs:
- the adaptive-icon vector drawables in app/src/main/res/drawable/ (foreground, background, monochrome);
- the raster store images in fastlane/metadata/android/<locale>/images/ (icon.png 512x512 and
  featureGraphic.png 1024x500), rasterised from the same polygons with a scanline fill and 4x4
  supersampling.

Standard library only (no Pillow, no cairo). Output is deterministic: no timestamps, fixed zlib level,
coordinates rounded to two decimals before they are used by either output. Run it from anywhere:

    python3 tools/render_icon.py

Then check that the PNGs did not change: sha256sum fastlane/metadata/android/*/images/*.png
"""

from __future__ import annotations

import math
import struct
import sys
import zlib
from array import array
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCALES = ("en-US", "pt-PT")
DRAWABLE_DIR = ROOT / "app/src/main/res/drawable"
IMAGES_DIRS = [ROOT / "fastlane/metadata/android" / locale / "images" for locale in LOCALES]

# Colours (docs/DESIGN.md section 2 and the launcher colours of values/colors.xml).
CHART_NIGHT = (0x0B, 0x15, 0x28)
BRASS = (0xE0, 0xB1, 0x54)
BRASS_DARK = (0xA9, 0x7C, 0x2E)
WAX = (0xD2, 0x49, 0x3A)
WAX_RIM = (0x8E, 0x2A, 0x20)
RHUMB_ALPHA = 0x1A  # very low alpha: rhumb lines must not compete with the rose

SUPERSAMPLE = 4  # 4 x 4 sub-samples per pixel
ICON_VIEWPORT = 108.0  # adaptive icon viewport (dp); the safe zone is the 66 dp circle at the centre
ICON_SIZE = 512
FEATURE_SIZE = (1024, 500)

Point = tuple[float, float]
Subpaths = list[list[Point]]


class Shape:
    """A filled path with the colour (r, g, b, alpha 0-255) and its subpaths (even-odd fill)."""

    def __init__(self, subpaths: Subpaths, colour: tuple[int, int, int, int]):
        self.subpaths = [[(_r(x), _r(y)) for x, y in poly] for poly in subpaths]
        self.colour = colour


def _r(value: float) -> float:
    return round(value, 2)


def _circle(cx: float, cy: float, radius: float, n: int = 64) -> list[Point]:
    return [
        (cx + radius * math.cos(2 * math.pi * i / n), cy + radius * math.sin(2 * math.pi * i / n))
        for i in range(n)
    ]


def _scallop(cx: float, cy: float, radius: float, amp: float, bumps: int = 12, n: int = 96) -> list[Point]:
    """Wax seal edge: a circle whose radius swings by `amp` `bumps` times around."""
    points = []
    for i in range(n):
        a = 2 * math.pi * i / n
        r = radius + amp * math.cos(bumps * a)
        points.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return points


def _rose(cx: float, cy: float, s: float, mono: bool) -> list[Shape]:
    """Eight points of the compass rose: cardinal points brass, diagonal points darker brass."""
    shapes: list[Shape] = []
    for k in range(8):
        angle = k * math.pi / 4
        dx, dy = math.sin(angle), -math.cos(angle)  # north is up (y grows downwards)
        px, py = math.cos(angle), math.sin(angle)
        cardinal = k % 2 == 0
        length = (31.0 if cardinal else 21.0) * s
        half = (5.3 if cardinal else 3.6) * s
        tip = (cx + dx * length, cy + dy * length)
        left = (cx + px * half, cy + py * half)
        right = (cx - px * half, cy - py * half)
        colour = (*(BRASS if cardinal else BRASS_DARK), 255)
        if mono:
            colour = (255, 255, 255, 255)
        shapes.append(Shape([[tip, left, right]], colour))
    hub_outer, hub_inner = 6.5 * s, 3.0 * s
    if mono:
        shapes.append(Shape([_circle(cx, cy, hub_outer), _circle(cx, cy, hub_inner)], (255, 255, 255, 255)))
    else:
        shapes.append(Shape([_circle(cx, cy, hub_outer)], (*BRASS, 255)))
        shapes.append(Shape([_circle(cx, cy, hub_inner)], (*CHART_NIGHT, 255)))
    return shapes


def _seal(cx: float, cy: float, s: float, mono: bool) -> list[Shape]:
    """Red wax seal: scalloped rim, wax face and a stamp ring. Mono: one silhouette with the stamp cut out."""
    rim = _scallop(cx, cy, 8.4 * s, 0.45 * s)
    if mono:
        return [Shape([rim, _circle(cx, cy, 3.0 * s)], (255, 255, 255, 255))]
    face = _scallop(cx, cy, 7.0 * s, 0.45 * s)
    stamp_outer, stamp_inner = 3.6 * s, 2.9 * s
    return [
        Shape([rim], (*WAX_RIM, 255)),
        Shape([face], (*WAX, 255)),
        Shape([_circle(cx, cy, stamp_outer), _circle(cx, cy, stamp_inner)], (*WAX_RIM, 255)),
    ]


def _line(x0: float, y0: float, x1: float, y1: float, width: float, colour: tuple[int, int, int, int]) -> Shape:
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    nx, ny = -dy / length * width / 2, dx / length * width / 2
    return Shape([[(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]], colour)


def _ring(cx: float, cy: float, radius: float, width: float, colour: tuple[int, int, int, int]) -> Shape:
    return Shape([_circle(cx, cy, radius + width / 2), _circle(cx, cy, radius - width / 2)], colour)


def _rhumb_lines(cx: float, cy: float, reach: float, colour: tuple[int, int, int, int]) -> list[Shape]:
    shapes = []
    for k in range(16):
        angle = k * math.pi / 8
        dx, dy = math.sin(angle), -math.cos(angle)
        shapes.append(_line(cx + dx * 6, cy + dy * 6, cx + dx * reach, cy + dy * reach, 0.35, colour))
    return shapes


def icon_background() -> list[Shape]:
    """Layer 1 of the adaptive icon: chart night with fine rhumb lines and two bearing rings."""
    shapes = [Shape([[(0, 0), (108, 0), (108, 108), (0, 108)]], (*CHART_NIGHT, 255))]
    rhumb = (*BRASS, RHUMB_ALPHA)
    shapes += _rhumb_lines(54, 54, 90, rhumb)
    shapes += [_ring(54, 54, 40, 0.5, rhumb), _ring(54, 54, 58, 0.5, rhumb)]
    return shapes


def icon_foreground(mono: bool = False) -> list[Shape]:
    """Layer 2 (and the monochrome layer when mono=True): the rose and the seal, inside the 66 dp safe zone."""
    shapes = _rose(54, 54, 1.0, mono)
    # The seal sits in the upper-right quadrant over the north-east point (17 units from the centre on each axis).
    shapes += _seal(70.5, 37.5, 1.0, mono)
    return shapes


def feature_graphic() -> list[Shape]:
    """1024x500 store graphic without text: a rose on a chart grid, rhumb lines and wax seals."""
    width, height = FEATURE_SIZE
    cx, cy, s = 790.0, 250.0, 4.0
    shapes = [Shape([[(0, 0), (width, 0), (width, height), (0, height)]], (*CHART_NIGHT, 255))]
    graticule = (*BRASS, 0x12)
    for x in range(0, width + 1, 40):
        shapes.append(_line(x, 0, x, height, 1.0, graticule))
    for y in range(0, height + 1, 40):
        shapes.append(_line(0, y, width, y, 1.0, graticule))
    rhumb = (*BRASS, RHUMB_ALPHA + 0x04)
    shapes += _rhumb_lines(cx, cy, 1100, rhumb)
    shapes += [_ring(cx, cy, 160, 1.0, rhumb), _ring(cx, cy, 232, 1.0, rhumb), _ring(cx, cy, 330, 1.0, rhumb)]
    border = (*BRASS, 0x40)
    inset = 14.0
    shapes += [
        _line(inset, inset, width - inset, inset, 1.5, border),
        _line(width - inset, inset, width - inset, height - inset, 1.5, border),
        _line(width - inset, height - inset, inset, height - inset, 1.5, border),
        _line(inset, height - inset, inset, inset, 1.5, border),
    ]
    shapes += _rose(cx, cy, s, mono=False)
    # Seals: one over the north-east point (same offset as the icon), three more scattered on the grid.
    shapes += _seal(cx + 16.5 * s, cy - 16.5 * s, s, mono=False)
    shapes += _seal(430.0, 175.0, 2.3, mono=False)
    shapes += _seal(585.0, 395.0, 1.9, mono=False)
    shapes += _seal(245.0, 340.0, 2.6, mono=False)
    return shapes


def _as_shapes_scaled(shapes: list[Shape], scale: float) -> list[Shape]:
    if scale == 1.0:
        return shapes
    return [Shape([[(x * scale, y * scale) for x, y in poly] for poly in sh.subpaths], sh.colour) for sh in shapes]


def rasterize(shapes: list[Shape], width: int, height: int) -> bytes:
    """Fills the shapes in order (painter's order, even-odd rule) and returns RGB bytes, row by row."""
    canvas = array("d", bytes(8 * width * height * 4))  # premultiplied RGBA, all zero
    for shape in shapes:
        _fill(canvas, width, height, shape)
    out = bytearray(width * height * 3)
    for i in range(width * height):
        a = canvas[i * 4 + 3]
        for c in range(3):
            v = canvas[i * 4 + c] / a if a > 0 else 0.0
            out[i * 3 + c] = max(0, min(255, int(round(v * 255))))
    return bytes(out)


def _fill(canvas: array, width: int, height: int, shape: Shape) -> None:
    edges = []
    for poly in shape.subpaths:
        n = len(poly)
        for i in range(n):
            (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
            if y0 == y1:
                continue
            if y0 > y1:
                x0, y0, x1, y1 = x1, y1, x0, y0
            edges.append((y0, y1, x0, x1))
    if not edges:
        return
    y_first = max(0, int(math.floor(min(e[0] for e in edges))))
    y_last = min(height - 1, int(math.ceil(max(e[1] for e in edges))))
    r, g, b, alpha = shape.colour[0] / 255, shape.colour[1] / 255, shape.colour[2] / 255, shape.colour[3] / 255
    sub = SUPERSAMPLE
    inv_sub2 = 1.0 / (sub * sub)
    for py in range(y_first, y_last + 1):
        cov = [0.0] * width
        for sy in range(sub):
            sample_y = py + (sy + 0.5) / sub
            xs = []
            for ya, yb, xa, xb in edges:
                if ya <= sample_y < yb:
                    xs.append(xa + (sample_y - ya) * (xb - xa) / (yb - ya))
            if len(xs) < 2:
                continue
            xs.sort()
            for k in range(0, len(xs) - 1, 2):
                _add_span(cov, xs[k], xs[k + 1], width, sub, inv_sub2)
        base = py * width * 4
        for px in range(width):
            c = cov[px]
            if c <= 0.0:
                continue
            sa = alpha * min(c, 1.0)
            i = base + px * 4
            canvas[i] = r * sa + canvas[i] * (1 - sa)
            canvas[i + 1] = g * sa + canvas[i + 1] * (1 - sa)
            canvas[i + 2] = b * sa + canvas[i + 2] * (1 - sa)
            canvas[i + 3] = sa + canvas[i + 3] * (1 - sa)


def _add_span(cov: list[float], a: float, b: float, width: int, sub: int, inv_sub2: float) -> None:
    first = max(0, int(math.floor(a)))
    last = min(width - 1, int(math.floor(b)))
    for px in range(first, last + 1):
        if px >= a and px + 1 <= b:  # pixel fully inside the span: all of its sub-samples are covered
            cov[px] += sub * inv_sub2
            continue
        for k in range(sub):
            sx = px + (k + 0.5) / sub
            if a <= sx < b:
                cov[px] += inv_sub2


def png_bytes(rgb: bytes, width: int, height: int) -> bytes:
    raw = bytearray()
    row = width * 3
    for y in range(height):
        raw.append(0)  # filter type 0 (none)
        raw += rgb[y * row:(y + 1) * row]

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB, no interlace
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def _fmt(value: float) -> str:
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def vector_xml(shapes: list[Shape], description: str) -> str:
    paths = []
    for shape in shapes:
        r, g, b, a = shape.colour
        colour = f"#{a:02X}{r:02X}{g:02X}{b:02X}"
        data = " ".join(
            "M" + " L".join(f"{_fmt(x)},{_fmt(y)}" for x, y in poly) + " Z" for poly in shape.subpaths
        )
        paths.append(f'    <path android:fillColor="{colour}" android:fillType="evenOdd"\n        android:pathData="{data}" />')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {description}. Generated by tools/render_icon.py: do not edit by hand. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def main() -> int:
    if sys.version_info < (3, 10):
        print("render_icon: Python 3.10 or newer is required", file=sys.stderr)
        return 2

    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)
    (DRAWABLE_DIR / "ic_launcher_background.xml").write_text(
        vector_xml(icon_background(), "Night Fleet launcher background: chart night with rhumb lines"),
        encoding="utf-8",
    )
    (DRAWABLE_DIR / "ic_launcher_foreground.xml").write_text(
        vector_xml(icon_foreground(), "Night Fleet launcher foreground: compass rose and wax seal"),
        encoding="utf-8",
    )
    (DRAWABLE_DIR / "ic_launcher_monochrome.xml").write_text(
        vector_xml(icon_foreground(mono=True), "Night Fleet launcher monochrome: rose and seal silhouette"),
        encoding="utf-8",
    )

    scale = ICON_SIZE / ICON_VIEWPORT
    icon_shapes = _as_shapes_scaled(icon_background() + icon_foreground(), scale)
    icon_png = png_bytes(rasterize(icon_shapes, ICON_SIZE, ICON_SIZE), ICON_SIZE, ICON_SIZE)
    feature_png = png_bytes(rasterize(feature_graphic(), *FEATURE_SIZE), *FEATURE_SIZE)
    for images in IMAGES_DIRS:
        images.mkdir(parents=True, exist_ok=True)
        (images / "icon.png").write_bytes(icon_png)
        (images / "featureGraphic.png").write_bytes(feature_png)
        print(f"wrote {images / 'icon.png'} ({len(icon_png)} bytes)")
        print(f"wrote {images / 'featureGraphic.png'} ({len(feature_png)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
