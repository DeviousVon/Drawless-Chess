# GPL release and corresponding-source gate

## Project licensing decision

Drawless Chess, including the Android application that links the modified
Fairy-Stockfish engine through JNI, is distributed under
GPL-3.0-or-later. Charging once for the app is compatible with that decision;
recipients still receive the freedoms to study, modify, and redistribute it.
No technical process boundary is being used as a licensing workaround.

`LICENSE` is the authoritative project license. `NOTICE` explains the source
promise and high-level attributions. `THIRD_PARTY_NOTICES.md` and the CycloneDX
SBOM record the exact resolved Android runtime inventory. Files carrying a
different license or third-party notice keep that license.

The GPL copyright license does not authorize a fork to misrepresent itself as an
official or endorsed release. “Drawless Chess” and the project logo are source
identifiers even though no trademark registration is claimed here. Forks retain
all GPL rights and may make truthful attribution, but release review should keep
branding/confusion questions separate from code and asset copyright licensing.

## What complete corresponding source means here

For the exact APK or App Bundle, publish everything a recipient needs to rebuild
and modify the work, including:

- the Android app, core, engine adapter, CMake/JNI bridge, rules, tests, and build
  scripts;
- the exact modified Fairy-Stockfish tree, its upstream identity, the ordered
  Drawless patches, and variant configuration;
- Gradle wrapper material, dependency declarations and locks, schemas, contracts,
  and interface-definition material;
- scripts used to configure, compile, package, install, and verify the binary;
- any release-specific source changes and any other material required by GPLv3's
  definition of Corresponding Source.

Build products, caches, local SDK paths, signing keys, credentials, device logs,
and private service configuration are neither source nor safe release content.
The source bundler excludes or rejects them.

## Exact-release checklist

The successful Android machine gate is engineering evidence, not permission to
publish. Before each public release:

1. Put the complete project in a version-controlled, reviewable state. Record an
   immutable release tag or source revision. If repository identity is unavailable,
   public distribution remains blocked even if an APK builds.
2. Resolve the exact release dependency graph. Review licenses and generate a
   complete third-party notice/SBOM for the resolved artifacts, including
   transitives. Resolve any license that is not GPLv3-compatible.
3. Run the normal tests, native-source/patch checks, Android machine verification,
   and signed-release verification. Preserve their reports.
4. Create the whole-project source archive from the same clean Git commit used for
   the binary. The bundler records that commit in `SOURCE-COMMIT` and refuses a
   dirty tree:

   ```bash
   scripts/source-bundle.sh release/drawless-chess-0.1.0-source.tar.gz
   ```

   `scripts/native-source-bundle.sh` is a compatibility alias and produces the
   same whole-project archive; it is no longer a native-only compliance artifact.
5. Store the source archive SHA-256 beside the signed APK/AAB SHA-256, version code,
   version name, signing-certificate digest, native manifest, and release tag.
   A source archive created from a nearby or later tree is not an acceptable match.
6. Publish the source archive without requiring recipients to surrender GPL rights.
   Put its durable HTTPS location in the app's About/Open Source screen and the
   store listing or release notes. Verify the link while logged out.
7. Ship the GPL text, project NOTICE, third-party notices, and Fairy-Stockfish
   attribution in the application. Preserve upstream notices in redistributed
   source and binaries. Confirm the provenance of every visual and sound asset.
   Current code-native pieces/icons are original; sampled audio combines CC0 chess
   and firework recordings with MIT-licensed ion.sound recordings. Preserve the complete ion.sound
   copyright and MIT notice in every APK/App Bundle and corresponding-source archive,
   and run the sampled-audio verifier before release.
8. Keep the source available for as long as the chosen GPL conveyance method
   requires. Prefer distributing source alongside every binary rather than relying
   on a written offer.
9. Confirm that signing, update, and device policies do not deny recipients rights
   the GPL requires. Document any installation information required for a covered
   User Product.

Do not set any `distributionAuthorized` field to true merely because this checklist
exists. Authorization requires an actual immutable source identity, public source
location, complete notices/SBOM, signing setup, and passing release evidence.

This document is an engineering release control, not legal advice.
