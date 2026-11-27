# Releasing

Aether releases from [`.github/workflows/release.yml`](.github/workflows/release.yml) to a GitHub Release with the Android APK and AAB, a desktop jar per OS and a zipped web build. The release and the snapshots share their build jobs in [`.github/workflows/build-artifacts.yml`](.github/workflows/build-artifacts.yml).

## Snapshots

[`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml) runs after CI passes on a push to `main`. It refuses a commit that is not on `main`, builds it and moves the rolling `snapshot` prerelease and its tag to it in place, with files named `X.Y.Z-SNAPSHOT-<sha>` after the manifest version and notes that list the unreleased changelog. The new files go up first, then `update.properties`, then the previous build's files are deleted, so the update check never finds the manifest missing or naming a file not yet uploaded. A newer push cancels an older snapshot build. `gh workflow run snapshot.yml --repo meshtastic/meshtastic-aether` builds `main` on demand. Snapshots build against the meshtastic-sdk snapshot the catalog pins, like every build.

## Version

One version, `X.Y.Z`, lives in every platform manifest: `versionName` in `androidApp/module.yaml`, and `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `iosApp/module.xcodeproj/project.pbxproj`. [`scripts/version.sh`](scripts/version.sh) writes them (`set X.Y.Z`), checks them (`check X.Y.Z`) and prints them (`show`). The iOS build number is `MAJOR * 1000000 + MINOR * 1000 + PATCH`, so it rises with every release. Desktop and web carry no version of their own.

Every CI build carries its label (`X.Y.Z` or `X.Y.Z-SNAPSHOT-<sha>`), its build code and its update channel: `plugins/build-info` writes them into `BuildInfo` from the `AETHER_BUILD_*` variables, and [`scripts/stamp-build.sh`](scripts/stamp-build.sh) stamps the label and code into the Android manifest. The build code is the commit count of the built commit: it is the Android `versionCode`, so every snapshot and release installs over the one before, and the update check compares it. A release and the snapshot of the same commit share a code. A dry run is stamped with the `dev` channel, so its files never offer updates. The workflow creates the annotated `vX.Y.Z` tag, so nobody tags by hand.

## Web deploys

Every snapshot deploys its web build to aether-snapshot.meshtastic.org (`deploy-web` in `snapshot.yml`), and a release dispatches `web-stable.yml`, which deploys the release's web zip to aether.meshtastic.org. Both go through [`.github/actions/vercel-deploy`](.github/actions/vercel-deploy/action.yml) as prebuilt static output. Each job runs in a GitHub environment (`web-snapshot`, `web-stable`) that admits only `main` and holds the `VERCEL_TOKEN` secret and the `VERCEL_ORG_ID` and `VERCEL_PROJECT_ID` variables for its Vercel project. To retry or roll back the stable site, run `gh workflow run web-stable.yml --repo meshtastic/meshtastic-aether -f tag=vX.Y.Z`.

## Changelog

Changes add fragments to [`changelog.d/`](changelog.d/README.md); `CHANGELOG.md` is only edited by the cut.

## Secrets

The Android files are signed when these repository secrets exist, and ship unsigned with a warning otherwise: `ANDROID_KEYSTORE_BASE64` (the release keystore, base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

## Cutting a release

1. Pick `X.Y.Z`: a minor bump for new behavior, a patch for fixes only. Before 1.0 nothing is a promise.
2. On a branch, run `scripts/changelog.sh preview`, then `scripts/changelog.sh cut X.Y.Z`. It folds every fragment into `## [Unreleased]`, deletes them and moves the section under a dated `## [X.Y.Z]` heading. It refuses an empty `[Unreleased]`.
3. Run `scripts/version.sh set X.Y.Z`.
4. Commit `chore(release): X.Y.Z`, signed off, open the PR and merge it.
5. Run `gh workflow run release.yml --repo meshtastic/meshtastic-aether -f version=X.Y.Z`. Add `-f dry_run=true` to build every artifact without tagging or releasing; a dry run can start from any branch. Pushing a `vX.Y.Z` tag on `main` runs the same workflow.

## What the workflow checks, in order

1. The version is `X.Y.Z` and, unless it is a dry run, the commit is on `main`.
2. Any existing `vX.Y.Z` tag points at this commit.
3. Every manifest carries `X.Y.Z` (`scripts/version.sh check`).
4. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section. It becomes the GitHub Release body verbatim.
5. The whole CI workflow passes on this commit, the iOS simulator tests included.
6. It builds the release APK and AAB (and checks the APK's `versionName`), a desktop executable jar on Linux and on macOS (each jar bundles its own OS's native libraries), and the web build.
7. It tags `vX.Y.Z` and publishes the GitHub Release with every file. A dry run stops before this step and leaves the files as workflow artifacts.

The iOS app is not attached: an `.ipa` needs a signing identity and provisioning profile.

## Updates

Every release and the snapshot carry `update.properties` ([`scripts/update-manifest.sh`](scripts/update-manifest.sh)): the label, the build code, the tag, and per platform the file and its SHA-256. Only a signed APK is listed, since Android refuses an unsigned one over a signed install. A release build reads `releases/latest/download/update.properties`, which skips prereleases; a snapshot build reads the `snapshot` release's. Android verifies the digest, the package name, the signing key and a higher `versionCode` before handing the APK to the system installer, and does nothing for a Play, F-Droid or Amazon install. The desktop jar verifies the digest, keeps the old jar as `<jar>.bak`, swaps the new one in and restarts. The Android build declares `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Google Play restricts the first: an AAB uploaded to Play needs it removed.
