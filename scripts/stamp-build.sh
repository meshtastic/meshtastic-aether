#!/usr/bin/env bash
# Stamp a CI build with what it is, before it is built.
#
#   scripts/stamp-build.sh <label> <code> <channel>
#
# <label> is the version shown to people (0.1.0, or 0.1.0-SNAPSHOT-abc1234), <code> the commit count of the built
# commit, and <channel> release, snapshot or dev. It writes the Android versionName and versionCode into
# androidApp/module.yaml and the same three values into shared/src/update/BuildInfo.kt, which the update check reads.
# The code rises with every commit on main, so every snapshot and release installs over the one before on Android.
# Never commit the result: a local build keeps the dev values.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
android="$root/androidApp/module.yaml"
info="$root/shared/src/update/BuildInfo.kt"

[ $# -eq 3 ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
label=$1 code=$2 channel=$3
[[ $label =~ ^[0-9A-Za-z.+-]+$ ]] || { echo "not a label: '$label'" >&2; exit 2; }
[[ $code =~ ^[1-9][0-9]*$ ]] || { echo "not a build code: '$code'" >&2; exit 2; }
case $channel in
  release) enum=RELEASE ;;
  snapshot) enum=SNAPSHOT ;;
  dev) enum=DEV ;;
  *) echo "not a channel: '$channel' (want release, snapshot or dev)" >&2; exit 2 ;;
esac

sed -i.bak -e "s/^    versionName: .*/    versionName: $label/" -e "s/^    versionCode: .*/    versionCode: $code/" "$android"
sed -i.bak -e "s/^    const val VERSION: String = .*/    const val VERSION: String = \"$label\"/" \
  -e "s/^    const val CODE: Long = .*/    const val CODE: Long = $code/" \
  -e "s/^    val CHANNEL: Channel = .*/    val CHANNEL: Channel = Channel.$enum/" "$info"
rm -f "$android.bak" "$info.bak"

grep -q "versionCode: $code\$" "$android" && grep -q "versionName: $label\$" "$android" \
  && grep -q "VERSION: String = \"$label\"" "$info" && grep -q "CODE: Long = $code\$" "$info" \
  && grep -q "CHANNEL: Channel = Channel.$enum\$" "$info" \
  || { echo "stamping did not take; check the patterns against $android and $info" >&2; exit 1; }
echo "stamped $label (code $code, $channel)"
