#!/usr/bin/env bash
# Print the update.properties a release carries, which the app's update check reads.
#
#   scripts/update-manifest.sh <label> <code> <tag> <file>...
#
# Each file whose name ends in -android.apk (a signed APK; an -unsigned one is left out, since Android refuses it over
# a signed install), -desktop-linux-x64.jar or -desktop-macos-arm64.jar is listed under its platform key with its
# SHA-256. Other files are ignored.
set -euo pipefail

[ $# -ge 3 ] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
label=$1 code=$2 tag=$3
shift 3
[[ $code =~ ^[1-9][0-9]*$ ]] || { echo "not a build code: '$code'" >&2; exit 2; }
[[ $tag =~ ^(snapshot|v[0-9]+\.[0-9]+\.[0-9]+)$ ]] || { echo "not a tag: '$tag'" >&2; exit 2; }

echo "version=$label"
echo "code=$code"
echo "tag=$tag"
for file in "$@"; do
  name=$(basename "$file")
  case $name in
    *-android.apk) key=android ;;
    *-desktop-linux-x64.jar) key=desktop-linux-x64 ;;
    *-desktop-macos-arm64.jar) key=desktop-macos-arm64 ;;
    *) continue ;;
  esac
  sha=$( (sha256sum "$file" 2>/dev/null || shasum -a 256 "$file") | cut -d' ' -f1)
  echo "$key=$name"
  echo "$key.sha256=$sha"
done
