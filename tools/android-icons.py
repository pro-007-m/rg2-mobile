"""Resize the existing RG2 artwork into Android launcher resources; no artwork changes."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = Image.open(root / "data/app-icon.png").convert("RGBA")
resources = root / "android/app/src/main/res"
for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    folder = resources / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    resized = source.resize((size, size), Image.Resampling.LANCZOS)
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        resized.save(folder / name)
foreground = Image.new("RGBA", (432, 432))
foreground.alpha_composite(source.resize((324, 324), Image.Resampling.LANCZOS), (54, 54))
folder = resources / "drawable-xxxhdpi"
folder.mkdir(parents=True, exist_ok=True)
foreground.save(folder / "ic_launcher_foreground.png")
print("Generated 11 launcher PNGs from the unchanged existing icon.")
