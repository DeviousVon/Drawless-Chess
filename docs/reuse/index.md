# Drawless Chess Reuse Catalog

**Owner:** Drawless Chess

**Reviewed:** 2026-09-07

This catalog identifies platform-neutral code that other GPL-compatible
projects can study or consume. Repository code is GPL-3.0-or-later unless a
file or bundled third-party notice says otherwise; extraction must preserve
applicable notices and source obligations.

## Shared rules, chess, and coordinator

- **Status:** Active; compiled into Android directly and into the Apple/JVM
  Kotlin Multiplatform targets from the same Kotlin source.
- **Tests:** Core acceptance fixtures and coordinator tests, plus shared-core
  parity tests.
- **Dependencies:** Kotlin standard library and the shared engine contracts; no
  UI or platform persistence dependency.
- **License:** GPL-3.0-or-later.
- **Consumers:** Android runtime and the iOS `SharedGameRuntime` host boundary.
- **Extraction readiness:** High for reuse inside a GPL-compatible Kotlin
  project; medium for a standalone package because the source currently lives
  under `android/core` and should move only in a reviewed no-behavior-change
  step.

## Checkpoint payload codec

- **Status:** Active; Android Room and the Apple shared runtime compile the same
  platform-neutral payload implementation.
- **Tests:** Shared-core codec/parity coverage and Android Room checkpoint
  instrumentation cover their current paths.
- **Dependencies:** Coordinator models and `kotlinx-serialization-json`;
  persistence engines remain outside the codec.
- **License:** GPL-3.0-or-later.
- **Consumers:** Apple native storage through `SharedGameRuntime` and Android's
  Room checkpoint adapter.
- **Extraction readiness:** High for GPL-compatible Kotlin projects; the codec
  is isolated under `shared/checkpoint-codec` and storage engines stay outside it.

## Engine protocol and game review

- **Status:** Active common deterministic parsing, requests, review planning,
  grading, and evidence models; native transports remain platform-specific.
- **Tests:** Core engine-layer tests, shared parity/concurrency tests, and native
  engine integration lanes.
- **Dependencies:** Shared chess/rules/coordinator models and the versioned UCI
  contract; runtime execution requires a platform engine transport.
- **License:** GPL-3.0-or-later.
- **Consumers:** Android JNI runtime, Apple FIFO/native runtime, post-game review,
  and headless verification tooling.
- **Extraction readiness:** High for protocol and review policy; transport and
  scheduling adapters must stay with the consuming platform.

## Board presentation

- **Status:** Active common reducer and platform-neutral board, history, motion,
  threat, accessibility, stable theme catalog, and semantic state-palette
  projections. Compose and SwiftUI keep native deterministic procedural-material
  rendering. Emberwood Court and Witchglass are temporary Halloween material
  candidates sharing the byte-identical, project-owned All Hallows sculpture
  atlases and native cached color-key renderer. Celestial Observatory uses the
  standard piece shapes. Candidate presence is not released evidence.
- **Tests:** Core presentation tests and shared-core parity/UI-facing contract
  coverage protect the established presentation path. The candidate also has
  deterministic-preview, Android compile, iOS simulator build/UI, localization,
  live simulator visual evidence, and Pixel install/launch visual smoke; R6 and
  sustained physical-device performance/thermal acceptance remain open.
- **Dependencies:** Shared chess, rules, coordinator snapshots, stable theme IDs,
  value-only presentation models, platform canvas/cache implementations, and the
  two 1774 × 887 All Hallows atlas PNGs. Experimental Celestial atlases remain
  unused concept assets, with their provenance retained. There is no live 3D runtime or third-party
  board/piece library.
- **Provenance/license:** GPL-3.0-or-later. The generated-atlas prompts, source-use
  boundary, and exact hashes are recorded in `artwork/all-hallows/README.md` and
  `artwork/celestial-observatory/README.md`.
- **Consumers:** Android Compose screens and SwiftUI through shared projections.
- **Extraction readiness:** Medium-high for reducers, theme identities, and semantic
  palettes. The atlas renderer is reusable when both GPL-covered images, crop table,
  color matrix, caches, and provenance record travel together; product copy,
  gestures, and widgets remain native app-owned adapters.

## Native engine patch and build boundary

- **Status:** Active pinned Fairy-Stockfish source manifest, ordered Drawless
  patch series, variant configuration, and reproducible verification scripts.
- **Tests:** Patch application/build verification, UCI acceptance/parity,
  Android JNI host/device lanes, and Apple native-engine lanes.
- **Dependencies:** The pinned Fairy-Stockfish revision, C++ toolchains, CMake,
  platform packagers, and the Drawless UCI contract.
- **License:** GPL-3.0-or-later, including the upstream engine and Drawless patch
  series; preserve corresponding-source and notice requirements.
- **Consumers:** Android engine AAR/JNI packaging, Apple static-library/XCFramework
  packaging, and headless diagnostics.
- **Extraction readiness:** Medium; the patch set and manifests are portable,
  but builds must retain platform toolchain pins, provenance, checksums, and
  license verification.

## Complete Review evidence

- **Status:** Private candidate, with core/JVM and native host validation;
  designated-device acceptance remains separate.
- **Implementation:** `GameReviewEvidence.kt` provides canonical SHA-256 keys,
  exact Evidence V2, legal replay validation, and versioned derived accuracy.
- **Dependencies:** Shared chess/rule models and Kotlin standard library.
- **Consumers:** Android live/history Review; shared engine analysis paths.
- **Tests:** Engine-layer fingerprint/round-trip/rejection cases and Android
  migration, cache-refresh, idempotency, and reopen tests.
- **License/provenance:** Project-authored GPL-3.0-or-later code.
- **Extraction readiness:** Suitable for a GPL-compatible Kotlin consumer after
  retaining its rule contracts and tests; Room storage remains Android-specific.

## Android public-source release validation

- **Status:** Candidate integration; exact source and archive rejection tests pass.
- **Implementation:** Android Gradle validates public identity, manifest digest,
  source file set, and file hashes; release assets carry only public identity.
  PowerShell verifies exact AAB identity, legal/SBOM bytes, signature, and native
  ABI/page alignment on supported host platforms.
- **Tests:** Production Kotlin validator fixtures, ZIP identity rejection tests,
  source exporter/native archive fixtures, and full release artifact verification.
- **Dependencies:** Java/Kotlin, Gradle, Python, PowerShell 7, Android SDK/NDK, and
  the project-specific reviewed source inclusion and native-lock policies.
- **Provenance/license:** Project-authored GPL-3.0-or-later release tooling.
- **Consumers:** Android corresponding-source and Play candidate preparation.
- **Extraction readiness:** Reusable as a pattern; package/version rules, legal
  payloads, native pins, signing identity, and publication approvals must be
  supplied and reviewed by each consuming project.
