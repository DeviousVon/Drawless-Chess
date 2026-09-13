# GPL release and corresponding-source gate

## Project licensing decision

Drawless Chess, including the Android application that links the modified
Fairy-Stockfish engine through JNI and the iOS application that links it through
the static Apple bridge, is distributed under GPL-3.0-or-later. Charging once
for either app is compatible with that decision; recipients still receive the
freedoms to study, modify, and redistribute it. No technical process boundary is
being used as a licensing workaround.

`LICENSE` is the authoritative project license. `NOTICE` explains the source
promise and high-level attributions. `THIRD_PARTY_NOTICES.md` records the iOS
runtime boundary and, together with the CycloneDX SBOM, the exact resolved
Android runtime inventory. Files carrying a different license or third-party
notice keep that license.

The GPL copyright license does not authorize a fork to misrepresent itself as an
official or endorsed release. “Drawless Chess” and the project logo are source
identifiers even though no trademark registration is claimed here. Forks retain
all GPL rights and may make truthful attribution, but release review should keep
branding/confusion questions separate from code and asset copyright licensing.

## What complete corresponding source means here

For the exact Android APK/AAB or iOS application archive, publish everything a
recipient needs to rebuild and modify the work, including:

- the applicable Android or iOS app, shared core, engine adapters, JNI or Apple
  bridge, rules, tests, and build scripts;
- the exact modified Fairy-Stockfish tree, its upstream identity, the ordered
  Drawless patches, and variant configuration;
- Gradle wrapper material, Android and Kotlin Multiplatform dependency
  declarations, XcodeGen project specification, schemas, contracts, and
  interface-definition material;
- scripts used to configure, compile, package, install, and verify the binary;
- any release-specific source changes and any other material required by GPLv3's
  definition of Corresponding Source.

Build products, caches, local SDK paths, signing keys, credentials, device logs,
private service configuration, repository administration, agent instructions,
portal data, and internal workflow material are neither source nor safe
public-release content. `scripts/source-bundle.sh` is the owner-controlled,
inclusion-only clean-room entry point. It reads committed blobs, requires every
tracked path to be classified, applies deny rules after inclusion, and fails on
unsafe types/names, private-only content, or non-deterministic output.

## Exact-release checklist

Successful platform machine/device gates are engineering evidence, not permission
to publish. Before each public release:

1. Put the complete project in a version-controlled, reviewable state. Record an
   immutable release tag or source revision. If repository identity is unavailable,
   public distribution remains blocked even if a platform binary builds.
2. Resolve the applicable platform's exact release dependency graph. For Android,
   review licenses and generate the complete transitive third-party notice/SBOM.
   For iOS, review the pinned Kotlin/Native toolchain, declared and transitive KMP
   runtime dependencies in `release/reports/ios-release-runtime-dependencies.txt`,
   static engine boundary, and bundled notices. Resolve any license that is not
   GPLv3-compatible.
3. Run the normal tests and native-source/patch checks. Run Android machine and
   signed-artifact verification for Android, or the complete simulator,
   both-device, archive-inspection, and distribution validation gates for iOS.
   Preserve their reports.
4. Create the whole-project source archive from the same clean source identity
   used for the binary with `scripts/source-bundle.sh`. The audited publisher
   records only the immutable public tag identity, never a private repository
   commit or ref. It rejects a dirty input, unclassified paths, forbidden
   project/workflow material, unsafe links/types/names/collisions, credentials,
   signing material, private-only content tokens, and generated artifacts. It
   exports the exact locked Fairy-Stockfish staged blobs while excluding
   repository-local `.git` data, `.github` administration, and nested
   `AGENTS.md` instructions, creates complete native and project byte
   manifests, and requires two byte-identical builds made under different roots,
   umasks, and time zones. For iOS 1.0.2 (build 2), the public tag is
   `ios-v1.0.2-build-2`, the archive is
   `drawless-chess-ios-1.0.2-build-2-source.tar.gz`, and its only root has the
   same name without `.tar.gz`. Verify that it contains `iosApp/`, `ios-engine/`,
   `multiplatform/`, `iosApp/project.yml`, and the public Apple build/inspection
   scripts. Follow `release/public-source/REBUILD-IOS.md` from the extracted root.
5. Store the source archive SHA-256 beside the signed binary identity and release
   tag. For Android, record the APK/AAB SHA-256, version code/name, signing
   certificate digest, and native manifest. For iOS, record the uploaded archive
   or IPA SHA-256, version/build, code-signing identity, executable identity, and
   App Store Connect build identity. A source archive from a nearby or later tree
   is not an acceptable match.
6. Publish the source archive without requiring recipients to surrender GPL rights.
   Put its durable HTTPS location in the app's About/Open Source screen and the
   store listing or release notes. Verify the link while logged out.
7. Ship the GPL text, Apache-2.0 text where applicable, project NOTICE,
   third-party notices, and Fairy-Stockfish attribution in the application.
   Preserve upstream notices in redistributed source and binaries. Confirm the
   provenance of every visual and sound asset. Code-native pieces/icons and the
   project-owned All Hallows sculpture atlases are original project material;
   atlas prompts and hashes are under `artwork/all-hallows`. Sampled audio combines CC0 physical recordings with
   MIT-licensed ion.sound recordings. Preserve the complete ion.sound copyright
   and MIT notice in every Android APK/AAB, iOS application archive, and
   corresponding-source archive, and run the sampled-audio verifier before
   release. The iOS archive inspector must prove byte identity for `LICENSE`,
   `APACHE-2.0.txt`, `NOTICE`, `THIRD_PARTY_NOTICES.md`,
   `engine/native/SOURCE_NOTICE.txt`. `THIRD_PARTY_NOTICES.md` is also the
   authoritative bundled sampled-audio attribution resource.
8. Keep the source available for as long as the chosen GPL conveyance method
   requires. Prefer distributing source alongside every binary rather than relying
   on a written offer.
9. Confirm that Android and Apple signing, update, and device policies do not deny
   recipients rights the GPL requires. Document any installation information
   required for a covered User Product.

Do not set any `distributionAuthorized` field to true merely because this
checklist exists. Authorization requires an actual immutable source identity,
public source location, complete platform-appropriate notices/inventory, signing
setup, and passing release evidence.

This document is an engineering release control, not legal advice.
