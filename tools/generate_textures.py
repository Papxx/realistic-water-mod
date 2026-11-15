# SPDX-License-Identifier: Apache-2.0
"""Generates the mod's 16x16 textures procedurally (clean-room: no external art).

Run from the repository root:  python3 tools/generate_textures.py
Writes PNGs into common/src/main/resources/assets/fluidphysics/textures/. Deterministic: the same script always
produces the same files, so regenerated textures only differ when this script changes.
"""

import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
TEXTURES = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "fluidphysics", "textures")

SIZE = 16


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(color, factor):
    return tuple(clamp(c * factor) for c in color[:3]) + (color[3] if len(color) > 3 else 255,)


def value_noise(rng, cells=4):
    """Smooth tileable noise in 0..1 on a 16x16 grid: random values on a coarse lattice, bilinearly blended."""
    lattice = [[rng.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * SIZE for _ in range(SIZE)]
    step = SIZE / cells
    for y in range(SIZE):
        for x in range(SIZE):
            gx, gy = x / step, y / step
            x0, y0 = int(gx) % cells, int(gy) % cells
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            fx, fy = gx - int(gx), gy - int(gy)
            fx = fx * fx * (3 - 2 * fx)
            fy = fy * fy * (3 - 2 * fy)
            top = lattice[y0][x0] * (1 - fx) + lattice[y0][x1] * fx
            bottom = lattice[y1][x0] * (1 - fx) + lattice[y1][x1] * fx
            out[y][x] = top * (1 - fy) + bottom * fy
    return out


def physics_sand():
    """Warm sand with scattered grains and a faint 4x4 grid: one block is 4 x 4 x 4 particles."""
    rng = random.Random(0x5A4D)
    base = (214, 190, 132, 255)
    noise = value_noise(rng, 4)
    img = Image.new("RGBA", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            factor = 0.9 + 0.16 * noise[y][x] + rng.uniform(-0.05, 0.05)
            # The particle grid: every fourth row and column a touch darker.
            if x % 4 == 0 or y % 4 == 0:
                factor *= 0.93
            color = shade(base, factor)
            roll = rng.random()
            if roll < 0.08:
                color = (168, 136, 92, 255)  # dark grain
            elif roll < 0.14:
                color = (244, 230, 186, 255)  # bright grain
            elif roll < 0.16:
                color = (150, 104, 74, 255)  # reddish mineral grain
            img.putpixel((x, y), color)
    return img


# Bucket silhouette, drawn on a 16x16 grid. Characters:
#   O outline, M metal, L light metal (left side highlight), D dark metal (right side shadow),
#   R rim, C contents, H handle, P particle droplet, . transparent
BUCKET = [
    "................",
    "......HHHH......",
    "....HH....HH....",
    "...H..P..P..H...",
    "..H....PP....H..",
    "..ORRRRPPRRRRO..",
    "..OCCCCCCCCCCO..",
    "..ORRRRRRRRRRO..",
    "..OLMMMMMMMMDO..",
    "..OLMMMMMMMMDO..",
    "...OLMMMMMMDO...",
    "...OLMMMMMMDO...",
    "...OLMMMMMMDO...",
    "....OLMMMMDO....",
    "....OOOOOOOO....",
    "................",
]

METAL = {
    "O": (46, 48, 54, 255),
    "M": (150, 154, 162, 255),
    "L": (196, 200, 208, 255),
    "D": (104, 108, 116, 255),
    "R": (178, 182, 190, 255),
    "H": (74, 76, 84, 255),
}


def bucket(contents, droplet):
    """A metal bucket holding {contents}, with particle droplets of {droplet} hovering above it."""
    rng = random.Random(sum(contents))
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for y, row in enumerate(BUCKET):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch == "C":
                color = shade(contents, 0.9 + 0.2 * rng.random())
            elif ch == "P":
                color = droplet
            else:
                color = METAL[ch]
            img.putpixel((x, y), color)
    return img


def save(img, *parts):
    path = os.path.join(TEXTURES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path, ROOT))


def main():
    save(physics_sand(), "block", "physics_sand.png")
    save(bucket((52, 96, 214, 255), (120, 170, 255, 255)), "item", "physics_water_bucket.png")
    save(bucket((226, 96, 22, 255), (255, 186, 60, 255)), "item", "physics_lava_bucket.png")
    save(bucket((96, 186, 74, 255), (150, 226, 120, 255)), "item", "physics_slime_bucket.png")


if __name__ == "__main__":
    main()
