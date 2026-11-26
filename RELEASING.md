# Releasing

Aether releases from [`.github/workflows/release.yml`](.github/workflows/release.yml) to a GitHub Release with the Android APK and AAB, a desktop jar per OS and a zipped web build. The release and the snapshots share their build jobs in [`.github/workflows/build-artifacts.yml`](.github/workflows/build-artifacts.yml). Like CI, they only run while the repository is public.

## Snapshots

[`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml) runs after CI passes on a push to `main`. It builds that commit and replaces the rolling `snapshot` prerelease and its tag, with files named `X.Y.Z-SNAPSHOT-<sha>` after the manifest version and notes that list the unreleased changelog. A newer push cancels an older snapshot build. `gh workflow run snapshot.yml --repo meshtastic/meshtastic-aether` builds `main` on demand. Snapshots build against the meshtastic-sdk snapshot the catalog pins, like every build.

## Version

One version, `X.Y.Z`, lives in every platform manifest: `versionName` and `versionCode` in `androidApp/module.yaml`, and `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `iosApp/module.xcodeproj/project.pbxproj`. [`scripts/version.sh`](scripts/version.sh) writes them (`set X.Y.Z`), checks them (`check X.Y.Z`) and prints them (`show`). The build number is `MAJOR * 1000000 + MINOR * 1000 + PATCH`, so it rises with every release. Desktop and web carry no version of their own. The workflow creates the annotated `vX.Y.Z` tag, so nobody tags by hand.

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
