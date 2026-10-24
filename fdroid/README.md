# F-Droid

This folder keeps the F-Droid recipe of Night Fleet. The recipe F-Droid uses lives in the `fdroiddata` repository (GitLab); `pt.tiago.nightfleet.yml` here is the draft that is copied there. The description, icon, feature graphic, screenshots and changelogs come from `fastlane/metadata/android/`.

F-Droid builds the app from source, checks that the result is identical to the APK attached to the GitHub release, and only then publishes that APK, signed by the author. So the build must be reproducible, and `AllowedAPKSigningKeys` must match the real signing certificate.

## Files

| File | Purpose |
|---|---|
| `pt.tiago.nightfleet.yml` | Draft of the `fdroiddata` recipe |
| `README.md` | This document |

## Releasing a new version

1. Raise `versionCode` (always up: 1, 2, 3…) and set `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and `fastlane/metadata/android/pt-PT/changelogs/<versionCode>.txt`, at most 500 characters each, in the same commit.
3. Update `README.md` and `README.pt-PT.md` if features or screenshots changed.
4. Run the local checks and read their real exit codes:
   ```bash
   bash tools/verify.sh          # tests, lint, debug APK, permissions and dependencies
   bash tools/repro_check.sh     # two clean clones of HEAD; must print REPRODUCIBLE
   ```
5. Commit, create the tag `v<versionName>` and push it to GitHub.
6. `.github/workflows/release.yml` runs on the tag. It:
   - checks that the tag equals `v<versionName>`;
   - runs `bash tools/verify.sh`;
   - builds `:app:assembleRelease` signed with the secrets `SIGNING_KEYSTORE_B64`, `SIGNING_PASSWORD` and `SIGNING_KEY_ALIAS`;
   - renames the APK to `NightFleet-v<versionName>.apk` and publishes the GitHub release with the en-US changelog.
7. In `fdroiddata`, update the recipe (`Builds`, `CurrentVersion`, `CurrentVersionCode`), or open a merge request for the first submission. F-Droid notices the tag (`UpdateCheckMode: Tags`), builds it and checks the result.

The keystore and its passwords never enter the repository (see `.gitignore`). If the key is lost, the app can never be updated again.
