"""Prepare approved cube-only artwork for Android, with exactly one outer background."""
from pathlib import Path
from icon_artwork import load_cube, centered_cube, background_icon, write_manifest

root = Path(__file__).resolve().parents[1]
master = root / "artwork/cube-mark.png"
cube = load_cube(master)
resources = root / "android/app/src/main/res"
outputs = []
app_icon = root / "data/app-icon.png"
centered_cube(cube, 1024, 922).save(app_icon, compress_level=9)
outputs.append(app_icon)
for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    folder = resources / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    for name, rounded in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
        output = folder / name
        background_icon(cube, size, round_icon=rounded).save(output, compress_level=9)
        outputs.append(output)
# 108dp x4 canvas; cube-only mark fits the central 66dp safe region.
foreground = centered_cube(cube, 432, 264)
folder = resources / "drawable-xxxhdpi"
folder.mkdir(parents=True, exist_ok=True)
output = folder / "ic_launcher_foreground.png"
foreground.save(output, compress_level=9)
outputs.append(output)
store = root / "distribution/icon-512.png"
background_icon(cube, 512).convert("RGB").save(store, compress_level=9)
outputs.append(store)
write_manifest(root / "artwork/android-icon-manifest.json", master, outputs,
    foregroundCanvasDp=108, safeDiameterDp=66, foregroundHasBackground=False, background="#20242e",
    appIconCanvas=1024, appIconContent=922, alphaCleanup="discard disconnected noise; preserve centered cube and attached antialiasing")
print("Generated adaptive foreground, ten legacy icons and one store icon from cube-only artwork.")
