# Fairy-Stockfish native source and Android package boundary

This directory pins the production native engine independently from the npm/WASM
proof of concept. The canonical upstream base is the full commit in
`upstream.properties`; a moving branch or short hash is never accepted by the
fetch or validation scripts.

## Prepare the source

From the repository root:

```bash
scripts/native-fetch-fairy.sh
scripts/native-validate-structure.sh --require-source
```

The fetch is fail-closed: it checks the fetched commit and Git tree before
applying the ordered Drawless patch series. It does not replace an existing
checkout. Use `--upstream-only` only for inspecting the unmodified base; that
checkout is not accepted by the Android production build.

The checkout is intentionally uncommitted under
`engine/native/upstream/Fairy-Stockfish`. The upstream Git commit is the source
content hash; Drawless modifications live as reviewable patches rather than a
second vendored copy.

## Android artifact

`android/engine` is an Android library module that compiles
`libdrawless_fairy.so` for exactly these version-1 ABIs:

- `arm64-v8a`, using the ARMv8/NEON path;
- `x86_64`, using only the Android ABI baseline (no AVX/BMI requirement).

The module pins NDK `29.0.14206865`, CMake `3.22.1`, min SDK 26, a static libc++
inside the engine shared object, and an explicit translation-unit manifest.
Upstream `main.cpp` is excluded. `native_bridge.cpp` supplies the provisional
private-test JNI runtime: bounded command/output pipes, exact `RegisterNatives`
methods, one live session, deterministic stop/quit, and sequential restart.

The shared library exports `JNI_OnLoad` plus immutable build identity functions.
Kotlin implements `NativeEnginePort` using managed startup/write and blocking
stdout/stderr reader workers; native code never calls Kotlin asynchronously. The
module packages the hash-locked configuration as
`assets/engine/drawless-variants.ini`, and the factory verifies and installs it
before creating the native session.

This is not release approval. Drawless Chess has adopted GPL-3.0-or-later for the
combined in-process application; JNI is not treated as a licensing workaround.
The boundary still has crash and lifecycle consequences that Android/device tests
must cover.

## Reproducibility and release boundary

The source pin, ordered patches, compiler version, CMake version, ABIs, minimum
API, compile definitions, and source list are fixed. This removes accidental
branch drift; it is not a claim that APK/AAR bytes are identical across host
operating systems because Android packaging can contain independent metadata.

For a release candidate, run `scripts/source-bundle.sh` and archive its SHA-256
next to the signed binary. The generated whole-project bundle contains this exact
prepared native Git checkout plus all Drawless source and build material.
`scripts/native-source-bundle.sh` remains only as a compatibility alias. Review
`SOURCE_NOTICE.txt` and `docs/RELEASE_LICENSING.md` before external distribution.

After an Android machine builds the AAR, verify its exact ABI contents, ELF
architectures, legal assets, sizes, and hashes with:

```bash
scripts/native-verify-aar.sh android/engine/build/outputs/aar/engine-release.aar \
  native-engine-manifest.json
```

The emitted JSON has the same fields as the core `NativeEngineManifest` model.
It is generated from real binaries; this repository does not contain invented
library sizes or checksums.

## App integration

`:app` depends on `:engine` and selects `AndroidFairyEngineFactory` by default.
Native failure becomes a visible non-playing bot error. The development bot is
available only through `-Pdrawless.useDevelopmentEngine=true` in debug; release
hardcodes that switch off. The engine module packages the root Drawless license
and notices alongside the Fairy-Stockfish license/source identity. No external APK
distribution is permitted until the Android binary/device and full GPL release
gates pass.
