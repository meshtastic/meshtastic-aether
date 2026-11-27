#!/usr/bin/env bash
# Stamp a CI Android build with its version, before it is built.
#
#   scripts/stamp-build.sh <label> <code>
#
# <label> is the version shown to people (0.1.0, or 0.1.0-SNAPSHOT-abc1234) and <code> the commit count of the built
# commit. It writes them as the Android versionName and versionCode into androidApp/module.yaml, which takes no
# variables. The code rises with every commit on main, so every snapshot and release installs over the one before.
# BuildInfo carries the same values from the AETHER_BUILD_* variables (plugins/build-info).
# Never commit the result: a local build keeps the dev values.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
android="$root/androidApp/module.yaml"

[ $# -eq 2 ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
label=$1 code=$2
[[ $label =~ ^[0-9A-Za-z.+-]+$ ]] || { echo "not a label: '$label'" >&2; exit 2; }
[[ $code =~ ^[1-9][0-9]*$ ]] || { echo "not a build code: '$code'" >&2; exit 2; }

sed -i.bak -e "s/^    versionName: .*/    versionName: $label/" -e "s/^    versionCode: .*/    versionCode: $code/" "$android"
rm -f "$android.bak"

grep -q "versionCode: $code\$" "$android" && grep -q "versionName: $label\$" "$android" \
  || { echo "stamping did not take; check the patterns against $android" >&2; exit 1; }
echo "stamped $label (code $code)"
