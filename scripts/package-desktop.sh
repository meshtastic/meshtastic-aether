#!/usr/bin/env bash
# Wrap the desktop executable jar in this OS's native installer: .deb on Linux, .dmg on macOS, .msi on Windows.
#
#   scripts/package-desktop.sh <label> <platform-id> <executable-jar> <out-dir>
#
# <label> is the version shown to people (0.1.0 or 0.1.0-SNAPSHOT-abc1234) and <platform-id> the suffix the jar
# carries (linux-x64, macos-arm64, windows-x64). The installer is out-dir/aether-<label>-desktop-<platform-id>.<ext>.
# It bundles a jlinked JetBrains Runtime, found in the Kotlin Toolchain's cache (run `./kotlin package` first, so the
# Toolchain has downloaded it) or at $AETHER_JDK, which must be a JBR SDK with jlink and jpackage.
# On macOS the dmg carries the Bluetooth and local-network usage strings and is signed ad hoc.
# jpackage needs a numeric version: the leading X.Y.Z of <label>, with the major raised by one on macOS, which rejects 0.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)

[ $# -eq 4 ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
label=$1 id=$2 jar=$3 out=$4
[[ $label =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)([0-9A-Za-z.+-]*)$ ]] || { echo "not a label: '$label'" >&2; exit 2; }
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
[ -f "$jar" ] || { echo "no jar at $jar" >&2; exit 2; }

case $id in
  linux-*) type=deb; ext=deb; icon=$root/desktopApp/resources/icon.png ;;
  macos-*) type=dmg; ext=dmg; icon=$root/desktopApp/packaging/icon.icns; major=$((10#$major + 1)) ;;
  windows-*) type=msi; ext=msi; icon=$root/desktopApp/packaging/icon.ico ;;
  *) echo "not a platform id: '$id'" >&2; exit 2 ;;
esac
version="$((10#$major)).$((10#$minor)).$((10#$patch))"

find_jdk() {
  if [ -n "${AETHER_JDK:-}" ]; then echo "$AETHER_JDK"; return; fi
  local roots=() r tool
  [ -n "${KOTLIN_SHARED_CACHE_DIR:-}" ] && roots+=("$KOTLIN_SHARED_CACHE_DIR")
  roots+=("$HOME/.cache/JetBrains/Kotlin" "$HOME/Library/Caches/JetBrains/Kotlin")
  [ -n "${LOCALAPPDATA:-}" ] && roots+=("$LOCALAPPDATA/JetBrains/Kotlin")
  for r in "${roots[@]}"; do
    [ -d "$r" ] || continue
    tool=$(find "$r" -path '*jbrsdk*' -path '*/bin/jpackage*' 2>/dev/null | sort | tail -1)
    [ -n "$tool" ] && { dirname "$(dirname "$tool")"; return; }
  done
  echo "no JBR SDK found: run ./kotlin package first, or set AETHER_JDK" >&2
  return 1
}
jdk=$(find_jdk)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/in" "$out"
cp "$jar" "$work/in/aether.jar"

# What the Compose desktop app and the SDK transports load; `jdeps` over the jar's libraries is the check for a new one.
modules=java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.sql,jdk.unsupported,jdk.crypto.ec
"$jdk/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages --output "$work/runtime"

common=(--input "$work/in" --main-jar aether.jar --runtime-image "$work/runtime"
  --name Aether --app-version "$version" --vendor "Meshtastic LLC" --copyright "Meshtastic LLC"
  --description "Generative ambient music and visuals from live mesh traffic" --icon "$icon"
  --java-options --enable-native-access=ALL-UNNAMED --dest "$work/dist")

# jpackage names each dependency by running `dpkg -S` on the build host, so a deb built on Ubuntu 24.04 or later
# requires libasound2t64 and libpng16-16t64, which Debian 12 and Raspberry Pi OS lack; the plain names resolve on both.
fix_deb_depends() {
  local deb=$1 tree=$work/debtree
  dpkg-deb -R "$deb" "$tree"
  sed -i -E '/^(Depends|Pre-Depends|Recommends):/ s/(lib[a-z0-9.+-]*)t64([,( ]|$)/\1\2/g' "$tree/DEBIAN/control"
  ! grep -E '^(Depends|Pre-Depends|Recommends):.*t64' "$tree/DEBIAN/control" || { echo "a t64 dependency survived" >&2; return 1; }
  dpkg-deb -b --root-owner-group "$tree" "$deb" >/dev/null
}

case $type in
  deb)
    "$jdk/bin/jpackage" --type deb "${common[@]}" --license-file "$root/LICENSE" --linux-package-name aether --linux-shortcut \
      --linux-menu-group "Audio;Network" --linux-app-category sound --linux-deb-maintainer developers@meshtastic.org
    fix_deb_depends "$(find "$work/dist" -maxdepth 1 -name '*.deb')"
    ;;
  dmg)
    # The Bluetooth and local-network strings are keys jpackage cannot add, and macOS ends the app on its first BLE use
    # without them, so build the bundle, add them, re-sign it ad hoc, and wrap that in the dmg.
    "$jdk/bin/jpackage" --type app-image "${common[@]}" --mac-package-name Aether --mac-package-identifier org.meshtastic.aether \
      --mac-app-category utilities
    plist=$work/dist/Aether.app/Contents/Info.plist
    /usr/libexec/PlistBuddy -c "Add :NSBluetoothAlwaysUsageDescription string Aether uses Bluetooth to listen to a Meshtastic radio." "$plist"
    /usr/libexec/PlistBuddy -c "Add :NSLocalNetworkUsageDescription string Aether uses your local network to reach a Meshtastic node over Wi-Fi." "$plist"
    codesign --force --deep --sign - "$work/dist/Aether.app"
    "$jdk/bin/jpackage" --type dmg --app-image "$work/dist/Aether.app" --name Aether --app-version "$version" \
      --mac-package-name Aether --mac-package-identifier org.meshtastic.aether --dest "$work/dmg"
    mv "$work"/dmg/*.dmg "$work/dist/"
    ;;
  msi)
    "$jdk/bin/jpackage" --type msi "${common[@]}" --win-menu --win-menu-group Meshtastic --win-shortcut --win-dir-chooser \
      --win-upgrade-uuid 6f3b8f0e-2d4a-4c57-9b1e-7a5d3c2e8f41 # WiX shows a license as RTF, so LICENSE is left out
    ;;
esac

built=$(find "$work/dist" -maxdepth 1 -name "*.$ext")
[ "$(echo "$built" | wc -l)" -eq 1 ] && [ -n "$built" ] || { echo "expected one .$ext in $work/dist, got: $built" >&2; exit 1; }
cp "$built" "$out/aether-$label-desktop-$id.$ext"
echo "packaged $out/aether-$label-desktop-$id.$ext (app version $version)"
