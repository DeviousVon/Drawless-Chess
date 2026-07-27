# Project Rules

## Upload-key and signing-certificate safety

The following rule is absolute for this project:

- **NEVER generate, create, rotate, replace, upgrade, or reset an Android/Google Play upload key or upload certificate unless Bob explicitly and specifically authorizes that exact action.**
- **NEVER request, submit, cancel, or modify a Google Play upload-key reset unless Bob explicitly and specifically authorizes that exact action.**
- General authorization to build, sign, publish, upload, update Google Play, finish a release, recover access, find credentials, avoid prompting, or act autonomously does **not** authorize any upload-key or upload-certificate change.
- The presence of an inaccessible keystore, missing password, certificate mismatch, failed signing attempt, release deadline, or blocked upload does **not** relax this rule.
- When the existing signing material cannot be used, preserve all existing keys and certificates, perform only read-only diagnosis, stop before any signing-identity change, and report the precise blocker to Bob.
- Existing signing material may be used only as-is. Never reveal signing secrets in logs, commits, documentation, chat, or command output.

This rule applies to every tool and workflow, including Google Play Console, `keytool`, `jarsigner`, Gradle signing configuration, OpenSSL, scripts, CI, and credential-recovery work.

## Test-device installation

- Whenever a new Android test build is ready for device review, install it on both of Bob's designated test devices: the Pixel phone and the R6 tablet.
- Launch and verify the newly installed build on both devices before reporting the test version complete.
- Preserve the production app, its data, and its signing identity. Use the separately packaged debug/test app unless Bob explicitly requests a different installation.
- If either device is disconnected or unauthorized, report that clearly and do not imply that both installations succeeded.
