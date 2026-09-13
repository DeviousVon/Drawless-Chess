#!/usr/bin/env python3
"""Exercise the production Kotlin public-source validator with isolated archives.

Uses DRAWLESS_KOTLINC, kotlinc on PATH, or node_modules/kotlin-compiler/bin/kotlinc.
No Android build, SDK, signer, Git repository, or device is needed.
"""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CONTROLS = {"SOURCE-MANIFEST.sha256", "SOURCE-MANIFEST.sha256.digest"}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PublicReleaseSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_temp = tempfile.TemporaryDirectory(prefix="drawless-source-validator-")
        cls.addClassCleanup(cls.compiler_temp.cleanup)
        directory = Path(cls.compiler_temp.name)
        source = (ROOT / "android/engine/build.gradle.kts").read_text(encoding="utf-8")
        marker = "// BEGIN PUBLIC SOURCE VALIDATOR"
        end = "// END PUBLIC SOURCE VALIDATOR."
        if source.count(marker) != 1 or source.count(end) != 1:
            raise AssertionError("Production validator extraction markers are missing or duplicated")
        validator = source[source.index("object DrawlessPublicSource {", source.index(marker)):source.index(end)]
        imports = "\n".join(line for line in source.splitlines() if line.startswith("import java."))
        harness = directory / "Validator.kt"
        harness.write_text(
            imports + "\n" +
            "class GradleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)\n" +
            validator + "\n" +
            "fun main(args: Array<String>) {\n"
            "    try {\n"
            "        DrawlessPublicSource.validate(File(args.single()))\n"
            "        println(\"VALID\")\n"
            "    } catch (exception: GradleException) {\n"
            "        System.err.println(exception.message)\n"
            "        kotlin.system.exitProcess(2)\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        compiler = os.environ.get("DRAWLESS_KOTLINC") or shutil.which("kotlinc")
        if compiler is None:
            compiler = str(ROOT / "node_modules/kotlin-compiler/bin/kotlinc")
        if not Path(compiler).is_file():
            raise RuntimeError("Set DRAWLESS_KOTLINC to an existing Kotlin compiler executable")
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        if not cls.java:
            raise RuntimeError("A Java runtime is required to test the Kotlin validator")
        cls.jar = directory / "validator.jar"
        result = subprocess.run(
            [compiler, str(harness), "-include-runtime", "-d", str(cls.jar)],
            text=True, capture_output=True, timeout=120,
        )
        if result.returncode:
            raise AssertionError("Production validator does not compile:\n" + result.stdout + result.stderr)

    def setUp(self):
        self.fixture_temp = tempfile.TemporaryDirectory(prefix="drawless-public-source-")
        self.addCleanup(self.fixture_temp.cleanup)
        self.root = Path(self.fixture_temp.name)
        self.identity = {
            "schemaVersion": "1",
            "platform": "Android",
            "applicationId": "com.drawlesschess",
            "version": "1.0.3",
            "build": "7",
            "publicTag": "v1.0.3",
            "archive": "drawless-chess-android-1.0.3-build-7-source.tar.gz",
            "nativeComponent": "Fairy-Stockfish",
            "nativeRevision": "1" * 40,
            "nativeTree": "2" * 40,
            "nativePatchedTree": "3" * 40,
            "nativePatchSeriesSha256": "4" * 64,
        }
        self.write("android/app/build.gradle.kts", '''android {
    defaultConfig {
        applicationId = "com.drawlesschess"
        versionCode = 7
        versionName = "1.0.3"
    }
}
''')
        self.write("engine/native/upstream.properties", "\n".join([
            "revision=" + self.identity["nativeRevision"],
            "tree=" + self.identity["nativeTree"],
            "patchedTree=" + self.identity["nativePatchedTree"],
            "patchSeriesSha256=" + self.identity["nativePatchSeriesSha256"],
        ]) + "\n")
        self.write("README.md", "Public Android source fixture.\n")
        self.write_identity()
        self.seal()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_identity(self):
        self.write("SOURCE-IDENTITY", "".join(f"{key}={value}\n" for key, value in self.identity.items()))

    def seal(self):
        files = sorted(path for path in self.root.rglob("*") if path.is_file() and path.relative_to(self.root).as_posix() not in CONTROLS)
        self.write("SOURCE-MANIFEST.sha256", "".join(
            f"{sha256(path)}  {path.relative_to(self.root).as_posix()}\n" for path in files
        ))
        self.seal_manifest()

    def seal_manifest(self):
        self.write("SOURCE-MANIFEST.sha256.digest", sha256(self.root / "SOURCE-MANIFEST.sha256") + "\n")

    def validate(self, expected=None):
        result = subprocess.run(
            [self.java, "-jar", str(self.jar), str(self.root)],
            text=True, capture_output=True, timeout=20,
        )
        if expected is None:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(result.stdout, "VALID\n")
        else:
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn(expected, result.stderr)

    def test_plain_relative_manifest_is_valid(self):
        self.validate()

    def test_missing_required_controls(self):
        for relative in ["SOURCE-IDENTITY", *sorted(CONTROLS)]:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_bytes()
                path.unlink()
                self.validate("Required public source file is absent")
                path.write_bytes(original)

    def test_altered_source_and_unmanifested_or_missing_files(self):
        path = self.root / "README.md"
        original = path.read_bytes()
        path.write_text("Tampered source\n", encoding="utf-8")
        self.validate("Public source differs from its manifest")
        path.unlink()
        self.validate("Public source file set differs")
        path.write_bytes(original)
        self.write("unexpected.txt", "Unmanifested source\n")
        self.validate("Public source file set differs")

    def test_stale_manifest_digest(self):
        self.write("SOURCE-MANIFEST.sha256.digest", "0" * 64 + "\n")
        self.validate("manifest digest does not match")

    def test_stale_identity_is_rejected_even_with_fresh_manifest(self):
        changes = {
            "schemaVersion": "2", "platform": "iOS", "applicationId": "com.example.other",
            "version": "1.0.2", "build": "6", "publicTag": "v1.0.2",
            "archive": "other-source.tar.gz", "nativeComponent": "Other",
            "nativeRevision": "5" * 40, "nativeTree": "6" * 40,
            "nativePatchedTree": "7" * 40, "nativePatchSeriesSha256": "8" * 64,
        }
        for key, wrong in changes.items():
            with self.subTest(key=key):
                original = self.identity[key]
                self.identity[key] = wrong
                self.write_identity()
                self.seal()
                self.validate(f"SOURCE-IDENTITY '{key}' does not match")
                self.identity[key] = original

    def test_changed_version_declarations_and_native_lock(self):
        app = self.root / "android/app/build.gradle.kts"
        original = app.read_text(encoding="utf-8")
        for before, after in [('versionName = "1.0.3"', 'versionName = "1.0.4"'), ("versionCode = 7", "versionCode = 8")]:
            with self.subTest(after=after):
                app.write_text(original.replace(before, after), encoding="utf-8")
                self.seal()
                self.validate("does not match the public source")
        app.write_text(original, encoding="utf-8")
        lock = self.root / "engine/native/upstream.properties"
        lock.write_text(lock.read_text(encoding="utf-8").replace("1" * 40, "5" * 40), encoding="utf-8")
        self.seal()
        self.validate("SOURCE-IDENTITY 'nativeRevision' does not match")

    def test_duplicate_missing_and_unknown_identity_fields(self):
        path = self.root / "SOURCE-IDENTITY"
        original = path.read_text(encoding="utf-8")
        for content, expected in [
            (original + "version=1.0.3\n", "Duplicate SOURCE-IDENTITY"),
            (original.replace("build=7\n", ""), "missing or unknown properties"),
            (original + "privateCommit=" + "9" * 40 + "\n", "missing or unknown properties"),
        ]:
            with self.subTest(expected=expected):
                path.write_text(content, encoding="utf-8")
                self.seal()
                self.validate(expected)

    def test_unsafe_and_duplicate_manifest_paths(self):
        manifest = self.root / "SOURCE-MANIFEST.sha256"
        original = manifest.read_text(encoding="utf-8")
        for relative in ["./README.md", "../README.md", "/README.md", "a//b", "a/./b", "a\\b", "a:b", "README.md", "readme.md", "build/source.txt"]:
            with self.subTest(relative=relative):
                manifest.write_text(original + "0" * 64 + "  " + relative + "\n", encoding="utf-8")
                self.seal_manifest()
                self.validate("Unsafe, excluded, or duplicate")

    def test_manifest_format_and_unicode_collisions(self):
        manifest = self.root / "SOURCE-MANIFEST.sha256"
        original = manifest.read_text(encoding="utf-8")
        manifest.write_text(original.replace("  README.md", " README.md"), encoding="utf-8")
        self.seal_manifest()
        self.validate("Invalid public source manifest row")
        manifest.write_text(original + "0" * 64 + "  caf\u00e9.txt\n" + "0" * 64 + "  cafe\u0301.txt\n", encoding="utf-8")
        self.seal_manifest()
        self.validate("Unsafe, excluded, or duplicate")

    def test_source_symlink_is_rejected(self):
        path = self.root / "README.md"
        path.unlink()
        path.symlink_to(self.root / "SOURCE-IDENTITY")
        self.validate("link, special file, or private metadata")

    def test_private_metadata_and_signing_properties_are_forbidden(self):
        for relative in [".git", ".GIT", "SOURCE-COMMIT", "source-commit", "android/signing.properties", "android/Signing.Properties", "AGENTS.md", "agents.md", "docs/internal/note.md", "Docs/Internal/note.md", ".FORGEJO/note.md", "engine/.git"]:
            with self.subTest(relative=relative):
                path = self.write(relative, "Forbidden local data\n")
                try:
                    # Case-insensitive filesystems also resolve .GIT through the early .git check.
                    self.validate("metadata")
                finally:
                    path.unlink()
                    # Do not leave an empty forbidden directory for the next fixture.
                    while path.parent != self.root and not any(path.parent.iterdir()):
                        path = path.parent
                        path.rmdir()

    def test_exact_generated_paths_and_local_sdk_properties_are_allowed(self):
        for relative in ["build/report.log", "android/.gradle/cache", "android/.kotlin/cache", "android/build/output", "android/app/build/output", "android/core/build/output", "android/engine/build/output", "android/app/.cxx/output", "android/engine/.cxx/output"]:
            self.write(relative, "Generated output\n")
        self.write("android/local.properties", "sdk.dir=/opt/android-sdk\n")
        self.validate()
        self.write("engine/build/hidden-source.txt", "Unmanifested source outside an allowed build directory\n")
        self.validate("Public source file set differs")

    def test_generated_directory_symlink_is_allowed_but_source_directory_link_is_not(self):
        with tempfile.TemporaryDirectory(prefix="drawless-generated-output-") as output:
            (self.root / "build").symlink_to(output, target_is_directory=True)
            self.validate()
            (self.root / "extra-source").symlink_to(output, target_is_directory=True)
            self.validate("link, special file, or private metadata")


if __name__ == "__main__":
    unittest.main(verbosity=2)
