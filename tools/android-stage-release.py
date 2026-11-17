"""Stage a public APK/source release; store-upload bundles stay local only."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('rg2_public_release_policy', Path(__file__).with_name('public-release-policy.py'))
policy = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(policy)
require = policy.require
sha256 = policy.sha256


def owned(root, path):
    path = Path(path).absolute()
    require(path.is_relative_to(root.resolve()), 'Artifact path escapes the repository')
    policy.no_links(path)
    require(path.resolve().is_relative_to(root.resolve()), 'Resolved artifact path escapes the repository')
    return path


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def artifact(path):
    return {'name': path.name, 'bytes': path.stat().st_size, 'sha256': sha256(path)}


def verify(root, directory, item):
    name = item.get('name', '')
    require(Path(name).name == name and '/' not in name and '\\' not in name, 'Invalid artifact name')
    require(re.fullmatch(r'[a-f0-9]{64}', item.get('sha256', '')) and type(item.get('bytes')) is int,
            'Invalid frozen artifact identity')
    path = owned(root, directory / name)
    require(path.is_file() and path.stat().st_size == item['bytes'] and sha256(path) == item['sha256'],
            'Frozen artifact identity differs: ' + name)
    return path


def atomic_text(root, path, text, encoding='utf-8'):
    path = owned(root, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding=encoding, newline='\n', dir=path.parent,
                                     prefix='.metadata-', delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(text)
    try:
        os.replace(owned(root, temporary), owned(root, path))
    finally:
        if temporary.exists():
            owned(root, temporary).unlink()


def write_json(root, path, value):
    atomic_text(root, path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def copy_frozen(root, source, destination):
    source, destination = owned(root, source), owned(root, destination)
    identity = artifact(source)
    if destination.exists():
        require(destination.is_file() and destination.stat().st_size == identity['bytes'] and
                sha256(destination) == identity['sha256'], 'Refusing to replace a different frozen release artifact')
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with source.open('rb') as incoming, destination.open('xb') as outgoing:
            shutil.copyfileobj(incoming, outgoing)
    require(destination.stat().st_size == identity['bytes'] and sha256(destination) == identity['sha256'],
            'Frozen artifact copy verification failed')
    return artifact(destination)


def inspect_existing(root, version):
    policy.valid_version(version)
    target = owned(root, root / 'artifacts/releases' / version)
    if not target.exists():
        return target, None, []
    bundles = []
    for entry in target.iterdir():
        entry = owned(root, entry)
        require(entry.is_file(), 'Public release must contain only flat files')
        if entry.suffix.lower() == '.aab':
            bundles.append(entry)
        else:
            require(entry.name in policy.public_allowlist(version), 'Unexpected public artifact: ' + entry.name)
    manifest_path = target / 'manifest.json'
    if not manifest_path.exists():
        require(not any(target.iterdir()), 'Nonempty public release has no manifest')
        return target, None, bundles
    manifest = load(owned(root, manifest_path))
    require(manifest.get('version') == version and isinstance(manifest.get('files'), list), 'Invalid existing release manifest')
    seen = set()
    private = owned(root, root / 'artifacts/store-upload' / version)
    public_artifacts = {f'{policy.artifact_base(version)}.apk', f'{policy.artifact_base(version)}-Source.zip'}
    for item in manifest['files']:
        name = item.get('name', '')
        require(name not in seen and (name in public_artifacts or Path(name).suffix.lower() == '.aab'),
                'Unexpected or duplicate frozen artifact')
        seen.add(name)
        directory = private if Path(name).suffix.lower() == '.aab' and not (target / name).exists() else target
        verify(root, directory, item)
    require(f'{policy.artifact_base(version)}.apk' in seen, 'Existing manifest does not pin its APK')
    require(all(not (target / name).exists() or name in seen for name in public_artifacts), 'Unpinned frozen public artifact')
    if tuple(map(int, version.split('.'))) >= (1, 1, 0):
        require(f'{policy.artifact_base(version)}-Source.zip' in seen, 'This GPL-enabled release requires its matching source archive')
    return target, manifest, bundles


def stash_bundles(root, version, bundles, metadata=None):
    private = owned(root, root / 'artifacts/store-upload' / version)
    local = owned(root, private / 'local-metadata.json')
    previous = load(local) if local.exists() else {'schemaVersion': 1, 'version': version, 'visibility': 'local-only', 'files': []}
    require(previous.get('version') == version and previous.get('visibility') == 'local-only', 'Conflicting local bundle metadata')
    records = {item['name']: item for item in previous['files']}
    for source in bundles:
        source = owned(root, source)
        require(source.suffix.lower() == '.aab', 'Only store-upload bundles belong in local staging')
        item = copy_frozen(root, source, private / source.name)
        require(source.name not in records or records[source.name] == item, 'Conflicting local frozen bundle identity')
        records[source.name] = item
    previous['files'] = list(records.values())
    if metadata:
        previous['verification'] = metadata
    if bundles or local.exists():
        write_json(root, local, previous)
    return private


def public_projection(manifest):
    result = json.loads(json.dumps(manifest))
    version = policy.valid_version(result['version'])
    result['files'] = [item for item in result['files'] if item['name'] in
                       {f'{policy.artifact_base(version)}.apk', f'{policy.artifact_base(version)}-Source.zip'}]
    for section in ('signing', 'offlineAssets'):
        result[section] = {key: value for key, value in result.get(section, {}).items() if 'aab' not in key.lower()}
    policy.no_bundle_metadata(result)
    return result


def finish_public(root, version, manifest, bundles, metadata=None):
    target = owned(root, root / 'artifacts/releases' / version)
    projected = public_projection(manifest)
    for item in projected['files']:
        verify(root, target, item)
    require(any(item['name'] == f'{policy.artifact_base(version)}.apk' for item in projected['files']), 'Public APK is missing')
    source = target / f'{policy.artifact_base(version)}-Source.zip'
    if source.exists():
        policy.validate_source_zip(source)
    private = stash_bundles(root, version, bundles, metadata)
    for bundle in bundles:
        bundle = owned(root, bundle)
        expected = artifact(bundle)
        verify(root, private, expected)
        # Only this exact, absolute, hash-verified old public file is removed.
        owned(root, bundle).unlink()
    write_json(root, target / 'manifest.json', projected)
    atomic_text(root, target / 'SHA256SUMS.txt', ''.join(f"{item['sha256']}  {item['name']}\n" for item in projected['files']), 'ascii')
    policy.validate_public_release(target, version)
    return projected


def migrate_existing(root, version):
    target, manifest, bundles = inspect_existing(root, version)
    require(manifest is not None, 'There is no frozen public release to migrate')
    # No build/Git/QA rerun: old APK, source ZIP and sourceCommit remain unchanged.
    return finish_public(root, version, manifest, bundles, {'previousReleaseManifest': manifest})


def git_bytes(root, arguments):
    return subprocess.check_output(['git', '-c', 'safe.directory=' + root.as_posix(), '-C', str(root), *arguments])


def release_source(root, existing, run_git=git_bytes):
    pinned = existing.get('sourceCommit') if existing else None
    if existing and any(item['name'].endswith('-Source.zip') for item in existing['files']):
        require(pinned is not None, 'Existing source archive has no pinned source commit')
    require(pinned is None or re.fullmatch(r'[a-fA-F0-9]{7,40}', pinned), 'Invalid source commit')
    revision = run_git(root, ['rev-parse', '--verify', (pinned or 'HEAD') + '^{commit}']).decode().strip()
    require(re.fullmatch(r'[a-fA-F0-9]{40}', revision), 'Cannot identify source revision')
    return revision, run_git(root, ['archive', '--format=zip', revision])


def stage(root, qa_report):
    gradle = (root / 'android/app/build.gradle').read_text(encoding='utf-8')
    version = re.search(r"versionName\s+'(\d+\.\d+\.\d+)'", gradle)[1]
    code = int(re.search(r'versionCode\s+(\d+)', gradle)[1])
    verification = load(root / 'android/app/build/outputs/build-verification.json')
    pin = (root / 'android/release-certificate.sha256').read_text().strip()
    require(verification['apkVerified'] and verification['certificateSha256'] == pin and verification['version'] == version,
            'Release verification does not match the canonical APK')
    qa = load(qa_report)
    if version != '1.0.0' or 'versionName' in qa:
        require(qa['versionName'] == version and qa['versionCode'] == code, 'QA version differs')
    require(qa['passed'] and qa['checks'] and all(check['passed'] for check in qa['checks']), 'Native QA failed')
    identity, apk = qa.get('installedTarget', {}), root / 'android/app/build/outputs/apk/release/app-release.apk'
    require(identity.get('installedApkSha256') == sha256(apk), 'QA APK identity differs from staged artifact')
    require(identity.get('signerSha256') == [pin] and identity.get('buildType') == 'release' and identity.get('releaseGateRequired') is True and
            identity.get('debuggable') is False and identity.get('testOnly') is False, 'QA target is not the canonical Release APK')
    if tuple(map(int, version.split('.'))) >= (1, 1, 0):
        required = {'virtual-game-boot', 'virtual-nxn50', 'virtual-polyhedra', 'virtual-ortega-worker', 'virtual-native-touch-and-restore'}
        review = tuple(map(int, version.split('.'))) >= (1, 2, 0)
        if review:
            required |= {'review-developer-unlock', 'review-edit-preview-persistence', 'review-native-document-roundtrip'}
        require(len(qa['checks']) == (21 if review else 18) and all(sum(check.get('name') == name for check in qa['checks']) == 1 for name in required) and
                qa.get('virtualPuzzlesRequested') is True and qa.get('virtualDatabaseRestored') is True, 'Required virtual puzzle QA is missing')
        if review:
            require(qa.get('reviewModeRequested') is True and qa.get('reviewDatabaseRestored') is True, 'Required review mode QA is missing')
    if tuple(map(int, version.split('.'))) >= (1, 2, 1):
        icon_qa = qa.get('launcherIcons', {})
        require(qa.get('launcherIconsRequested') is True and icon_qa.get('passed') is True and
                icon_qa.get('actualInstalledAdaptiveResource') is True and icon_qa.get('backgroundCount') == 1 and
                icon_qa.get('foregroundCornersTransparent') is True and icon_qa.get('circleClippedPixels') == 0 and
                icon_qa.get('squircleClippedPixels') == 0 and icon_qa.get('monochromeAvailable') is True,
                'Required installed launcher icon QA is missing')
    target, existing, old_bundles = inspect_existing(root, version)
    require(not git_bytes(root, ['status', '--porcelain']).strip(), 'Commit release sources before staging')
    revision, source = release_source(root, existing)
    archives = [apk]
    bundle = root / 'android/app/build/outputs/bundle/release/app-release.aab'
    if bundle.exists():
        require(verification.get('aabVerified'), 'Local store-upload bundle is not verified')
        archives.append(bundle)
    audits = []
    for archive in archives:
        verify(root, archive.parent, next(item for item in verification['files'] if item['name'] == archive.name))
        run = subprocess.run([sys.executable, str(root / 'tools/android-asset-audit.py'), str(archive)], capture_output=True,
                             text=True, encoding='utf-8', check=True)
        audit = json.loads(run.stdout)
        require(audit['passed'], 'Offline asset audit failed')
        audits.append(audit)
    source_path = owned(root, target / f'{policy.artifact_base(version)}-Source.zip')
    require(not source_path.exists() or sha256(source_path) == hashlib.sha256(source).hexdigest(), 'Existing source differs from pinned release revision')
    if existing:
        pinned = next(item for item in existing['files'] if item['name'] == f'{policy.artifact_base(version)}.apk')
        require(pinned['sha256'] == sha256(apk), 'Refusing to replace a different frozen APK')
    target.mkdir(parents=True, exist_ok=True)
    files = [copy_frozen(root, apk, target / f'{policy.artifact_base(version)}.apk')]
    if not source_path.exists():
        with source_path.open('xb') as stream:
            stream.write(source)
    files.append(artifact(source_path))
    tree = hashlib.sha256()
    for path in sorted((root / 'data').rglob('*'), key=lambda item: item.relative_to(root / 'data').as_posix()):
        if path.is_file():
            tree.update(path.relative_to(root / 'data').as_posix().encode('utf-8') + b'\0')
            tree.update(bytes.fromhex(sha256(path)) + b'\n')
    manifest = {'schemaVersion': 1, 'application': 'TwistForge Mobile', 'applicationId': 'io.github.pro007m.rg2', 'version': version,
                'versionCode': code, 'sourceCommit': revision, 'minSdk': 24, 'targetSdk': 36,
                'createdUtc': existing.get('createdUtc') if existing else datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
                'signing': {'certificateSha256': pin, 'apkVerified': True},
                'offlineAssets': {'files': audits[0]['expectedAssets'], 'sourceTreeSha256': tree.hexdigest(), 'apkByteForByte': True, 'windowsRuntimeFiles': 0},
                'nativeQa': {'passed': True, 'checks': len(qa['checks']), 'api': qa['api'], 'durationMs': qa['durationMs'], 'internetPermission': False,
                             'versionName': qa.get('versionName', version), 'versionCode': qa.get('versionCode', code)}, 'files': files}
    if bundle.exists():
        stash_bundles(root, version, [bundle], {'buildVerification': verification, 'assetAudits': audits[1:]})
    return finish_public(root, version, manifest, old_bundles)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--qa-report', type=Path, default=ROOT.parent / 'mobile-native-qa/passed/offline-smoke-result.json')
    parser.add_argument('--migrate-existing', action='store_true', help='Sanitize frozen local release folders without rebuilding their APK/source')
    args = parser.parse_args()
    if args.migrate_existing:
        releases = owned(ROOT, ROOT / 'artifacts/releases')
        results = [migrate_existing(ROOT, entry.name) for entry in sorted(releases.iterdir())
                   if re.fullmatch(r'\d+\.\d+\.\d+', entry.name)] if releases.exists() else []
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(stage(ROOT, args.qa_report), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
