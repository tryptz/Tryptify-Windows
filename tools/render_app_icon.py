#!/usr/bin/env python3
"""
Renders the app icon for the Windows installer and the Linux package from the
Android launcher icon's design (app/src/main/res/drawable/ic_launcher_foreground.xml
in the Android repo): five white bars on black.

Android masks an adaptive icon to its central 72 of 108 units, so the same crop
is used here; the corners are rounded the way a desktop app tile is. Each size
is drawn from an 8x supersample so the 16 px icon keeps five distinct bars.

    pip install pillow
    python3 tools/render_app_icon.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'packaging')

VIEW_MIN, VIEW_SIZE = 18.0, 72.0          # the visible part of the 108-unit viewport
BARS = [(34, 36, 72), (44, 30, 78), (54, 26, 82), (64, 30, 78), (74, 36, 72)]  # x, top, bottom
STROKE = 4.0
CORNER = 0.22                              # corner radius as a fraction of the side


def render(size: int) -> Image.Image:
    ss = 8
    big = size * ss
    scale = big / VIEW_SIZE
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * CORNER), fill=(0, 0, 0, 255))
    half = STROKE / 2
    for x, top, bottom in BARS:
        # Android's default stroke cap is butt: the bar spans exactly top..bottom.
        d.rectangle([(x - half - VIEW_MIN) * scale, (top - VIEW_MIN) * scale,
                     (x + half - VIEW_MIN) * scale, (bottom - VIEW_MIN) * scale], fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    base = render(256)
    base.save(os.path.join(OUT, 'tryptify.ico'), sizes=[(s, s) for s in sizes],
              append_images=[render(s) for s in sizes if s != 256])
    render(512).save(os.path.join(OUT, 'tryptify.png'))
    print('wrote packaging/tryptify.ico and packaging/tryptify.png')


if __name__ == '__main__':
    main()
