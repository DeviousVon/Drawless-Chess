#!/usr/bin/env python3
"""Fail-closed, deterministic Drawless Chess public-source publisher.

The publisher reads committed blobs directly from Git. It never archives the
private working tree, repository history, administrative metadata, or ignored
files. The separately prepared Fairy-Stockfish index is validated against the
public native lock and exported blob-by-blob with repository-local `.git` data,
`.github` administration, and nested `AGENTS.md` removed.
"""

from __future__ import annotations

import argparse
import dataclasses
import gzip
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import unicodedata
import zlib
from pathlib import Path, PurePosixPath
from typing import Iterable, Mapping, Sequence


class BundleError(RuntimeError):
    """A release-stopping source-publisher failure."""


@dataclasses.dataclass(frozen=True)
class ArchiveEntry:
    data: bytes
    mode: int = 0o644


@dataclasses.dataclass(frozen=True)
class IndexEntry:
    path: str
    oid: str
    mode: int


@dataclasses.dataclass(frozen=True)
class ReleaseIdentity:
    version: str
    build: str
    platform: str = "ios"

    def __post_init__(self) -> None:
        if self.platform not in ("ios", "android"):
            raise BundleError(f"unsupported source platform: {self.platform}")

    @property
    def public_tag(self) -> str:
        if self.platform == "android":
            return f"v{self.version}"
        return f"ios-v{self.version}-build-{self.build}"

    @property
    def archive_name(self) -> str:
        return f"drawless-chess-{self.platform}-{self.version}-build-{self.build}-source.tar.gz"

    @property
    def root_name(self) -> str:
        return self.archive_name.removesuffix(".tar.gz")


@dataclasses.dataclass(frozen=True)
class LiteralPolicy:
    includes: tuple[str, ...]
    forbids: tuple[str, ...]

    @staticmethod
    def _parse(data: bytes, label: str) -> tuple[str, ...]:
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise BundleError(f"{label} is not UTF-8") from error
        values: list[str] = []
        for line_number, raw in enumerate(text.splitlines(), 1):
            value = raw.strip()
            if not value or value.startswith("#"):
                continue
            if any(character in value for character in "*?[]{}"):
                raise BundleError(
                    f"{label}:{line_number} uses a glob; only literal paths are allowed"
                )
            validate_relative_path(value.removesuffix("/"), label=label)
            if value in values:
                raise BundleError(f"{label}:{line_number} duplicates {value}")
            values.append(value)
        if not values:
            raise BundleError(f"{label} has no policy entries")
        return tuple(values)

    @classmethod
    def from_bytes(cls, include_data: bytes, forbid_data: bytes) -> "LiteralPolicy":
        return cls(
            cls._parse(include_data, "include-paths.txt"),
            cls._parse(forbid_data, "forbidden-paths.txt"),
        )

    @staticmethod
    def _matches(path: str, rule: str) -> bool:
        return path.startswith(rule) if rule.endswith("/") else path == rule

    def classification(self, path: str) -> str:
        if any(self._matches(path, rule) for rule in self.forbids):
            return "forbidden"
        if any(self._matches(path, rule) for rule in self.includes):
            return "included"
        return "unclassified"


