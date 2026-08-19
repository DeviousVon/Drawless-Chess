#!/usr/bin/env python3
"""Unit and policy tests for the inclusion-only public-source publisher."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = ROOT / "scripts" / "source-bundle.py"
SPEC = importlib.util.spec_from_file_location("drawless_source_bundle", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
publisher = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = publisher
SPEC.loader.exec_module(publisher)


def load_policy():
    public_source = ROOT / "release" / "public-source"
    policy = publisher.LiteralPolicy.from_bytes(
        (public_source / "include-paths.txt").read_bytes(),
        (public_source / "forbidden-paths.txt").read_bytes(),
    )
    private_policy = ROOT / "release" / "private-source-content.sha256"
    private_repository_markers_present = (ROOT / "AGENTS.md").is_file() or (
        ROOT / ".forgejo"
    ).is_dir()
    private_policy_data = None
    if private_repository_markers_present:
        tracked = subprocess.run(
            ["git", "ls-files", "--error-unmatch", "release/private-source-content.sha256"],
            cwd=ROOT,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if tracked.returncode != 0:
            raise RuntimeError("private content policy is not committed")
        if not private_policy.is_file() or private_policy.is_symlink():
            raise RuntimeError("private content policy is missing or unsafe")
        private_policy_data = private_policy.read_bytes()
    scanner = publisher.content_scanner_from_policy_data(
        (public_source / "forbidden-content.sha256").read_bytes(),
        private_policy_data,
        require_private=private_repository_markers_present,
    )
    return policy, scanner


def repository_paths() -> list[str]:
    tracked = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, check=True, stdout=subprocess.PIPE
    ).stdout
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    return sorted(
        item.decode("utf-8") for item in (tracked + untracked).split(b"\0") if item
    )


def add_manifest(entries: dict[str, object]) -> None:
    manifest = b"".join(
        hashlib.sha256(entry.data).hexdigest().encode("ascii")
        + b"  "
        + path.encode("utf-8")
        + b"\n"
        for path, entry in sorted(entries.items())
    )
    entries["SOURCE-MANIFEST.sha256"] = publisher.ArchiveEntry(manifest)
    entries["SOURCE-MANIFEST.sha256.digest"] = publisher.ArchiveEntry(
        hashlib.sha256(manifest).hexdigest().encode("ascii") + b"\n"
    )


def validate_release_channel_scanner_product_pair(executable: Path) -> None:
    if not executable.is_file() or executable.is_symlink():
        raise RuntimeError(
            f"release-channel scanner product is missing, non-regular, or linked: {executable}"
        )
    original_digest = hashlib.sha256(executable.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(
        prefix="drawless-release-channel-stripped-"
    ) as temporary:
        stripped = Path(temporary) / "Drawless Chess"
        shutil.copy2(executable, stripped)
        subprocess.run(
            ["/usr/bin/strip", "-Sx", str(stripped)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        for candidate in (executable, stripped):
            subprocess.run(
                [
                    "bash",
                    "scripts/inspect-ios-release-app.sh",
                    "--release-channel-scanner-self-test",
                    str(candidate),
                ],
                cwd=ROOT,
                check=True,
            )
    if hashlib.sha256(executable.read_bytes()).hexdigest() != original_digest:
        raise RuntimeError("release-channel scanner altered the exact Release product")


class PolicyTests(unittest.TestCase):
    def test_every_repository_path_is_classified(self):
        policy, _ = load_policy()
        unclassified = [
            path for path in repository_paths() if policy.classification(path) == "unclassified"
        ]
        self.assertEqual([], unclassified)

    def test_deny_rules_override_broad_inclusions(self):
        policy, _ = load_policy()
        for path in (
            "AGENTS.md",
            ".forgejo/PULL_REQUEST_TEMPLATE.md",
            "docs/internal/index.md",
            "docs/portal/app-store.md",
            "codex-skills/example/SKILL.md",
            "docs/PLAY_RELEASE_GUIDE.md",
            "play/listing.md",
            "release/private-source-content.sha256",
            "website/app/page.tsx",
        ):
            self.assertEqual("forbidden", policy.classification(path), path)

    def test_included_paths_have_no_hard_deny_violation(self):
        policy, _ = load_policy()
        violations = {
            path: publisher.hard_forbidden_reason(path)
            for path in repository_paths()
            if policy.classification(path) == "included"
            and publisher.hard_forbidden_reason(path)
        }
        self.assertEqual({}, violations)

    def test_hard_path_poison_is_rejected(self):
        for path in (
            "nested/AGENTS.md",
            "nested/.git/config",
            "nested/.github/workflows/release.yml",
            "output/build/product.bin",
            "signing/AuthKey_example.p8",
            "signing/release.mobileprovision",
            "output/release.aab",
            "output/Drawless Chess.app",
            "../escape",
            "nested/control\x01.txt",
            "nested/trailing ",
            "nested/CON.txt",
        ):
            if path in ("../escape", "nested/control\x01.txt", "nested/trailing ", "nested/CON.txt"):
                with self.assertRaises(publisher.BundleError):
                    publisher.validate_relative_path(path)
            else:
                self.assertIsNotNone(publisher.hard_forbidden_reason(path), path)

    def test_all_included_worktree_content_passes_private_scan(self):
        policy, scanner = load_policy()
        for relative in repository_paths():
            if policy.classification(relative) != "included":
                continue
            path = ROOT / relative
            if path.is_file() and not path.is_symlink():
                scanner.scan(relative, path.read_bytes())


class ContentPoisonTests(unittest.TestCase):
    def setUp(self):
        _, self.scanner = load_policy()

    def assert_poison(self, content: bytes):
        with self.assertRaises(publisher.BundleError):
            self.scanner.scan("fixture.txt", content)

    def test_public_policy_synthetic_fixture_is_rejected(self):
        synthetic_fixture = ("drawless-public" + "-sanitizer-fixture").encode()
        self.assert_poison(b"prefix " + synthetic_fixture + b" suffix")

    def test_private_policy_requirement_fails_closed(self):
        with self.assertRaises(publisher.BundleError):
            publisher.content_scanner_from_policy_data(
                (ROOT / "release/public-source/forbidden-content.sha256").read_bytes(),
                None,
                require_private=True,
            )

    def test_secret_shapes_are_rejected(self):
        self.assert_poison(b"-----BEGIN " + b"PRIVATE" + b" KEY-----\npoison")
        self.assert_poison(b"DRAWLESS_UPLOAD_" + b"STORE_PASSWORD=poison")
        self.assert_poison(b"store" + b"Password=poison")
        self.assert_poison(b"issue" + b"comment-123")
        self.assert_poison(b"agent/" + b"codex-example")
        self.assert_poison(b"/" + b"Users/example/project")
        self.assert_poison(b"github_" + b"pat_example")
        self.assert_poison(b"AKIA" + b"1234567890ABCDEF")

    def test_signing_example_sentinel_is_allowed(self):
        self.scanner.scan("android/signing.properties.example", b"storePassword=replace-locally\n")


class ManifestAndArchiveTests(unittest.TestCase):
    def test_manifest_is_complete_and_tamper_evident(self):
        entries = {
            "one.txt": publisher.ArchiveEntry(b"one\n"),
            "nested/two.sh": publisher.ArchiveEntry(b"two\n", 0o755),
        }
        add_manifest(entries)
        publisher.verify_manifest(entries)
        entries["one.txt"] = publisher.ArchiveEntry(b"changed\n")
        with self.assertRaises(publisher.BundleError):
            publisher.verify_manifest(entries)

    def test_archive_bytes_ignore_root_location_umask_and_timezone(self):
        entries = {
            "a.txt": publisher.ArchiveEntry(b"alpha\n"),
            "nested/b.sh": publisher.ArchiveEntry(b"beta\n", 0o755),
        }
        with tempfile.TemporaryDirectory() as first_root, tempfile.TemporaryDirectory() as second_root:
            first = Path(first_root) / "fixture.tar.gz"
            second = Path(second_root) / "fixture.tar.gz"
            old_timezone = os.environ.get("TZ")
            old_umask = os.umask(0o077)
            try:
                os.environ["TZ"] = "UTC"
                publisher.write_archive(first, "fixture-root", entries)
                os.umask(0o022)
                os.environ["TZ"] = "Pacific/Honolulu"
                publisher.write_archive(second, "fixture-root", entries)
            finally:
                os.umask(old_umask)
                if old_timezone is None:
                    os.environ.pop("TZ", None)
                else:
                    os.environ["TZ"] = old_timezone
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_archive_links_fail_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "unsafe.tar.gz"
            with archive_path.open("wb") as raw:
                with gzip.GzipFile(filename="", mode="wb", mtime=0, fileobj=raw) as zipped:
                    with tarfile.open(fileobj=zipped, mode="w") as archive:
                        root = tarfile.TarInfo("unsafe-root")
                        root.type = tarfile.DIRTYPE
                        root.mode = 0o755
                        archive.addfile(root)
                        link = tarfile.TarInfo("unsafe-root/link")
                        link.type = tarfile.SYMTYPE
                        link.linkname = "../escape"
                        archive.addfile(link)
            with self.assertRaises(publisher.BundleError):
                publisher.inspect_archive(archive_path, expected_root="unsafe-root")

    def test_archive_traversal_fails_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "unsafe.tar.gz"
            with archive_path.open("wb") as raw:
                with gzip.GzipFile(filename="", mode="wb", mtime=0, fileobj=raw) as zipped:
                    with tarfile.open(fileobj=zipped, mode="w") as archive:
                        root = tarfile.TarInfo("unsafe-root")
                        root.type = tarfile.DIRTYPE
                        root.mode = 0o755
                        archive.addfile(root)
                        poison = tarfile.TarInfo("unsafe-root/../escape")
                        poison.mode = 0o644
                        poison.size = len(b"poison")
                        archive.addfile(poison, io.BytesIO(b"poison"))
            with self.assertRaises(publisher.BundleError):
                publisher.inspect_archive(archive_path, expected_root="unsafe-root")

    def test_archive_trailing_gzip_data_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "trailing.tar.gz"
            publisher.write_archive(
                archive_path,
                "fixture-root",
                {"file.txt": publisher.ArchiveEntry(b"fixture\n")},
            )
            with archive_path.open("ab") as archive:
                archive.write(b"trailing-poison")
            with self.assertRaises(publisher.BundleError):
                publisher.inspect_archive(archive_path, expected_root="fixture-root")

    def test_archive_forbidden_or_empty_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "empty-directory.tar.gz"
            with archive_path.open("wb") as raw:
                with gzip.GzipFile(filename="", mode="wb", mtime=0, fileobj=raw) as zipped:
                    with tarfile.open(fileobj=zipped, mode="w") as archive:
                        for name in ("fixture-root", "fixture-root/.forgejo"):
                            directory = tarfile.TarInfo(name)
                            directory.type = tarfile.DIRTYPE
                            directory.mode = 0o755
                            archive.addfile(directory)
                        file_entry = tarfile.TarInfo("fixture-root/file.txt")
                        file_entry.mode = 0o644
                        file_entry.size = len(b"fixture\n")
                        archive.addfile(file_entry, io.BytesIO(b"fixture\n"))
            with self.assertRaises(publisher.BundleError):
                publisher.inspect_archive(archive_path, expected_root="fixture-root")


class NativeAndRebuildContractTests(unittest.TestCase):
    def supplied_native_source(self):
        supplied = os.environ.get("DRAWLESS_TEST_NATIVE_SOURCE")
        if not supplied:
            self.skipTest("DRAWLESS_TEST_NATIVE_SOURCE was not supplied")
        return Path(supplied)

    def test_real_prepared_native_tree_when_supplied(self):
        native_source = self.supplied_native_source()
        lock = publisher.parse_properties(
            (ROOT / "engine" / "native" / "upstream.properties").read_bytes(), "native lock"
        )
        publisher.validate_native_source(ROOT, native_source, lock)
        entries: dict[str, object] = {}
        publisher.add_native_entries(entries, native_source, lock)
        self.assertIn("engine/native/upstream/Fairy-Stockfish/Copying.txt", entries)
        self.assertIn("engine/native/archive-fairy-source.sha256", entries)
        native_manifest = entries["engine/native/archive-fairy-source.sha256"].data
        self.assertTrue(native_manifest)
        self.assertTrue(
            all(b"  ./" in row for row in native_manifest.splitlines()),
            "native archive manifest rows must use the ./path format required by rebuild validators",
        )
        self.assertFalse(any("/.github/" in f"/{path}/" for path in entries))
        self.assertFalse(any(path.casefold().endswith("/agents.md") for path in entries))
        _, scanner = load_policy()
        for path, entry in entries.items():
            scanner.scan(path, entry.data)

    def test_complete_worktree_fixture_archive_when_native_is_supplied(self):
        native_source = self.supplied_native_source()
        policy, scanner = load_policy()
        entries = {}
        for relative in repository_paths():
            if policy.classification(relative) != "included":
                continue
            path = ROOT / relative
            if not path.is_file() or path.is_symlink():
                continue
            mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
            entries[relative] = publisher.ArchiveEntry(path.read_bytes(), mode)
        identity = publisher.release_identity(entries)
        lock = publisher.parse_properties(
            entries["engine/native/upstream.properties"].data, "native lock"
        )
        publisher.validate_native_source(ROOT, native_source, lock)
        publisher.add_native_entries(entries, native_source, lock)
        publisher.add_generated_release_files(entries, identity, lock)
        publisher.validate_entry_set(entries, policy, scanner)
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / identity.archive_name
            first_digest = publisher.reproducibility_build(
                archive, identity.root_name, entries
            )
            self.assertEqual(first_digest, hashlib.sha256(archive.read_bytes()).hexdigest())
            root_name, inspected = publisher.inspect_archive(
                archive, expected_root=identity.root_name
            )
            self.assertEqual(identity.root_name, root_name)
            self.assertIn("SOURCE-IDENTITY", inspected)
            extracted = Path(temporary) / "extracted" / identity.root_name
            for relative, entry in inspected.items():
                target = extracted / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(entry.data)
                target.chmod(entry.mode)
            subprocess.run(
                ["bash", "scripts/native-validate-structure.sh", "--require-source"],
                cwd=extracted,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

    def test_rebuild_and_inspector_contract_is_explicit(self):
        rebuild = (ROOT / "release" / "public-source" / "REBUILD-IOS.md").read_text()
        inspector = (ROOT / "scripts" / "inspect-ios-release-app.sh").read_text()
        gate = Path(__file__).read_text()
        publisher_source = (ROOT / "scripts" / "source-bundle.py").read_text()
        for required in (
            "scripts/native-validate-structure.sh --require-source",
            "npm run test:kmp:apple",
            "npm run test:native-patch",
            "scripts/generate-ios-project.sh",
            "scripts/build-ios-engine.sh",
            "CODE_SIGNING_ALLOWED=NO",
            "--unsigned-rebuild",
        ):
            self.assertIn(required, rebuild)
        self.assertIn("inspection_mode=signed", inspector)
        self.assertIn("signing_mode=unsigned-rebuild", inspector)
        self.assertIn("DRAWLESS_EXPECTED_APPLE_TEAM_ID", inspector)
        self.assertIn(
            "DRAWLESS_EXPECTED_APPLE_DEVELOPMENT_CERTIFICATE_SHA1", inspector
        )
        for required in (
            '"test:kmp:apple"',
            '"test:native-patch"',
            '"scripts/build-ios-engine.sh"',
            '"iphonesimulator"',
            '"ARCHS=arm64"',
            '"iphoneos"',
            '"CODE_SIGNING_ALLOWED=NO"',
            '"--unsigned-rebuild"',
        ):
            self.assertIn(required, gate)
        self.assertIn('str(gate), "--release-gate"', publisher_source)
        self.assertIn("--manifest-digest", publisher_source)
        scanner_result = subprocess.run(
            [
                "bash",
                "scripts/inspect-ios-release-app.sh",
                "--release-channel-scanner-self-test",
            ],
            cwd=ROOT,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.assertIn("release-channel scanner self-test PASS", scanner_result.stdout)
        for required in (
            "otool -tvV",
            "<__text>",
            "bl 0x100003000",
            "ret-separated",
            "key-outside-window",
            "raw-path-only",
            "appended-bytes-only",
            "debug-metadata-only",
            "arm64e",
            "universal",
            "core-and-private",
        ):
            self.assertIn(required, inspector)
        self.assertIn("ARM64 Swift small-string", inspector)
        self.assertIn(
            "release_channel_evidence=arm64-swift-small-string", inspector
        )
        self.assertIn('["/usr/bin/strip", "-Sx", str(stripped)]', gate)
        self.assertIn("validate_release_channel_scanner_product_pair", gate)

    def test_archive_creation_cannot_continue_past_a_failed_release_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = (
                Path(temporary)
                / "drawless-chess-ios-1.0.2-build-2-source.tar.gz"
            )
            with (
                mock.patch.object(publisher, "validate_clean_repository"),
                mock.patch.object(
                    publisher,
                    "run_release_rebuild_gate",
                    side_effect=publisher.BundleError("release gate poison"),
                ) as gate,
                mock.patch.object(publisher, "prepare_bundle_entries") as prepare,
                mock.patch("sys.stdout", new=io.StringIO()),
                mock.patch("sys.stderr", new=io.StringIO()) as stderr,
            ):
                result = publisher.main(
                    [
                        "--repository-root",
                        str(ROOT),
                        "--output",
                        str(output),
                    ]
                )
            self.assertNotEqual(0, result)
            gate.assert_called_once_with(ROOT.resolve())
            prepare.assert_not_called()
            self.assertIn("release gate poison", stderr.getvalue())
            self.assertFalse(output.exists())


def run_release_gate() -> int:
    native_source = ROOT / "engine" / "native" / "upstream" / "Fairy-Stockfish"
    os.environ["DRAWLESS_TEST_NATIVE_SOURCE"] = str(native_source)
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        return 1

    def checked(*command: str) -> None:
        print("release-rebuild:", " ".join(command), flush=True)
        subprocess.run(command, cwd=ROOT, check=True)

    checked("bash", "scripts/native-validate-structure.sh", "--require-source")
    checked("npm", "test")
    checked("npm", "run", "test:kotlin")
    checked("npm", "run", "test:ios-structure")
    checked("npm", "run", "test:localization")
    checked("npm", "run", "test:kmp")
    checked("npm", "run", "test:kmp:apple")
    checked("npm", "run", "test:audio")
    checked("npm", "run", "test:license")
    checked("npm", "run", "test:native-source")
    checked("npm", "run", "test:native-patch")
    checked("bash", "scripts/generate-ios-project.sh")
    checked("git", "diff", "--exit-code", "--", "iosApp/DrawlessChess.xcodeproj")

    generated_roots = (
        ROOT / "build" / "ios-engine",
        ROOT / "build" / "public-source-release-simulator",
        ROOT / "build" / "public-source-release-device",
    )
    for generated_root in generated_roots:
        if generated_root.exists():
            shutil.rmtree(generated_root)

    checked("bash", "scripts/build-ios-engine.sh")
    checked(
        "xcodebuild",
        "-quiet",
        "-project",
        "iosApp/DrawlessChess.xcodeproj",
        "-scheme",
        "DrawlessChess",
        "-configuration",
        "Release",
        "-sdk",
        "iphonesimulator",
        "-destination",
        "generic/platform=iOS Simulator",
        "-derivedDataPath",
        "build/public-source-release-simulator",
        "ARCHS=arm64",
        "CODE_SIGNING_ALLOWED=NO",
        "CODE_SIGNING_REQUIRED=NO",
        "build",
    )
    simulator_executable = (
        ROOT
        / "build"
        / "public-source-release-simulator"
        / "Build"
        / "Products"
        / "Release-iphonesimulator"
        / "Drawless Chess.app"
        / "Drawless Chess"
    )
    print(
        "release-rebuild: release-channel scanner unstripped/strip-Sx simulator",
        flush=True,
    )
    validate_release_channel_scanner_product_pair(simulator_executable)
    checked(
        "scripts/inspect-ios-release-app.sh",
        "--unsigned-rebuild",
        "build/public-source-release-simulator/Build/Products/Release-iphonesimulator/Drawless Chess.app",
    )
    checked(
        "xcodebuild",
        "-quiet",
        "-project",
        "iosApp/DrawlessChess.xcodeproj",
        "-scheme",
        "DrawlessChess",
        "-configuration",
        "Release",
        "-sdk",
        "iphoneos",
        "-destination",
        "generic/platform=iOS",
        "-derivedDataPath",
        "build/public-source-release-device",
        "CODE_SIGNING_ALLOWED=NO",
        "CODE_SIGNING_REQUIRED=NO",
        "build",
    )
    device_executable = (
        ROOT
        / "build"
        / "public-source-release-device"
        / "Build"
        / "Products"
        / "Release-iphoneos"
        / "Drawless Chess.app"
        / "Drawless Chess"
    )
    print(
        "release-rebuild: release-channel scanner unstripped/strip-Sx device",
        flush=True,
    )
    validate_release_channel_scanner_product_pair(device_executable)
    checked(
        "scripts/inspect-ios-release-app.sh",
        "--unsigned-rebuild",
        "build/public-source-release-device/Build/Products/Release-iphoneos/Drawless Chess.app",
    )
    checked("git", "diff", "--exit-code", "--", "iosApp/DrawlessChess.xcodeproj")
    print("public-source release rebuild gate PASS")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--release-gate"]:
        raise SystemExit(run_release_gate())
    unittest.main(verbosity=2)
