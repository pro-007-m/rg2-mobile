"""Shared fail-closed policy for public Android release files (no network/SDK)."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import re
import stat
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def valid_version(version):
    require(isinstance(version, str) and re.fullmatch(r"\d+\.\d+\.\d+", version), "Invalid release version")
    return version


def public_allowlist(version):
    valid_version(version)
    return {f"RG2-Mobile-{version}.apk", f"RG2-Mobile-{version}-Source.zip", "manifest.json", "SHA256SUMS.txt"}


def no_links(path):
    path = Path(path).absolute()
    for item in (path, *path.parents):
        if item.exists() or item.is_symlink():
            info = item.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not (getattr(info, "st_file_attributes", 0) & 0x400),
                    "Release paths cannot contain symlinks/junctions")
    return path


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_source_zip(path):
    """Check names and actual key-file headers without extracting anything."""
    private_suffixes = {".aab", ".p12", ".pfx", ".jks", ".keystore", ".key"}
    private_names = {"signing.json", "signing.properties", "key.properties", ".env"}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            name = entry.filename.replace("\\", "/")
            parts = PurePosixPath(name).parts
            require(not name.startswith("/") and not re.match(r"^[A-Za-z]:", name) and ".." not in parts,
                    "Source ZIP contains an unsafe path")
            item = PurePosixPath(name)
            require(item.suffix.lower() not in private_suffixes and item.name.lower() not in private_names,
                    "Source ZIP contains a local-only store/signing artifact")
            require(not any(part.lower() in {"store-upload", ".rg2-mobile-signing"} for part in parts),
                    "Source ZIP contains a private artifact directory")
            mode = entry.external_attr >> 16
            require(not stat.S_ISLNK(mode), "Source ZIP contains a symlink")
            if not entry.is_dir():
                with archive.open(entry) as stream:
                    header = stream.read(4096).lstrip()
                require(not re.match(br"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----", header),
                        "Source ZIP contains private key material")
    return True


def no_bundle_metadata(value):
    if isinstance(value, dict):
        require(not any("aab" in key.lower() for key in value), "Public manifest contains store-upload metadata")
        for child in value.values():
            no_bundle_metadata(child)
    elif isinstance(value, list):
        for child in value:
            no_bundle_metadata(child)
    elif isinstance(value, str):
        require(not re.search(r"\.aab(?:\b|$)", value, re.IGNORECASE), "Public manifest mentions a store-upload bundle")
        require(not re.search(r"[A-Za-z]:[\\/]", value), "Public manifest contains a machine-specific path")


def validate_public_release(directory, version):
    """Return only validated upload paths; call before authentication or network."""
    valid_version(version)
    directory = no_links(directory)
    require(directory.is_dir(), "Public release directory is missing")
    entries = {entry.name: no_links(entry) for entry in directory.iterdir()}
    require(set(entries) <= public_allowlist(version) and all(path.is_file() for path in entries.values()),
            "Public release contains a non-allowlisted artifact")
    apk = f"RG2-Mobile-{version}.apk"
    source = f"RG2-Mobile-{version}-Source.zip"
    require({apk, "manifest.json", "SHA256SUMS.txt"} <= set(entries), "Required public release files are missing")
    if tuple(map(int, version.split("."))) >= (1, 1, 0):
        require(source in entries, "This GPL-enabled release requires its matching source archive")
    manifest = json.loads(entries["manifest.json"].read_text(encoding="utf-8-sig"))
    require(manifest.get("version") == version, "Manifest version differs from the release")
    no_bundle_metadata(manifest)
    files = manifest.get("files")
    require(isinstance(files, list) and files, "Manifest file list is missing")
    expected = {apk, source} & set(entries)
    require(len(files) == len(expected) and {item.get("name") for item in files} == expected,
            "Manifest file list must contain only the APK and existing source archive")
    sums = []
    for item in files:
        require(re.fullmatch(r"[a-f0-9]{64}", item.get("sha256", "")) and type(item.get("bytes")) is int,
                "Manifest has an invalid artifact identity")
        path = entries[item["name"]]
        require(path.stat().st_size == item["bytes"] and sha256(path) == item["sha256"], "Public artifact hash/size differs")
        sums.append(f"{item['sha256']}  {item['name']}")
    actual = entries["SHA256SUMS.txt"].read_text(encoding="ascii").splitlines()
    require(actual == sums, "Public checksums differ from the allowlisted manifest")
    if source in entries:
        validate_source_zip(entries[source])
    return [entries[name] for name in (apk, source, "manifest.json", "SHA256SUMS.txt") if name in entries]
