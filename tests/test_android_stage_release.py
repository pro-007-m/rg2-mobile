"""Pure release-policy checks: temporary files only, no SDK/auth/network/processes."""
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("android_stage_release", Path(__file__).resolve().parents[1] / "tools/android-stage-release.py")
stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage)
policy = stage.policy


def zip_bytes(name="README.md", content=b"Frozen original source"):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr(name, content)
    return stream.getvalue()


class PublicReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rg2-public-policy-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.version = "1.1.0"
        self.directory = self.root / "artifacts/releases" / self.version
        self.directory.mkdir(parents=True)
        self.old_commit = "5492d89b66c249384473ec14d3ded6431adf69bb"
        self.apk = self.directory / "RG2-Mobile-1.1.0.apk"
        self.apk.write_bytes(b"exact frozen APK bytes")
        self.bundle = self.directory / "RG2-Mobile-1.1.0.aab"
        self.bundle.write_bytes(b"local store upload bytes")
        self.source = self.directory / "RG2-Mobile-1.1.0-Source.zip"
        self.source.write_bytes(zip_bytes())
        self.manifest = {
            "schemaVersion": 1, "version": self.version, "versionCode": 3,
            "sourceCommit": self.old_commit, "createdUtc": "2026-11-17T13:34:07Z",
            "signing": {"certificateSha256": "c" * 64, "apkVerified": True, "aabVerified": True},
            "offlineAssets": {"files": 2210, "apkByteForByte": True, "aabByteForByte": True},
            "nativeQa": {"passed": True, "checks": 18},
            "files": [stage.artifact(path) for path in (self.apk, self.bundle, self.source)]}
        self.save_manifest()

    def save_manifest(self):
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.directory / "SHA256SUMS.txt").write_text("".join(f"{item['sha256']}  {item['name']}\n" for item in self.manifest["files"]), encoding="ascii")

    def migrate(self):
        return stage.migrate_existing(self.root, self.version)

    def test_migration_preserves_binary_and_old_source_identity(self):
        before = {path.name: path.read_bytes() for path in (self.apk, self.source, self.bundle)}
        result = self.migrate()
        self.assertEqual(result["sourceCommit"], self.old_commit)
        self.assertEqual(result["createdUtc"], self.manifest["createdUtc"])
        self.assertEqual(self.apk.read_bytes(), before[self.apk.name])
        self.assertEqual(self.source.read_bytes(), before[self.source.name])
        private = self.root / "artifacts/store-upload" / self.version
        self.assertEqual((private / self.bundle.name).read_bytes(), before[self.bundle.name])
        self.assertFalse(self.bundle.exists())
        local = json.loads((private / "local-metadata.json").read_text())
        self.assertEqual(local["visibility"], "local-only")
        self.assertEqual(local["files"][0]["sha256"], hashlib.sha256(before[self.bundle.name]).hexdigest())
        self.assertEqual({p.name for p in policy.validate_public_release(self.directory, self.version)}, policy.public_allowlist(self.version))
        self.assertNotIn("aab", json.dumps(result).lower())
        self.assertNotIn(".aab", (self.directory / "SHA256SUMS.txt").read_text())

    def test_migration_is_idempotent_without_rebuilding_source(self):
        first = self.migrate()
        source = self.source.read_bytes()
        self.assertEqual(self.migrate(), first)
        self.assertEqual(self.source.read_bytes(), source)

    def test_conflicting_private_bundle_fails_without_public_mutation(self):
        private = self.root / "artifacts/store-upload" / self.version
        private.mkdir(parents=True)
        (private / self.bundle.name).write_bytes(b"different bundle; preserve this too")
        original = (self.directory / "manifest.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "different frozen"):
            self.migrate()
        self.assertTrue(self.bundle.exists())
        self.assertEqual((self.directory / "manifest.json").read_bytes(), original)

    def test_corrupted_frozen_apk_or_source_aborts_before_bundle_move(self):
        for path in (self.apk, self.source):
            original = path.read_bytes()
            path.write_bytes(original + b"tampered")
            with self.assertRaisesRegex(ValueError, "identity differs"):
                self.migrate()
            self.assertTrue(self.bundle.exists())
            path.write_bytes(original)

    def test_existing_apk_and_source_are_never_overwritten(self):
        replacement = self.root / "replacement.bin"
        replacement.write_bytes(b"unreviewed new release bytes")
        for destination in (self.apk, self.source):
            before = destination.read_bytes()
            with self.assertRaisesRegex(ValueError, "different frozen"):
                stage.copy_frozen(self.root, replacement, destination)
            self.assertEqual(destination.read_bytes(), before)

    def test_new_head_does_not_replace_pinned_5492_source_archive(self):
        original = self.source.read_bytes()
        calls = []
        def fake_git(root, arguments):
            calls.append(arguments)
            if arguments[0] == "rev-parse":
                return (self.old_commit + "\n").encode() if arguments[2].startswith(self.old_commit) else b"f" * 40
            return original if arguments[-1] == self.old_commit else zip_bytes(content=b"new HEAD source")
        revision, contents = stage.release_source(self.root, self.manifest, fake_git)
        self.assertEqual(revision, self.old_commit)
        self.assertEqual(contents, original)
        self.assertEqual(calls, [["rev-parse", "--verify", self.old_commit + "^{commit}"], ["archive", "--format=zip", self.old_commit]])

    def test_missing_source_pin_cannot_silently_archive_new_head(self):
        del self.manifest["sourceCommit"]
        with self.assertRaisesRegex(ValueError, "no pinned"):
            stage.release_source(self.root, self.manifest, lambda *_: self.fail("Git must not be called"))

    def test_older_release_without_source_zip_is_preserved(self):
        version = "1.0.1"
        directory = self.root / "artifacts/releases" / version
        directory.mkdir()
        apk, bundle = directory / f"RG2-Mobile-{version}.apk", directory / f"RG2-Mobile-{version}.aab"
        apk.write_bytes(b"old APK")
        bundle.write_bytes(b"old local bundle")
        manifest = {"version": version, "signing": {"apkVerified": True, "aabVerified": True},
                    "files": [stage.artifact(apk), stage.artifact(bundle)]}
        (directory / "manifest.json").write_text(json.dumps(manifest))
        result = stage.migrate_existing(self.root, version)
        self.assertNotIn("sourceCommit", result)
        self.assertEqual(len(policy.validate_public_release(directory, version)), 3)

    def test_gpl_release_requires_source_before_migration_or_upload(self):
        result = self.migrate()
        self.source.unlink()
        result["files"] = [item for item in result["files"] if item["name"] != self.source.name]
        (self.directory / "manifest.json").write_text(json.dumps(result))
        with self.assertRaisesRegex(ValueError, "requires.*source"):
            policy.validate_public_release(self.directory, self.version)
        with self.assertRaisesRegex(ValueError, "requires.*source"):
            self.migrate()

    def test_upload_allowlist_rejects_bundle_extra_apk_and_unsafe_version(self):
        self.migrate()
        for name in ("unexpected.aab", "debug.apk", "local-metadata.json"):
            path = self.directory / name
            path.write_bytes(b"not public")
            with self.assertRaisesRegex(ValueError, "non-allowlisted"):
                policy.validate_public_release(self.directory, self.version)
            path.unlink()
        for version in ("../1.1.0", "1.1.0/extra", "1.1.0\n", "latest"):
            with self.assertRaises(ValueError):
                policy.public_allowlist(version)

    def test_public_manifest_and_checksums_cannot_reintroduce_bundle(self):
        result = self.migrate()
        result["signing"]["aabVerified"] = True
        (self.directory / "manifest.json").write_text(json.dumps(result), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "store-upload metadata"):
            policy.validate_public_release(self.directory, self.version)
        del result["signing"]["aabVerified"]
        (self.directory / "manifest.json").write_text(json.dumps(result), encoding="utf-8")
        with (self.directory / "SHA256SUMS.txt").open("a") as stream:
            stream.write("0" * 64 + "  hidden.aab\n")
        with self.assertRaisesRegex(ValueError, "checksums differ"):
            policy.validate_public_release(self.directory, self.version)
        policy.no_bundle_metadata({"sha256": "aab" + "0" * 61})

    def test_source_zip_rejects_store_signing_private_key_and_traversal(self):
        for name, data in [("binary.aab", b"x"), ("private.p12", b"x"), ("keys/release.keystore", b"x"),
                           ("signing.json", b"{}"), (".env", b"PASSWORD=x"), ("../escape.txt", b"x"),
                           ("C:/escape.txt", b"x"), ("certificate.pem", b"-----BEGIN PRIVATE KEY-----\nsecret")]:
            self.source.write_bytes(zip_bytes(name, data))
            with self.subTest(name=name), self.assertRaises(ValueError):
                policy.validate_source_zip(self.source)
        self.source.write_bytes(zip_bytes("android/release-certificate.sha256", b"c" * 64))
        self.assertTrue(policy.validate_source_zip(self.source))

    def test_paths_outside_repository_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "escapes"):
            stage.owned(self.root, self.root.parent / "not-this-qa-folder" / "bundle.aab")

    def test_full_staging_audits_bundle_privately_and_uploads_only_public_files(self):
        apk = self.root / "android/app/build/outputs/apk/release/app-release.apk"
        bundle = self.root / "android/app/build/outputs/bundle/release/app-release.aab"
        apk.parent.mkdir(parents=True)
        bundle.parent.mkdir(parents=True)
        apk.write_bytes(self.apk.read_bytes())
        bundle.write_bytes(self.bundle.read_bytes())
        (self.root / "android/app/build.gradle").write_text("versionName '1.1.0'\nversionCode 3\n")
        (self.root / "android/release-certificate.sha256").write_text("c" * 64)
        verification = {"version": self.version, "certificateSha256": "c" * 64,
                        "apkVerified": True, "aabVerified": True,
                        "files": [stage.artifact(apk), stage.artifact(bundle)]}
        (self.root / "android/app/build/outputs/build-verification.json").write_text(json.dumps(verification))
        data = self.root / "data"
        data.mkdir()
        (data / "original.txt").write_text("offline data")
        required = ["virtual-game-boot", "virtual-nxn50", "virtual-polyhedra", "virtual-ortega-worker", "virtual-native-touch-and-restore"]
        qa = {"versionName": self.version, "versionCode": 3, "passed": True, "api": 35, "durationMs": 30000,
              "checks": [{"name": name, "passed": True} for name in [*[f"base-{i}" for i in range(13)], *required]],
              "virtualPuzzlesRequested": True, "virtualDatabaseRestored": True,
              "installedTarget": {"installedApkSha256": stage.sha256(apk), "signerSha256": ["c" * 64],
                                  "buildType": "release", "releaseGateRequired": True, "debuggable": False, "testOnly": False}}
        qa_path = self.root / "qa.json"
        qa_path.write_text(json.dumps(qa))
        original_zip = self.source.read_bytes()
        audit = SimpleNamespace(stdout=json.dumps({"passed": True, "expectedAssets": 1}))
        with patch.object(stage, "git_bytes", return_value=b""), \
             patch.object(stage, "release_source", return_value=(self.old_commit, original_zip)), \
             patch.object(stage.subprocess, "run", return_value=audit) as run_audit:
            result = stage.stage(self.root, qa_path)
        self.assertEqual(run_audit.call_count, 2, "APK and private bundle were both audited")
        self.assertEqual(result["sourceCommit"], self.old_commit)
        self.assertEqual(self.source.read_bytes(), original_zip)
        self.assertEqual({path.name for path in policy.validate_public_release(self.directory, self.version)}, policy.public_allowlist(self.version))
        self.assertEqual({item["name"] for item in result["files"]}, {self.apk.name, self.source.name})
        self.assertTrue((self.root / "artifacts/store-upload" / self.version / "app-release.aab").is_file())


if __name__ == "__main__":
    unittest.main()
