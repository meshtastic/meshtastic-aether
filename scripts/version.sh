#!/usr/bin/env bash
# Stamp one app version into every platform manifest, or check that they agree.
#
#   scripts/version.sh set <X.Y.Z>     write versionName (Android) and MARKETING_VERSION/CURRENT_PROJECT_VERSION (iOS)
#   scripts/version.sh check <X.Y.Z>   fail unless every manifest carries <X.Y.Z> and its code
#   scripts/version.sh show            print the version each manifest carries
#
# The iOS build number is MAJOR * 1000000 + MINOR * 1000 + PATCH, so it rises with every release, as the App Store
# requires. The Android versionCode is the commit count, which CI stamps with scripts/stamp-build.sh, so snapshots
# install over each other too. Desktop and web carry no version of their own.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
android="$root/androidApp/module.yaml"
ios="$root/iosApp/module.xcodeproj/project.pbxproj"

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

code_for() {
  local major minor patch
  IFS=. read -r major minor patch <<<"$1"
  echo $((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))
}

valid() {
  [[ $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a version: '$1' (want X.Y.Z)" >&2; exit 2; }
  local _ minor patch
  IFS=. read -r _ minor patch <<<"$1"
  ((10#$minor < 1000 && 10#$patch < 1000)) || { echo "minor and patch must be under 1000: $1" >&2; exit 2; }
}

android_name() { sed -n 's/^    versionName: *\(.*\)$/\1/p' "$android" | head -1; }
ios_names() { sed -n 's/.*MARKETING_VERSION = \([^;]*\);.*/\1/p' "$ios" | sort -u; }
ios_codes() { sed -n 's/.*CURRENT_PROJECT_VERSION = \([^;]*\);.*/\1/p' "$ios" | sort -u; }

set_version() {
  local version=$1 code
  code=$(code_for "$version")
  sed -i.bak -e "s/^    versionName: .*/    versionName: $version/" "$android"
  sed -i.bak -e "s/MARKETING_VERSION = [^;]*;/MARKETING_VERSION = $version;/" \
    -e "s/CURRENT_PROJECT_VERSION = [^;]*;/CURRENT_PROJECT_VERSION = $code;/" "$ios"
  rm -f "$android.bak" "$ios.bak"
  echo "set $version (build $code)"
}

check_version() {
  local version=$1 code bad=0
  code=$(code_for "$version")
  [ "$(android_name)" = "$version" ] || { echo "androidApp/module.yaml versionName is '$(android_name)', want $version" >&2; bad=1; }
  [ "$(ios_names)" = "$version" ] || { echo "iOS MARKETING_VERSION is '$(ios_names | tr '\n' ' ')', want $version" >&2; bad=1; }
  [ "$(ios_codes)" = "$code" ] || { echo "iOS CURRENT_PROJECT_VERSION is '$(ios_codes | tr '\n' ' ')', want $code" >&2; bad=1; }
  [ $bad -eq 0 ] && echo "every manifest carries $version (build $code)"
  return $bad
}

[ $# -ge 1 ] || usage
case $1 in
  set) [ $# -eq 2 ] || usage; valid "$2"; set_version "$2" ;;
  check) [ $# -eq 2 ] || usage; valid "$2"; check_version "$2" ;;
  show) echo "android: $(android_name)"; echo "ios: $(ios_names | tr '\n' ' ')($(ios_codes | tr '\n' ' '))" ;;
  *) usage ;;
esac
