#!/usr/bin/env python3
"""Generate Windows distribution images from the macOS icon set.

The launcher icon, the installer header (150x57), the welcome-page sidebar (164x314) and the
installer/uninstaller icons are all derived from vibeidea.icns, so the two platforms cannot drift
apart: one source image, one script, deterministic output.

Requires Pillow (`pip install pillow`). Run from the repository root:
    python vibe-plugins/tools/makeWinImages.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
ICNS = ROOT / "vibeidea-customization/resources/mac/vibeidea.icns"
OUT = ROOT / "vibeidea-customization/resources/win"

# Brand palette — the same stops as vibeidea.svg.
BG = (0x16, 0x17, 0x1B)
PINK = (0xFF, 0x2E, 0x97)
PURPLE = (0x9D, 0x5C, 0xFF)
CYAN = (0x00, 0xE5, 0xFF)

LAUNCHER_SIZES = [16, 20, 24, 32, 40, 48, 64, 256]
INSTALLER_SIZES = [16, 24, 32, 48]


def icon(size: int) -> Image.Image:
    """Exact icns rendition when one exists (16 and 32 are hand-simplified), otherwise a downscale."""
    im = Image.open(ICNS)
    available = sorted({s[0] for s in im.info["sizes"]})
    src = size if size in available else available[-1]
    im.size = (src, src)
    im.load()
    im = im.convert("RGBA")
    return im if src == size else im.resize((size, size), Image.LANCZOS)


def write_ico(path: Path, sizes: list[int]) -> None:
    base = icon(256)
    extra = [icon(s) for s in (16, 32) if s in sizes]
    base.save(path, format="ICO", sizes=[(s, s) for s in sizes], append_images=extra, bitmap_format="bmp")


def glow(size: tuple[int, int], blobs: list[tuple[tuple[int, int, int, int], tuple[int, int, int]]], blur: int) -> Image.Image:
    """Soft neon blobs over the dark ground — the installer's only decoration."""
    canvas = Image.new("RGBA", size, BG + (255,))
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for box, color in blobs:
        draw.ellipse(box, fill=color + (150,))
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    return Image.alpha_composite(canvas, layer)


def save_bmp(im: Image.Image, path: Path) -> None:
    im.convert("RGB").save(path, format="BMP")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    write_ico(OUT / "vibeidea.ico", LAUNCHER_SIZES)
    write_ico(OUT / "install.ico", INSTALLER_SIZES)
    write_ico(OUT / "uninstall.ico", INSTALLER_SIZES)

    header = glow((150, 57), [((70, -30, 190, 60), PURPLE), ((110, 20, 200, 110), CYAN)], blur=22)
    header.alpha_composite(icon(40), (9, 8))
    save_bmp(header, OUT / "headerlogo.bmp")

    logo = glow((164, 314), [((-60, 150, 120, 330), PINK), ((40, 200, 220, 400), PURPLE), ((90, 260, 260, 420), CYAN)], blur=30)
    logo.alpha_composite(icon(96), (34, 34))
    save_bmp(logo, OUT / "logo.bmp")
    print("written:", ", ".join(sorted(p.name for p in OUT.iterdir())))


if __name__ == "__main__":
    main()
