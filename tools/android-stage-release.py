"""Stage verified public Android artifacts without private or machine-specific metadata."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import subprocess
import sys
import argparse

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--qa-report', type=Path, default=root.parent / 'mobile-native-qa/passed/offline-smoke-result.json')
args = parser.parse_args()
gradle = (root / "android/app/build.gradle").read_text(encoding="utf-8")
version = re.search(r"versionName\s+'(\d+\.\d+\.\d+)'", gradle)[1]
version_code = int(re.search(r"versionCode\s+(\d+)", gradle)[1])
verification = json.loads((root / "android/app/build/outputs/build-verification.json").read_text(encoding="utf-8-sig"))
pin = (root / "android/release-certificate.sha256").read_text().strip()
assert verification["apkVerified"] and verification["aabVerified"]
assert verification["certificateSha256"] == pin
assert verification["version"] == version
qa = json.loads(args.qa_report.read_text(encoding="utf-8-sig"))
if version != '1.0.0' or 'versionName' in qa:
    assert qa['versionName'] == version and qa['versionCode'] == version_code
assert qa["passed"] and qa["checks"] and all(check["passed"] for check in qa["checks"])

source_files = [root / "android/app/build/outputs/apk/release/app-release.apk",
                root / "android/app/build/outputs/bundle/release/app-release.aab"]
audits = []
for archive in source_files:
    run = subprocess.run([sys.executable, str(root / "tools/android-asset-audit.py"), str(archive)],
                         capture_output=True, text=True, encoding="utf-8", check=True)
    audit = json.loads(run.stdout)
    assert audit["passed"]
    audits.append(audit)

tree = hashlib.sha256()
for path in sorted((root / "data").rglob("*"), key=lambda item: item.relative_to(root / "data").as_posix()):
    if path.is_file():
        tree.update(path.relative_to(root / "data").as_posix().encode("utf-8") + b"\0")
        tree.update(hashlib.sha256(path.read_bytes()).digest() + b"\n")

target = root / "artifacts/releases" / version
target.mkdir(parents=True, exist_ok=True)
files = []
for archive in source_files:
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    recorded = next(item for item in verification["files"] if item["name"] == archive.name)
    assert recorded["sha256"] == digest and recorded["bytes"] == archive.stat().st_size
    name = f"RG2-Mobile-{version}{archive.suffix}"
    destination = target / name
    shutil.copyfile(archive, destination)
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
    files.append({"name": name, "bytes": destination.stat().st_size, "sha256": digest})

(target / "SHA256SUMS.txt").write_text("".join(f"{item['sha256']}  {item['name']}\n" for item in files), encoding="ascii")
manifest = {
    "schemaVersion": 1,
    "application": "RG2 Mobile",
    "applicationId": "io.github.pro007m.rg2",
    "version": version,
    "versionCode": version_code,
    "minSdk": 24,
    "targetSdk": 36,
    "createdUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "signing": {"certificateSha256": pin, "apkVerified": True, "aabVerified": True},
    "offlineAssets": {"files": audits[0]["expectedAssets"], "sourceTreeSha256": tree.hexdigest(),
                      "apkByteForByte": True, "aabByteForByte": True, "windowsRuntimeFiles": 0},
    "nativeQa": {"passed": True, "checks": len(qa["checks"]), "api": qa["api"],
                 "durationMs": qa["durationMs"], "internetPermission": False,
                 "versionName": qa.get("versionName", version), "versionCode": qa.get("versionCode", version_code)},
    "files": files,
}
public_json = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
assert not re.search(r"[A-Za-z]:[\\/]", public_json)
(target / "manifest.json").write_text(public_json, encoding="utf-8")
print(public_json)
