#!/usr/bin/env python3
"""Generate Dink launcher icon + TV banners from code (no external art).

Glyph: two chime bars meeting at an apex ("clink"), a gold spark at the strike
point and an eighth note — the onomatopoeia "dink". Minimalist, flat.

Renders at 4x supersample then downscales (Lanczos) for crisp edges.
Outputs:
  app/src/main/res/mipmap-*/ic_launcher.webp     (48..192 legacy launcher icon)
  app/src/main/res/drawable-xhdpi/banner.png     (320x180 TV launcher banner)
  play/icon-512.png                              (Play hi-res icon)
  play/banner-1280x720.png                       (Play TV banner / feature)
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
PLAY = os.path.join(ROOT, "play")
LATO_BLACK = "/usr/share/fonts/truetype/lato/Lato-Black.ttf"

BG_TOP = (38, 28, 82)
BG_BOT = (78, 45, 150)
GLOW = (132, 95, 210)
BAR = (244, 238, 255)
BAR_SHADE = (205, 196, 235)
GOLD = (255, 196, 78)
GOLD_HI = (255, 224, 158)

SS = 4  # supersample factor


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vgrad(w, h, top, bot):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        c = lerp(top, bot, y / max(1, h - 1))
        for x in range(w):
            px[x, y] = c
    return img


def radial_glow(size, center, radius, color, max_alpha):
    g = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(g)
    steps = 60
    for i in range(steps, 0, -1):
        r = radius * i / steps
        a = int(max_alpha * (1 - i / steps))
        d.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r], fill=a)
    layer = Image.new("RGB", (size, size), color)
    return layer, g


def rounded_bar(length, width, color, radius=None):
    """Horizontal rounded-rect tile, transparent margins for clean rotation."""
    length, width = int(length), int(width)
    pad = width
    W, H = length + pad * 2, width + pad * 2
    tile = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    r = radius if radius is not None else width // 2
    d.rounded_rectangle([pad, pad, pad + length, pad + width], radius=r, fill=color + (255,))
    # subtle inner shade band along the bottom edge for a little dimension
    d.rounded_rectangle([pad, pad + width * 0.62, pad + length, pad + width],
                        radius=r, fill=BAR_SHADE + (90,))
    return tile


def paste_bar_from_apex(canvas, apex, angle_deg, length, width):
    """Place a bar so one rounded end sits at `apex`, extending at angle (deg,
    measured from +x axis, growing downward as angle increases in screen space)."""
    tile = rounded_bar(length, width, BAR)
    rot = tile.rotate(-angle_deg, expand=True, resample=Image.BICUBIC)
    # vector from apex along the bar to its center
    a = math.radians(angle_deg)
    cx = apex[0] + math.cos(a) * (length / 2)
    cy = apex[1] + math.sin(a) * (length / 2)
    canvas.alpha_composite(rot, (int(cx - rot.width / 2), int(cy - rot.height / 2)))


def spark(canvas, c, r, color, color_hi):
    d = ImageDraw.Draw(canvas)
    # four-point star: long thin diamonds vertical + horizontal
    def diamond(cx, cy, rl, rs, col):
        d.polygon([(cx, cy - rl), (cx + rs, cy), (cx, cy + rl), (cx - rs, cy)], fill=col)
    diamond(c[0], c[1], r, r * 0.18, color + (255,))
    diamond(c[0], c[1], r * 0.18, r, color + (255,))
    # diagonal shorter rays
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        ex = c[0] + math.cos(a) * r * 0.62
        ey = c[1] + math.sin(a) * r * 0.62
        d.line([c, (ex, ey)], fill=color + (220,), width=max(2, int(r * 0.10)))
    d.ellipse([c[0] - r * 0.30, c[1] - r * 0.30, c[0] + r * 0.30, c[1] + r * 0.30],
              fill=color_hi + (255,))


def eighth_note(canvas, cx, cy, scale, color):
    """Filled eighth note: oval head, stem, flag. cy is the note-head center."""
    d = ImageDraw.Draw(canvas)
    hw, hh = scale * 1.05, scale * 0.78
    # head (slight italic tilt via polygon-ish ellipse)
    d.ellipse([cx - hw, cy - hh, cx + hw, cy + hh], fill=color + (255,))
    stem_w = max(2, int(scale * 0.26))
    stem_x = cx + hw - stem_w * 0.5
    stem_top = cy - scale * 3.4
    d.rectangle([stem_x - stem_w / 2, stem_top, stem_x + stem_w / 2, cy], fill=color + (255,))
    # flag
    d.polygon([
        (stem_x + stem_w / 2, stem_top),
        (stem_x + stem_w / 2 + scale * 1.5, stem_top + scale * 1.1),
        (stem_x + stem_w / 2 + scale * 1.2, stem_top + scale * 2.2),
        (stem_x + stem_w / 2, stem_top + scale * 1.2),
    ], fill=color + (255,))


def soundwaves(canvas, c, r0, color):
    d = ImageDraw.Draw(canvas)
    for k, r in enumerate((r0, r0 * 1.5)):
        w = max(2, int(r0 * 0.10))
        for side, (a0, a1) in (("l", (150, 210)), ("r", (-30, 30))):
            d.arc([c[0] - r, c[1] - r, c[0] + r, c[1] + r], a0, a1,
                  fill=color + (200 - k * 70,), width=w)


def glyph_layer(size):
    """The Dink mark on a transparent square of side `size`."""
    L = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    apex = (size * 0.46, size * 0.40)
    bar_len = size * 0.40
    bar_w = size * 0.105
    # two bars forming an inverted-V meeting at the apex (the strike)
    paste_bar_from_apex(L, apex, 118, bar_len, bar_w)   # down-left
    paste_bar_from_apex(L, apex, 62, bar_len, bar_w)    # down-right
    soundwaves(L, apex, size * 0.22, GOLD)
    spark(L, apex, size * 0.14, GOLD, GOLD_HI)
    eighth_note(L, size * 0.70, size * 0.30, size * 0.052, GOLD)
    return L


def make_icon(px):
    s = px * SS
    base = vgrad(s, s, BG_TOP, BG_BOT).convert("RGBA")
    layer, mask = radial_glow(s, (s * 0.46, s * 0.42), s * 0.5, GLOW, 150)
    base.paste(layer, (0, 0), mask)
    base.alpha_composite(glyph_layer(s))
    return base.resize((px, px), Image.LANCZOS).convert("RGB")


def make_banner(w, h, with_word=True):
    W, H = w * SS, h * SS
    base = vgrad(W, H, BG_TOP, BG_BOT).convert("RGBA")
    g = glyph_layer(int(H * 0.92))
    gap = int(H * 0.04)
    d = ImageDraw.Draw(base)
    if with_word:
        fsz = int(H * 0.40)
        font = ImageFont.truetype(LATO_BLACK, fsz)
        l, t, r, b = d.textbbox((0, 0), "Dink", font=font)
        tw, th = r - l, b - t
        total = g.width + gap + tw
        gx = (W - total) // 2
        ty = (H - th) // 2 - t
    else:
        total = g.width
        gx = (W - total) // 2
    gy = (H - g.height) // 2
    # glow centered on the glyph
    layer, mask = radial_glow(max(W, H), (gx + g.width // 2, H // 2), H * 0.85, GLOW, 130)
    base.paste(layer.crop((0, 0, W, H)), (0, 0), mask.crop((0, 0, W, H)))
    base.alpha_composite(g, (gx, gy))
    if with_word:
        d = ImageDraw.Draw(base)
        d.text((gx + g.width + gap, ty), "Dink", font=font, fill=BAR)
    return base.resize((w, h), Image.LANCZOS).convert("RGB")


def main():
    os.makedirs(PLAY, exist_ok=True)
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in dens.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        make_icon(px).save(os.path.join(d, "ic_launcher.webp"), "WEBP", quality=95, method=6)
        make_icon(px).save(os.path.join(d, "ic_launcher_round.webp"), "WEBP", quality=95, method=6)
    bdir = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(bdir, exist_ok=True)
    make_banner(320, 180).save(os.path.join(bdir, "banner.png"), "PNG")
    make_icon(512).save(os.path.join(PLAY, "icon-512.png"), "PNG")
    make_banner(1280, 720).save(os.path.join(PLAY, "banner-1280x720.png"), "PNG")
    # preview for review
    make_icon(432).save(os.path.join(PLAY, "_preview_icon.png"), "PNG")
    make_banner(640, 360).save(os.path.join(PLAY, "_preview_banner.png"), "PNG")
    print("done")


if __name__ == "__main__":
    main()
