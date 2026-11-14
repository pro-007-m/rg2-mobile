"""Verify every packaged offline web asset against the current complete data tree."""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("apk", nargs="?", type=Path,
                    default=root / "android/app/build/outputs/apk/release/app-release.apk")
args = parser.parse_args()
data = root / "data"
prefix = "base/assets/www/" if args.apk.suffix.lower() == ".aab" else "assets/www/"
expected = {prefix + path.relative_to(data).as_posix(): path
            for path in data.rglob("*") if path.is_file()}
with zipfile.ZipFile(args.apk) as archive:
    actual = {name for name in archive.namelist() if name.startswith(prefix) and not name.endswith("/")}
    missing = sorted(expected.keys() - actual)
    extra = sorted(actual - expected.keys())
    mismatches = [name for name in sorted(expected.keys() & actual)
                  if hashlib.sha256(archive.read(name)).digest() != hashlib.sha256(expected[name].read_bytes()).digest()]
    windows_runtime = [name for name in archive.namelist() if name.lower().endswith((".exe", ".dll", ".pdb"))]
report = {"passed": not (missing or extra or mismatches or windows_runtime),
          "archive": str(args.apk.resolve()), "expectedAssets": len(expected), "packagedAssets": len(actual),
          "missing": missing, "extra": extra, "byteMismatches": mismatches,
          "windowsRuntimeFiles": windows_runtime}
print(json.dumps(report, ensure_ascii=False, indent=2))
raise SystemExit(0 if report["passed"] else 1)
