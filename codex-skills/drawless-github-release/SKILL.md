---
name: drawless-github-release
description: Publish or update a Drawless Chess release on GitHub from C:\src. Use when Bob asks to update GitHub, push the release version, publish a GitHub release, tag a Drawless Chess version, attach the signed AAB or corresponding source, or verify the public GitHub release.
---

# Drawless Chess GitHub release

Own the GitHub workflow end to end. Do not ask Bob for repository names, artifact paths,
tagging steps, or authentication procedures that can be discovered or handled locally.

## Fixed project context

- Repository: `DeviousVon/Drawless-Chess`
- Working tree: `C:\src`
- Release tags: `v<version>`
- Signed AAB: `android/app/build/outputs/bundle/release/app-release.aab`
- Verification evidence: `build/release-evidence/play-aab.json`
- Corresponding source: read `sourceArchive.file` from the evidence and resolve it under `release/`
- Release notes: prefer `build/release-evidence/github-release-notes.md`; regenerate them when
  their commit, version, or hashes do not match the candidate.

Read [references/project.md](references/project.md) when publishing or diagnosing GitHub state.
Run [scripts/Test-ReleaseInputs.ps1](scripts/Test-ReleaseInputs.ps1) before any external write.

## Authorization

Treat Bob's direct request to “update GitHub”, “publish the release”, or equivalent as
authorization to push the intended release branch, open or update its PR, merge it after
checks pass, create/push the exact annotated tag, and publish the GitHub Release and assets.
Do not ask him to perform routine GitHub UI or CLI steps.

Do not infer authorization for unrelated repository changes, deleting releases/tags,
rewriting history, bypassing branch protection, or publishing signing secrets.

## Workflow

1. Inspect `AGENTS.md`, `git status --short --branch`, the current diff, branch/upstream, and
   active concurrent work. Preserve unrelated user edits. Never stage blindly.
2. Determine the exact release commit and version from the committed candidate and
   `play-aab.json`. Require the evidence commit, AAB hash, source-archive hash, app ID, version,
   signature result, ABI set, and source correspondence to agree.
3. Run the preflight script from the repository root:

   ```powershell
   pwsh -NoProfile -File <skill>/scripts/Test-ReleaseInputs.ps1 -RepositoryRoot C:\src
   ```

4. Review and stage only the intended files. Run `git diff --check`, a targeted secret scan,
   and proportionate tests. Commit with a release-focused message. Re-run the signed artifact
   verifier if the release source commit changed.
5. Authenticate without exposing credentials. Prefer the connected GitHub app for PR metadata
   and merge operations. If `gh` needs authentication, obtain the existing GitHub credential
   from Git Credential Manager only in process memory, set `GH_TOKEN` temporarily, verify the
   login is `DeviousVon`, and clear all token-bearing variables in `finally`.
6. Push the release branch. Create or update a PR into the repository default branch, mark it
   ready, require applicable checks, and merge it without force. Verify the expected source
   commit is reachable from the updated default branch.
7. Create annotated tag `v<version>` at the exact evidence commit and push that exact ref.
   If the tag already exists, require its peeled commit to match; never move a published tag.
8. Publish a non-draft, non-prerelease GitHub Release marked latest. Attach:

   - `drawless-chess-<version>.aab`, copied from the verified signed AAB
   - `drawless-chess-<version>-source.tar.gz`, the exact verified corresponding source

9. Verify through GitHub independently: release URL, tag target, draft/prerelease/latest state,
   both asset names, sizes, and SHA-256 digests. A successful local command is not completion.
10. Report the release URL, commit, tag, asset digests, and any gate that remains elsewhere.

## Idempotence and recovery

- Re-running against an existing matching PR, tag, release, or asset is success after live
  verification; do not create duplicates.
- If an existing tag or public asset differs from the candidate, stop and report the exact
  mismatch. Do not overwrite published history without explicit authorization.
- If GitHub authentication is stale, repair or refresh the existing account session. Never ask
  Bob how GitHub works and never create a new GitHub identity.
- GitHub, Google Play, website deployment, and device verification are separate gates. Report
  each independently.

## Absolute safety rules

- Never commit, upload, print, or attach a keystore, private key, certificate private material,
  signing properties, passwords, access tokens, or credential-manager output.
- Never generate, rotate, replace, or reset the Android upload identity.
- Never use force-push, delete a tag/release, or replace a mismatched published asset unless Bob
  explicitly authorizes that exact destructive action.