class ContentScanner:
    _token = re.compile(rb"[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{2,}")
    _part = re.compile(rb"[A-Za-z0-9][A-Za-z0-9._@+\\-]{2,}")
    _issue_metadata = re.compile(
        rb"(?i)(?:"
        + b"issue"
        + b"comment-|agent/"
        + b"codex-)"
    )

    def __init__(self, forbidden_digests: frozenset[str]):
        if not forbidden_digests:
            raise BundleError("forbidden-content.sha256 has no digests")
        self._forbidden_digests = forbidden_digests

    @classmethod
    def from_bytes(cls, data: bytes) -> "ContentScanner":
        try:
            text = data.decode("ascii")
        except UnicodeDecodeError as error:
            raise BundleError("forbidden-content.sha256 is not ASCII") from error
        digests: set[str] = set()
        for line_number, raw in enumerate(text.splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            digest = line.split()[0]
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise BundleError(
                    f"forbidden-content.sha256:{line_number} has an invalid digest"
                )
            if digest in digests:
                raise BundleError(
                    f"forbidden-content.sha256:{line_number} duplicates a digest"
                )
            digests.add(digest)
        return cls(frozenset(digests))

    @staticmethod
    def _sha256(candidate: bytes) -> str:
        return hashlib.sha256(candidate).hexdigest()

    def scan(self, path: str, data: bytes) -> None:
        private_key_phrase = b"PRIVATE" + b" KEY"
        if b"-----BEGIN " in data and private_key_phrase in data:
            raise BundleError(f"private-key content reached public source: {path}")

        local_path_markers = (
            b"/" + b"Users/",
            b"/" + b"home/",
            b":\\" + b"Users\\",
        )
        if any(marker in data for marker in local_path_markers):
            raise BundleError(f"machine-local path reached public source: {path}")

        credential_markers = (
            b"github_" + b"pat_",
            b"gh" + b"p_",
            b"sk_" + b"live_",
        )
        if any(marker in data for marker in credential_markers) or re.search(
            rb"AKIA[0-9A-Z]{16}", data
        ):
            raise BundleError(f"credential-shaped content reached public source: {path}")

        upload_markers = (
            b"DRAWLESS_UPLOAD_" + b"STORE_PASSWORD=",
            b"DRAWLESS_UPLOAD_" + b"KEY_PASSWORD=",
        )
        if any(marker in data for marker in upload_markers):
            raise BundleError(f"upload-password content reached public source: {path}")

        property_keys = (b"store" + b"Password", b"key" + b"Password")
        for raw_line in data.splitlines():
            stripped = raw_line.strip()
            for key in property_keys:
                if stripped.startswith(key + b"=") and stripped != key + b"=replace-locally":
                    raise BundleError(f"signing-password content reached public source: {path}")

        if self._issue_metadata.search(data):
            raise BundleError(f"private issue or agent metadata reached public source: {path}")

        candidates: set[bytes] = set()
        candidates.update(match.group(0) for match in self._token.finditer(data))
        candidates.update(match.group(0) for match in self._part.finditer(data))
        for raw_line in data.splitlines():
            stripped = raw_line.strip()
            if 2 < len(stripped) <= 512:
                candidates.add(stripped)
        for candidate in candidates:
            for normalized in (candidate, candidate.lower()):
                if self._sha256(normalized) in self._forbidden_digests:
                    raise BundleError(f"private-only content token reached public source: {path}")


def content_scanner_from_policy_data(
    public_data: bytes,
    private_data: bytes | None,
    *,
    require_private: bool,
) -> ContentScanner:
    if require_private and private_data is None:
        raise BundleError("committed private content policy is missing")
    scanner_data = public_data
    if private_data is not None:
        scanner_data += b"\n" + private_data
    return ContentScanner.from_bytes(scanner_data)


FORBIDDEN_COMPONENTS = frozenset(
    {
        ".forgejo",
        ".git",
        ".github",
        ".gradle",
        ".kotlin",
        ".swiftpm",
        "build",
        "codex-skills",
        "deriveddata",
        "evidence",
        "node_modules",
        "play",
        "pods",
        "website",
        "xcuserdata",
    }
)
SENSITIVE_SUFFIXES = (
    ".cer",
    ".csr",
    ".der",
    ".jks",
    ".key",
    ".keystore",
    ".mobileprovision",
    ".p12",
    ".p8",
    ".pem",
    ".pfx",
)
GENERATED_SUFFIXES = (
    ".aab",
    ".aar",
    ".apk",
    ".app",
    ".class",
    ".dll",
    ".dylib",
    ".ipa",
    ".o",
    ".obj",
    ".so",
    ".xcarchive",
)
SENSITIVE_NAMES = frozenset(
    {
        ".ds_store",
        ".env",
        "google-services.json",
        "key.properties",
        "local.properties",
        "signing.properties",
    }
)


def validate_relative_path(path: str, *, label: str = "path") -> None:
    if not path:
        raise BundleError(f"{label} is empty")
    if path.startswith("/") or "\\" in path or ":" in path:
        raise BundleError(f"{label} is absolute or non-portable: {path}")
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in path):
        raise BundleError(f"{label} contains a control character")
    if any(character in path for character in '<>"|?*'):
        raise BundleError(f"{label} contains a non-portable character: {path}")
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise BundleError(f"{label} contains traversal or an empty component: {path}")
    if any(part != part.strip() or part.endswith(".") for part in parts):
        raise BundleError(f"{label} has a non-portable component: {path}")
    windows_reserved = {"con", "prn", "aux", "nul"}
    windows_reserved.update(f"com{number}" for number in range(1, 10))
    windows_reserved.update(f"lpt{number}" for number in range(1, 10))
    if any(part.split(".", 1)[0].casefold() in windows_reserved for part in parts):
        raise BundleError(f"{label} uses a reserved filename: {path}")
    if unicodedata.normalize("NFC", path) != path:
        raise BundleError(f"{label} is not Unicode NFC: {path}")


def hard_forbidden_reason(path: str) -> str | None:
    parts = path.split("/")
    lowered = [part.casefold() for part in parts]
    if any(part == "agents.md" for part in lowered):
        return "agent instructions"
    joined_pairs = {"/".join(lowered[index : index + 2]) for index in range(len(parts) - 1)}
    for part in lowered:
        if part in FORBIDDEN_COMPONENTS:
            return f"forbidden component {part}"
    if "docs/internal" in joined_pairs or "docs/portal" in joined_pairs:
        return "private documentation"
    filename = lowered[-1]
    if filename in SENSITIVE_NAMES or filename.startswith(".env."):
        return "credential or machine-local filename"
    if filename.startswith("authkey_") and filename.endswith(".p8"):
        return "Apple signing credential filename"
    if filename.endswith(SENSITIVE_SUFFIXES):
        return "credential or signing-material extension"
    if filename.endswith(GENERATED_SUFFIXES):
        return "generated binary or build-product extension"
    return None


def native_administrative_path(path: str) -> bool:
    lowered = [part.casefold() for part in path.split("/")]
    return ".github" in lowered or lowered[-1] == "agents.md"


