# GitHub project reference

## Stable identity

- GitHub repository: `DeviousVon/Drawless-Chess`
- Local repository: resolve from the current checkout; do not record machine-specific paths
- Tag convention: `v<version>`
- Release title convention: `Drawless Chess <version>`
- Android package: `com.drawlesschess`
- Release evidence: `build/release-evidence/play-aab.json`
- Signed bundle: `android/app/build/outputs/bundle/release/app-release.aab`

## Authentication

Prefer the connected GitHub app. When its coverage is insufficient, use `gh` with the existing
credential. Git Credential Manager can supply a token to the process without printing it:

1. Pipe `protocol=https`, `host=github.com`, and a terminating blank line to
   `git credential fill`.
2. Parse the returned `password` only in memory.
3. Set `GH_TOKEN`, verify `gh api user --jq .login` equals `DeviousVon`, perform the calls, then
   clear the token and parsed credential data in `finally`.

Do not echo the credential response, token, passwords, or private signing material.

## Expected public verification

Use both the GitHub Release API and Git refs. Confirm:

- `refs/tags/v<version>^{}` is the evidence commit.
- The release is published, not a draft or prerelease, and is marked latest when intended.
- The AAB and corresponding-source assets exist with their release-specific names.
- GitHub-reported SHA-256 digests match local `Get-FileHash` values.
- The release URL is `https://github.com/DeviousVon/Drawless-Chess/releases/tag/v<version>`.