def run(
    command: Sequence[str],
    *,
    cwd: Path,
    check: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    environment = os.environ.copy()
    environment.update({"LC_ALL": "C", "LANG": "C", "TZ": "UTC"})
    result = subprocess.run(
        list(command),
        cwd=cwd,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise BundleError(f"command failed ({' '.join(command)}): {detail}")
    return result


def git(cwd: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    return run(("git", "-c", "core.autocrlf=false", *arguments), cwd=cwd, check=check)


def read_index(repository: Path) -> list[IndexEntry]:
    output = git(repository, "ls-files", "--stage", "-z").stdout
    entries: list[IndexEntry] = []
    collision_keys: dict[str, str] = {}
    for raw in output.split(b"\0"):
        if not raw:
            continue
        try:
            metadata, raw_path = raw.split(b"\t", 1)
            raw_mode, raw_oid, raw_stage = metadata.split(b" ", 2)
            path = raw_path.decode("utf-8")
            mode_text = raw_mode.decode("ascii")
            oid = raw_oid.decode("ascii")
            stage = raw_stage.decode("ascii")
        except (ValueError, UnicodeDecodeError) as error:
            raise BundleError("Git index contains an unparseable entry") from error
        validate_relative_path(path, label="Git index path")
        if stage != "0":
            raise BundleError(f"Git index contains an unresolved stage for {path}")
        if mode_text not in ("100644", "100755"):
            raise BundleError(f"Git index contains a link or unsupported type: {path} ({mode_text})")
        if not re.fullmatch(r"[0-9a-f]{40,64}", oid):
            raise BundleError(f"Git index contains an invalid object identity: {path}")
        collision_key = unicodedata.normalize("NFC", path).casefold()
        if collision_key in collision_keys:
            raise BundleError(
                f"Git index contains case/Unicode-colliding paths: "
                f"{collision_keys[collision_key]} and {path}"
            )
        collision_keys[collision_key] = path
        entries.append(IndexEntry(path, oid, 0o755 if mode_text == "100755" else 0o644))
    if not entries:
        raise BundleError(f"Git index is empty: {repository}")
    return entries


def read_blobs(repository: Path, object_ids: Iterable[str]) -> dict[str, bytes]:
    ordered = list(dict.fromkeys(object_ids))
    process = subprocess.Popen(
        ["git", "-c", "core.autocrlf=false", "cat-file", "--batch"],
        cwd=repository,
        env={**os.environ, "LC_ALL": "C", "LANG": "C", "TZ": "UTC"},
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdin is not None
    assert process.stdout is not None
    assert process.stderr is not None
    process.stdin.write(b"".join(object_id.encode("ascii") + b"\n" for object_id in ordered))
    process.stdin.close()
    blobs: dict[str, bytes] = {}
    try:
        for requested in ordered:
            header = process.stdout.readline().decode("ascii", "strict").strip()
            fields = header.split()
            if len(fields) != 3 or fields[1] != "blob" or not fields[2].isdigit():
                raise BundleError(f"Git object is missing or not a blob: {requested}")
            actual, _, size_text = fields
            size = int(size_text)
            data = process.stdout.read(size)
            terminator = process.stdout.read(1)
            if len(data) != size or terminator != b"\n":
                raise BundleError(f"Git blob stream ended early: {requested}")
            if actual != requested:
                raise BundleError(f"Git returned the wrong blob for {requested}")
            blobs[requested] = data
    finally:
        process.stdout.close()
    stderr = process.stderr.read().decode("utf-8", "replace").strip()
    process.stderr.close()
    return_code = process.wait()
    if return_code != 0:
        raise BundleError(f"git cat-file failed: {stderr}")
    return blobs


def parse_properties(data: bytes, label: str) -> dict[str, str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise BundleError(f"{label} is not UTF-8") from error
    values: dict[str, str] = {}
    for line_number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise BundleError(f"{label}:{line_number} is not a property")
        key, value = line.split("=", 1)
        if not key or not value or key in values:
            raise BundleError(f"{label}:{line_number} has an invalid or duplicate property")
        values[key] = value
    return values


def release_identity(
    entries: Mapping[str, ArchiveEntry], platform: str = "ios"
) -> ReleaseIdentity:
    if platform == "android":
        try:
            project = entries["android/app/build.gradle.kts"].data.decode("utf-8")
        except (KeyError, UnicodeDecodeError) as error:
            raise BundleError("Android release metadata is missing or invalid") from error
        values: dict[str, str] = {}
        for key, pattern in (
            ("applicationId", r'"(com\.drawlesschess)"'),
            ("versionName", r'"((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"'),
            ("versionCode", r"([1-9][0-9]*)"),
        ):
            assignments = re.findall(rf"^\s*{key}\s*=\s*(.*?)\s*$", project, re.MULTILINE)
            match = re.fullmatch(pattern, assignments[0]) if len(assignments) == 1 else None
            if match is None:
                raise BundleError(
                    f"android/app/build.gradle.kts must define one literal release {key}"
                )
            values[key] = match.group(1)
        if int(values["versionCode"]) > 2_100_000_000:
            raise BundleError("Android versionCode exceeds the supported release range")
        return ReleaseIdentity(values["versionName"], values["versionCode"], "android")
    if platform != "ios":
        raise BundleError(f"unsupported source platform: {platform}")
    try:
        package = json.loads(entries["package.json"].data.decode("utf-8"))
        project = entries["iosApp/project.yml"].data.decode("utf-8")
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BundleError("release metadata is missing or invalid") from error
    version_match = re.search(r'^\s*MARKETING_VERSION:\s*"([^"]+)"\s*$', project, re.MULTILINE)
    build_match = re.search(
        r'^\s*CURRENT_PROJECT_VERSION:\s*"([0-9]+)"\s*$', project, re.MULTILINE
    )
    if not version_match or not build_match:
        raise BundleError("iosApp/project.yml does not define the release version/build")
    version = version_match.group(1)
    build = build_match.group(1)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise BundleError(f"iOS marketing version is not a release version: {version}")
    if package.get("version") != version:
        raise BundleError("package.json and iOS marketing versions differ")
    return ReleaseIdentity(version, build)


def load_outer_entries(repository: Path) -> tuple[dict[str, ArchiveEntry], LiteralPolicy, ContentScanner]:
    index = read_index(repository)
    blob_map = read_blobs(repository, (entry.oid for entry in index))
    by_path = {entry.path: entry for entry in index}
    policy_paths = (
        "release/public-source/include-paths.txt",
        "release/public-source/forbidden-paths.txt",
        "release/public-source/forbidden-content.sha256",
    )
    for policy_path in policy_paths:
        if policy_path not in by_path:
            raise BundleError(f"committed publisher policy is missing: {policy_path}")
    policy = LiteralPolicy.from_bytes(
        blob_map[by_path[policy_paths[0]].oid], blob_map[by_path[policy_paths[1]].oid]
    )
    private_policy_path = "release/private-source-content.sha256"
    private_policy_data = (
        blob_map[by_path[private_policy_path].oid]
        if private_policy_path in by_path
        else None
    )
    private_repository_markers_present = "AGENTS.md" in by_path or any(
        path.startswith(".forgejo/") for path in by_path
    )
    scanner = content_scanner_from_policy_data(
        blob_map[by_path[policy_paths[2]].oid],
        private_policy_data,
        require_private=private_repository_markers_present,
    )

    output: dict[str, ArchiveEntry] = {}
    unclassified: list[str] = []
    for item in index:
        classification = policy.classification(item.path)
        if classification == "forbidden":
            continue
        if classification == "unclassified":
            unclassified.append(item.path)
            continue
        reason = hard_forbidden_reason(item.path)
        if reason:
            raise BundleError(f"included path violates hard deny policy ({reason}): {item.path}")
        output[item.path] = ArchiveEntry(blob_map[item.oid], item.mode)
    if unclassified:
        preview = ", ".join(unclassified[:8])
        raise BundleError(f"tracked paths are not classified by the reviewed policy: {preview}")
    return output, policy, scanner


def validate_clean_repository(repository: Path) -> None:
    commit = git(repository, "rev-parse", "--verify", "HEAD").stdout.strip()
    if not re.fullmatch(rb"[0-9a-f]{40,64}", commit):
        raise BundleError("repository HEAD is not an immutable full Git object identity")
    status_output = git(
        repository, "status", "--porcelain=v1", "-z", "--untracked-files=all"
    ).stdout
    if status_output:
        raise BundleError("repository must be clean before creating exact public source")


def validate_native_source(
    repository: Path,
    native_source: Path,
    native_lock: Mapping[str, str],
) -> None:
    expected_relative = "upstream/Fairy-Stockfish"
    if native_lock.get("sourceDirectory") != expected_relative:
        raise BundleError("native lock has an unexpected sourceDirectory")
    default_native = repository / "engine" / "native" / expected_relative
    if native_source.resolve() == default_native.resolve():
        validation = run(
            ("bash", "scripts/native-validate-structure.sh", "--require-source"),
            cwd=repository,
            check=False,
        )
        if validation.returncode != 0:
            detail = validation.stderr.decode("utf-8", "replace").strip()
            raise BundleError(f"native source structure validation failed: {detail}")
    if not native_source.is_dir() or native_source.is_symlink():
        raise BundleError("prepared native source is absent, not a directory, or a symbolic link")

    head = git(native_source, "rev-parse", "--verify", "HEAD").stdout.decode().strip()
    head_tree = git(native_source, "rev-parse", "HEAD^{tree}").stdout.decode().strip()
    staged_tree = git(native_source, "write-tree").stdout.decode().strip()
    if head != native_lock.get("revision"):
        raise BundleError("prepared native source has the wrong upstream revision")
    if head_tree != native_lock.get("tree"):
        raise BundleError("prepared native source has the wrong upstream tree")
    if staged_tree != native_lock.get("patchedTree"):
        raise BundleError("prepared native staged tree differs from the locked patched tree")
    if git(native_source, "diff", "--quiet", check=False).returncode != 0:
        raise BundleError("prepared native source has unstaged modifications")
    untracked = git(native_source, "ls-files", "--others", "--exclude-standard", "-z").stdout
    untracked_paths = [item.decode("utf-8") for item in untracked.split(b"\0") if item]
    if untracked_paths != [".drawless-source-state.properties"]:
        raise BundleError("prepared native source has unexpected untracked files")

    state_path = native_source / ".drawless-source-state.properties"
    if not state_path.is_file() or state_path.is_symlink():
        raise BundleError("prepared native source-state marker is absent or unsafe")
    state = parse_properties(state_path.read_bytes(), "native source-state marker")
    expected_state = {
        "schemaVersion": "1",
        "upstreamRevision": native_lock.get("revision"),
        "upstreamTree": native_lock.get("tree"),
        "patchedTree": native_lock.get("patchedTree"),
        "patchVersion": native_lock.get("drawlessPatchVersion"),
        "patchesApplied": "true",
        "patchSeriesSha256": native_lock.get("patchSeriesSha256"),
    }
    if state != expected_state:
        raise BundleError("prepared native source-state marker differs from the public lock")


def add_native_entries(
    entries: dict[str, ArchiveEntry],
    native_source: Path,
    native_lock: Mapping[str, str],
) -> None:
    index = read_index(native_source)
    blobs = read_blobs(native_source, (entry.oid for entry in index))
    native_prefix = "engine/native/upstream/Fairy-Stockfish"
    native_relative_entries: dict[str, ArchiveEntry] = {}
    for item in index:
        if native_administrative_path(item.path):
            continue
        reason = hard_forbidden_reason(item.path)
        if reason:
            raise BundleError(
                f"prepared native source violates hard deny policy ({reason}): {item.path}"
            )
        native_relative_entries[item.path] = ArchiveEntry(blobs[item.oid], item.mode)

    state_data = (native_source / ".drawless-source-state.properties").read_bytes()
    native_relative_entries[".drawless-source-state.properties"] = ArchiveEntry(state_data)
    if not native_relative_entries:
        raise BundleError("prepared native source export is empty")
    required_native = {"AUTHORS", "Copying.txt", "src/search.cpp", "src/variants.ini"}
    missing = sorted(required_native - native_relative_entries.keys())
    if missing:
        raise BundleError(f"prepared native source is incomplete: {', '.join(missing)}")

    native_manifest = b"".join(
        hashlib.sha256(entry.data).hexdigest().encode("ascii")
        + b"  "
        + b"./"
        + path.encode("utf-8")
        + b"\n"
        for path, entry in sorted(native_relative_entries.items())
    )
    for relative, entry in native_relative_entries.items():
        target = f"{native_prefix}/{relative}"
        if target in entries:
            raise BundleError(f"native source collides with committed source: {target}")
        entries[target] = entry
    entries["engine/native/archive-fairy-source.sha256"] = ArchiveEntry(native_manifest)


def add_generated_release_files(
    entries: dict[str, ArchiveEntry],
    identity: ReleaseIdentity,
    native_lock: Mapping[str, str],
) -> None:
    platform_properties = (
        "platform=Android\napplicationId=com.drawlesschess\n"
        if identity.platform == "android"
        else "platform=iOS\nbundleIdentifier=com.drawlesschess\n"
    )
    identity_text = (
        "schemaVersion=1\n"
        f"{platform_properties}"
        f"version={identity.version}\n"
        f"build={identity.build}\n"
        f"publicTag={identity.public_tag}\n"
        f"archive={identity.archive_name}\n"
        "nativeComponent=Fairy-Stockfish\n"
        f"nativeRevision={native_lock['revision']}\n"
        f"nativeTree={native_lock['tree']}\n"
        f"nativePatchedTree={native_lock['patchedTree']}\n"
        f"nativePatchSeriesSha256={native_lock['patchSeriesSha256']}\n"
    ).encode("utf-8")
    readme = f"""# Drawless Chess {identity.version} ({identity.build}) iOS corresponding source

This inclusion-only archive is the complete source prepared for the public tag
`{identity.public_tag}` and the matching Drawless Chess iOS binary. It contains
the exact pinned, patched Fairy-Stockfish staged tree without repository-local
`.git` data, `.github` administration, or nested `AGENTS.md` instructions.

Verify `SOURCE-MANIFEST.sha256` and its digest before using the archive. Rebuild
and unsigned-product inspection instructions are in
`release/public-source/REBUILD-IOS.md`. SDKs, caches, signing keys, provisioning
profiles, credentials, portal data, and private workflow
material are intentionally absent.
""".encode("utf-8")
    if identity.platform == "android":
        readme = f"""# Drawless Chess {identity.version} ({identity.build}) Android corresponding source

This inclusion-only archive is the complete source prepared for the public tag
`{identity.public_tag}` and the matching Drawless Chess Android binary. It contains
the exact pinned, patched Fairy-Stockfish staged tree without repository-local
`.git` data, `.github` administration, or nested `AGENTS.md` instructions.

Verify `SOURCE-MANIFEST.sha256` and its digest before using the archive. Android
rebuild instructions are in `release/public-source/REBUILD-ANDROID.md`. The
release binary carries this archive's `SOURCE-IDENTITY` and manifest digest.
SDKs, caches, signing keys, credentials, portal data, and private workflow
material are intentionally absent.
""".encode("utf-8")
    entries["SOURCE-IDENTITY"] = ArchiveEntry(identity_text)
    entries["SOURCE-BUNDLE-README.md"] = ArchiveEntry(readme)

    manifest = b"".join(
        hashlib.sha256(entry.data).hexdigest().encode("ascii")
        + b"  "
        + path.encode("utf-8")
        + b"\n"
        for path, entry in sorted(entries.items())
    )
    entries["SOURCE-MANIFEST.sha256"] = ArchiveEntry(manifest)
    entries["SOURCE-MANIFEST.sha256.digest"] = ArchiveEntry(
        hashlib.sha256(manifest).hexdigest().encode("ascii") + b"\n"
    )


def validate_entry_set(
    entries: Mapping[str, ArchiveEntry],
    policy: LiteralPolicy,
    scanner: ContentScanner,
    platform: str = "ios",
) -> None:
    required = {
        "LICENSE",
        "APACHE-2.0.txt",
        "NOTICE",
        "THIRD_PARTY_NOTICES.md",
        "SOURCE-BUNDLE-README.md",
        "SOURCE-IDENTITY",
        "SOURCE-MANIFEST.sha256",
        "SOURCE-MANIFEST.sha256.digest",
        "engine/native/SOURCE_NOTICE.txt",
        "engine/native/archive-fairy-source.sha256",
        "engine/native/upstream/Fairy-Stockfish/Copying.txt",
        "engine/patches/series",
        "engine/variants.ini",
        "multiplatform/shared-core/build.gradle.kts",
        "scripts/source-bundle.py",
        "scripts/test-source-bundle.py",
    }
    if platform == "android":
        required.update({
            "android/app/build.gradle.kts",
            "android/app/src/main/AndroidManifest.xml",
            "android/build.gradle.kts",
            "android/settings.gradle.kts",
            "android/gradlew",
            "android/gradle/wrapper/gradle-wrapper.jar",
            "android/gradle/wrapper/gradle-wrapper.properties",
            "android/engine/build.gradle.kts",
            "android/engine/src/main/cpp/CMakeLists.txt",
            "engine/native/upstream.properties",
            "release/public-source/REBUILD-ANDROID.md",
            "scripts/native-validate-structure.sh",
        })
    elif platform == "ios":
        required.update({
            "ios-engine/include/drawless_fairy.h",
            "iosApp/project.yml",
            "iosApp/DrawlessChess/ContentView.swift",
            "release/public-source/REBUILD-IOS.md",
            "scripts/build-ios-engine.sh",
            "scripts/generate-ios-project.sh",
            "scripts/inspect-ios-release-app.sh",
        })
    else:
        raise BundleError(f"unsupported source platform: {platform}")
    missing = sorted(required - entries.keys())
    if missing:
        raise BundleError(f"public source is missing required rebuild material: {', '.join(missing)}")

    collision_keys: dict[str, str] = {}
    generated = {
        "SOURCE-BUNDLE-README.md",
        "SOURCE-IDENTITY",
        "SOURCE-MANIFEST.sha256",
        "SOURCE-MANIFEST.sha256.digest",
        "engine/native/archive-fairy-source.sha256",
    }
    file_paths = set(entries)
    for path, entry in entries.items():
        validate_relative_path(path)
        collision_key = unicodedata.normalize("NFC", path).casefold()
        if collision_key in collision_keys:
            raise BundleError(
                f"public source has case/Unicode-colliding paths: "
                f"{collision_keys[collision_key]} and {path}"
            )
        collision_keys[collision_key] = path
        parent = PurePosixPath(path).parent
        while str(parent) != ".":
            if str(parent) in file_paths:
                raise BundleError(
                    f"public source has a file/directory collision: {parent} and {path}"
                )
            parent = parent.parent
        reason = hard_forbidden_reason(path)
        if reason:
            raise BundleError(f"public source contains {reason}: {path}")
        if path not in generated and policy.classification(path) != "included":
            raise BundleError(f"public source path is not explicitly included: {path}")
        if entry.mode not in (0o644, 0o755):
            raise BundleError(f"public source has a non-normalized mode: {path}")
        scanner.scan(path, entry.data)


def directory_names(paths: Iterable[str]) -> list[str]:
    directories: set[str] = set()
    for path in paths:
        parent = PurePosixPath(path).parent
        while str(parent) != ".":
            directories.add(str(parent))
            parent = parent.parent
    return sorted(directories)


def write_archive(path: Path, root_name: str, entries: Mapping[str, ArchiveEntry]) -> None:
    with path.open("xb") as raw_output:
        with gzip.GzipFile(
            filename="", mode="wb", compresslevel=9, mtime=0, fileobj=raw_output
        ) as compressed:
            with tarfile.open(
                fileobj=compressed, mode="w", format=tarfile.GNU_FORMAT
            ) as archive:
                root = tarfile.TarInfo(root_name)
                root.type = tarfile.DIRTYPE
                root.mode = 0o755
                root.uid = root.gid = root.mtime = 0
                root.uname = root.gname = ""
                archive.addfile(root)
                for directory in directory_names(entries.keys()):
                    member = tarfile.TarInfo(f"{root_name}/{directory}")
                    member.type = tarfile.DIRTYPE
                    member.mode = 0o755
                    member.uid = member.gid = member.mtime = 0
                    member.uname = member.gname = ""
                    archive.addfile(member)
                for relative, entry in sorted(entries.items()):
                    member = tarfile.TarInfo(f"{root_name}/{relative}")
                    member.type = tarfile.REGTYPE
                    member.mode = entry.mode
                    member.uid = member.gid = member.mtime = 0
                    member.uname = member.gname = ""
                    member.size = len(entry.data)
                    archive.addfile(member, io.BytesIO(entry.data))
    path.chmod(0o644)


def verify_manifest(files: Mapping[str, ArchiveEntry]) -> None:
    try:
        manifest = files["SOURCE-MANIFEST.sha256"].data
        recorded_digest = files["SOURCE-MANIFEST.sha256.digest"].data.decode("ascii").strip()
    except (KeyError, UnicodeDecodeError) as error:
        raise BundleError("source archive is missing a valid manifest or digest") from error
    actual_digest = hashlib.sha256(manifest).hexdigest()
    if recorded_digest != actual_digest:
        raise BundleError("source archive manifest digest does not match")

    manifested: set[str] = set()
    for line_number, raw in enumerate(manifest.splitlines(), 1):
        match = re.fullmatch(rb"([0-9a-f]{64})  (.+)", raw)
        if not match:
            raise BundleError(f"source manifest row {line_number} is invalid")
        expected = match.group(1).decode("ascii")
        try:
            relative = match.group(2).decode("utf-8")
        except UnicodeDecodeError as error:
            raise BundleError(f"source manifest row {line_number} path is not UTF-8") from error
        validate_relative_path(relative, label="manifest path")
        if relative in manifested:
            raise BundleError(f"source manifest repeats a path: {relative}")
        if relative in ("SOURCE-MANIFEST.sha256", "SOURCE-MANIFEST.sha256.digest"):
            raise BundleError("source manifest recursively includes itself")
        if relative not in files:
            raise BundleError(f"source manifest names an absent file: {relative}")
        actual = hashlib.sha256(files[relative].data).hexdigest()
        if expected != actual:
            raise BundleError(f"source manifest hash differs: {relative}")
        manifested.add(relative)
    actual_files = set(files) - {"SOURCE-MANIFEST.sha256", "SOURCE-MANIFEST.sha256.digest"}
    if manifested != actual_files:
        extra = sorted(actual_files - manifested)
        raise BundleError(f"source archive contains unmanifested files: {', '.join(extra[:8])}")


def inspect_archive(
    archive_path: Path,
    *,
    expected_root: str | None = None,
    expected_platform: str | None = None,
) -> tuple[str, dict[str, ArchiveEntry]]:
    if not archive_path.is_file() or archive_path.is_symlink():
        raise BundleError("source archive is absent, not regular, or a symbolic link")
    archive_bytes = archive_path.read_bytes()
    if (
        len(archive_bytes) < 18
        or archive_bytes[:3] != b"\x1f\x8b\x08"
        or archive_bytes[3] != 0
        or archive_bytes[4:8] != b"\0\0\0\0"
    ):
        raise BundleError("source archive gzip header is not normalized")
    decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        decompressor.decompress(archive_bytes)
        decompressor.flush()
    except zlib.error as error:
        raise BundleError(f"source archive gzip stream is invalid: {error}") from error
    if not decompressor.eof or decompressor.unused_data or decompressor.unconsumed_tail:
        raise BundleError("source archive has a truncated, concatenated, or trailing gzip stream")
    files: dict[str, ArchiveEntry] = {}
    directories: set[str] = set()
    seen: dict[str, str] = {}
    root_name: str | None = None
    try:
        with tarfile.open(archive_path, mode="r:gz") as archive:
            members = archive.getmembers()
            if not members:
                raise BundleError("source archive is empty")
            first_name = (
                members[0].name[:-1]
                if members[0].isdir() and members[0].name.endswith("/")
                else members[0].name
            )
            if not members[0].isdir() or "/" in first_name:
                raise BundleError("source archive must begin with its single root directory")
            validate_relative_path(first_name, label="archive root")
            root_name = first_name
            for member in members:
                raw_name = member.name
                name = raw_name[:-1] if member.isdir() and raw_name.endswith("/") else raw_name
                if name.startswith("/") or "\\" in name or ":" in name:
                    raise BundleError(f"source archive has an unsafe path: {name}")
                parts = name.split("/")
                if any(part in ("", ".", "..") for part in parts):
                    raise BundleError(f"source archive has traversal or an empty path: {name}")
                if parts[0] != root_name:
                    raise BundleError("source archive contains more than one top-level root")
                collision = unicodedata.normalize("NFC", name).casefold()
                if collision in seen:
                    raise BundleError(
                        f"source archive has duplicate/colliding members: {seen[collision]} and {name}"
                    )
                seen[collision] = name
                if member.uid != 0 or member.gid != 0 or member.mtime != 0:
                    raise BundleError(f"source archive metadata is not normalized: {name}")
                if member.uname or member.gname:
                    raise BundleError(f"source archive owner names are not empty: {name}")
                if member.isdir():
                    if stat.S_IMODE(member.mode) != 0o755:
                        raise BundleError(f"source archive directory mode is not 0755: {name}")
                    if len(parts) > 1:
                        relative_directory = "/".join(parts[1:])
                        validate_relative_path(relative_directory, label="archive directory")
                        reason = hard_forbidden_reason(relative_directory)
                        if reason:
                            raise BundleError(
                                f"source archive contains a forbidden directory "
                                f"({reason}): {relative_directory}"
                            )
                        directories.add(relative_directory)
                    continue
                if not member.isreg():
                    raise BundleError(f"source archive contains a link or special file: {name}")
                if len(parts) < 2:
                    raise BundleError("source archive has a top-level regular file")
                relative = "/".join(parts[1:])
                validate_relative_path(relative, label="archive path")
                mode = stat.S_IMODE(member.mode)
                if mode not in (0o644, 0o755):
                    raise BundleError(f"source archive file mode is not normalized: {name}")
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise BundleError(f"source archive file could not be read: {name}")
                files[relative] = ArchiveEntry(extracted.read(), mode)
    except (tarfile.TarError, OSError) as error:
        raise BundleError(f"source archive could not be read: {error}") from error
    assert root_name is not None
    if expected_root is not None and root_name != expected_root:
        raise BundleError(f"source archive root differs: expected {expected_root}, found {root_name}")
    if root_name not in seen or not any(name != root_name for name in seen.values()):
        raise BundleError("source archive does not contain its normalized root directory")
    expected_directories = set(directory_names(files))
    if directories != expected_directories:
        unexpected = sorted(directories - expected_directories)
        missing = sorted(expected_directories - directories)
        detail = unexpected[:4] or missing[:4]
        raise BundleError(
            "source archive directory inventory is not canonical: " + ", ".join(detail)
        )

    try:
        policy = LiteralPolicy.from_bytes(
            files["release/public-source/include-paths.txt"].data,
            files["release/public-source/forbidden-paths.txt"].data,
        )
        scanner = ContentScanner.from_bytes(
            files["release/public-source/forbidden-content.sha256"].data
        )
    except KeyError as error:
        raise BundleError("source archive is missing its public path/content policy") from error
    try:
        identity_values = parse_properties(files["SOURCE-IDENTITY"].data, "SOURCE-IDENTITY")
    except KeyError as error:
        raise BundleError("source archive is missing SOURCE-IDENTITY") from error
    platform = {"iOS": "ios", "Android": "android"}.get(identity_values.get("platform", ""))
    if platform is None or (expected_platform is not None and platform != expected_platform):
        raise BundleError("source archive platform identity is inconsistent")
    validate_entry_set(files, policy, scanner, platform)
    verify_manifest(files)
    validate_archive_identity(files, root_name, identity_values, platform)
    return root_name, files


def validate_archive_identity(
    files: Mapping[str, ArchiveEntry],
    root_name: str,
    identity_values: Mapping[str, str],
    platform: str,
) -> None:
    if platform == "android":
        identity = release_identity(files, platform)
        try:
            native_lock = parse_properties(
                files["engine/native/upstream.properties"].data, "native lock"
            )
            expected_values = {
                "schemaVersion": "1",
                "platform": "Android",
                "applicationId": "com.drawlesschess",
                "version": identity.version,
                "build": identity.build,
                "publicTag": identity.public_tag,
                "archive": identity.archive_name,
                "nativeComponent": "Fairy-Stockfish",
                "nativeRevision": native_lock["revision"],
                "nativeTree": native_lock["tree"],
                "nativePatchedTree": native_lock["patchedTree"],
                "nativePatchSeriesSha256": native_lock["patchSeriesSha256"],
            }
        except KeyError as error:
            raise BundleError("source archive is missing native identity metadata") from error
        if identity_values != expected_values:
            raise BundleError("Android source identity differs from the release metadata/native lock")
        if root_name != identity.root_name:
            raise BundleError("source archive filename/root identity is inconsistent")
        return
    if identity_values.get("publicTag") != f"ios-v{identity_values.get('version')}-build-{identity_values.get('build')}":
        raise BundleError("source archive public tag identity is inconsistent")
    expected_name = (
        f"drawless-chess-ios-{identity_values.get('version')}-"
        f"build-{identity_values.get('build')}-source"
    )
    if root_name != expected_name or identity_values.get("archive") != expected_name + ".tar.gz":
        raise BundleError("source archive filename/root identity is inconsistent")


def reproducibility_build(
    output: Path,
    root_name: str,
    entries: Mapping[str, ArchiveEntry],
) -> str:
    output_parent = output.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    if output.exists() or output.is_symlink():
        raise BundleError(f"output already exists: {output}")

    old_timezone = os.environ.get("TZ")
    old_umask = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(
            prefix="drawless-publisher-a-", dir=output_parent
        ) as first_root, tempfile.TemporaryDirectory(
            prefix="drawless-publisher-b-", dir=output_parent
        ) as second_root:
            first = Path(first_root) / output.name
            second = Path(second_root) / output.name
            os.environ["TZ"] = "UTC"
            if hasattr(time, "tzset"):
                time.tzset()
            os.umask(0o077)
            write_archive(first, root_name, entries)

            os.environ["TZ"] = "Pacific/Honolulu"
            if hasattr(time, "tzset"):
                time.tzset()
            os.umask(0o022)
            write_archive(second, root_name, entries)

            first_hash = hashlib.sha256(first.read_bytes()).hexdigest()
            second_hash = hashlib.sha256(second.read_bytes()).hexdigest()
            if first_hash != second_hash or first.read_bytes() != second.read_bytes():
                raise BundleError("independent source builds are not byte-identical")
            inspect_archive(first, expected_root=root_name)
            inspect_archive(second, expected_root=root_name)
            try:
                os.link(first, output)
            except FileExistsError as error:
                raise BundleError(f"output already exists: {output}") from error
            except OSError as error:
                raise BundleError(f"could not atomically publish source archive: {error}") from error
            output.chmod(0o644)
            return first_hash
    finally:
        os.umask(old_umask)
        if old_timezone is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = old_timezone
        if hasattr(time, "tzset"):
            time.tzset()


def prepare_bundle_entries(
    repository: Path, native_source: Path | None = None, platform: str = "ios"
) -> tuple[dict[str, ArchiveEntry], ReleaseIdentity]:
    repository = repository.resolve()
    if not repository.is_dir() or repository.is_symlink():
        raise BundleError("repository root is absent or unsafe")
    validate_clean_repository(repository)
    entries, policy, scanner = load_outer_entries(repository)
    identity = release_identity(entries, platform)
    native_lock = parse_properties(
        entries["engine/native/upstream.properties"].data, "engine/native/upstream.properties"
    )
    required_lock = {
        "revision",
        "tree",
        "patchedTree",
        "patchSeriesSha256",
        "drawlessPatchVersion",
        "sourceDirectory",
    }
    if any(not native_lock.get(key) for key in required_lock):
        raise BundleError("native source lock is incomplete")
    native_source = native_source or (
        repository / "engine" / "native" / native_lock["sourceDirectory"]
    )
    validate_native_source(repository, native_source, native_lock)
    add_native_entries(entries, native_source, native_lock)
    add_generated_release_files(entries, identity, native_lock)
    validate_entry_set(entries, policy, scanner, platform)
    return entries, identity


def run_release_rebuild_gate(repository: Path) -> None:
    gate = repository / "scripts" / "test-source-bundle.py"
    if not gate.is_file() or gate.is_symlink():
        raise BundleError("release rebuild gate is absent, not regular, or a symbolic link")
    environment = os.environ.copy()
    environment.update({"LC_ALL": "C", "LANG": "C", "TZ": "UTC"})
    result = subprocess.run(
        [sys.executable, str(gate), "--release-gate"],
        cwd=repository,
        env=environment,
        check=False,
    )
    if result.returncode != 0:
        raise BundleError("release rebuild gate failed; source archive was not created")


def run_android_source_gate(repository: Path, native_source: Path | None = None) -> None:
    gate = repository / "scripts" / "test-source-bundle.py"
    if not gate.is_file() or gate.is_symlink():
        raise BundleError("Android source gate is absent, not regular, or a symbolic link")
    environment = os.environ.copy()
    environment.update({"LC_ALL": "C", "LANG": "C", "TZ": "UTC"})
    if native_source is not None:
        environment["DRAWLESS_TEST_NATIVE_SOURCE"] = str(native_source.resolve())
    result = subprocess.run(
        [sys.executable, str(gate), "--android-release-gate"],
        cwd=repository,
        env=environment,
        check=False,
    )
    if result.returncode != 0:
        raise BundleError("Android source extraction gate failed; source archive was not created")


def create_bundle(
    repository: Path,
    output: Path,
    native_source: Path | None = None,
    platform: str = "ios",
) -> str:
    if output.exists() or output.is_symlink():
        raise BundleError(f"output already exists: {output}")
    output = output.resolve()
    if platform == "ios":
        expected_archive = "drawless-chess-ios-1.0.2-build-2-source.tar.gz"
        if output.name != expected_archive:
            raise BundleError(f"output filename must be the public release identity {expected_archive}")
    elif platform != "android":
        raise BundleError(f"unsupported source platform: {platform}")
    repository = repository.resolve()
    validate_clean_repository(repository)
    if platform == "android":
        committed_entries, _, _ = load_outer_entries(repository)
        identity = release_identity(committed_entries, platform)
        if output.name != identity.archive_name:
            raise BundleError(
                f"output filename must be the public release identity {identity.archive_name}"
            )
        run_android_source_gate(repository, native_source)
        entries, identity = prepare_bundle_entries(repository, native_source, platform)
    else:
        run_release_rebuild_gate(repository)
        entries, identity = prepare_bundle_entries(repository, native_source)
    if output.name != identity.archive_name:
        raise BundleError(
            f"output filename must be the public release identity {identity.archive_name}"
        )
    digest = reproducibility_build(output, identity.root_name, entries)
    inspect_archive(output, expected_root=identity.root_name)
    return digest


def infer_root_for_verification(archive_path: Path) -> str:
    match = re.fullmatch(
        r"(drawless-chess-(?:ios|android)-[0-9]+\.[0-9]+\.[0-9]+-build-[0-9]+-source)\.tar\.gz",
        archive_path.name,
    )
    if not match:
        raise BundleError("source archive has an unexpected public filename")
    return match.group(1)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--native-source", type=Path)
    parser.add_argument("--verify", type=Path)
    parser.add_argument("--manifest-digest", action="store_true")
    parser.add_argument("--platform", choices=("ios", "android"))
    options = parser.parse_args(arguments)
    try:
        if options.verify is not None:
            if (
                options.repository_root is not None
                or options.output is not None
                or options.native_source is not None
                or options.manifest_digest
            ):
                raise BundleError("--verify cannot be combined with build options")
            expected_root = infer_root_for_verification(options.verify)
            inspect_archive(
                options.verify.resolve(),
                expected_root=expected_root,
                expected_platform=options.platform,
            )
            digest = hashlib.sha256(options.verify.read_bytes()).hexdigest()
            print(f"source_archive_sha256={digest}")
            print("source_archive_verification=PASS")
            return 0
        if options.manifest_digest:
            if options.repository_root is None or options.output is not None:
                raise BundleError(
                    "--manifest-digest requires --repository-root and does not accept --output"
                )
            entries, identity = prepare_bundle_entries(
                options.repository_root, options.native_source, options.platform or "ios"
            )
            manifest_digest = hashlib.sha256(
                entries["SOURCE-MANIFEST.sha256"].data
            ).hexdigest()
            print(f"public_tag={identity.public_tag}")
            print(f"source_manifest_sha256={manifest_digest}")
            print("source_manifest_inventory=PASS")
            return 0
        if options.repository_root is None or options.output is None:
            raise BundleError("--repository-root and --output are required to build")
        platform = options.platform or "ios"
        digest = create_bundle(
            options.repository_root, options.output, options.native_source, platform
        )
        identity_match = re.fullmatch(
            rf"drawless-chess-{platform}-([0-9]+\.[0-9]+\.[0-9]+)-build-([0-9]+)-source\.tar\.gz",
            options.output.name,
        )
        assert identity_match is not None
        identity = ReleaseIdentity(identity_match.group(1), identity_match.group(2), platform)
        print(f"public_tag={identity.public_tag}")
        print(f"source_archive_sha256={digest}")
        print("source_archive_determinism=PASS")
        print("source_archive_verification=PASS")
        return 0
    except BundleError as error:
        print(f"source-bundle: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
