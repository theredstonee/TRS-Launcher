#!/usr/bin/env bash
# TRS Launcher – legt die iOS-Spiel-Engine in die App und baut eine unsignierte IPA für
# AltStore/SideStore (ldid-Pseudosignatur mit den Entitlements; AltStore/SideStore signieren neu).
#
#   scripts/ios/package-ipa.sh <TRS.app | unsigned.ipa> <engine-ordner> <ausgabe.ipa>
#
# <engine-ordner> = Ergebnis von scripts/ios/build-engine.sh. Braucht macOS (plutil, PlistBuddy) und ldid.
# Entitlements: TRS_IOS_ENTITLEMENTS (Standard: ios/trs-game.entitlements des Spiel-Plugins).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IN="${1:?Aufruf: package-ipa.sh <App.app|App.ipa> <engine-ordner> <ausgabe.ipa>}"
ENGINE="${2:?engine-ordner fehlt}"
OUT="${3:?ausgabe.ipa fehlt}"
ENTITLEMENTS="${TRS_IOS_ENTITLEMENTS:-$ROOT/src-tauri/plugins/tauri-plugin-trs-game/ios/trs-game.entitlements}"

die() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }
command -v ldid >/dev/null || die "ldid fehlt (brew install ldid)"
[ -f "$ENGINE/engine.json" ] || die "$ENGINE ist kein Engine-Ordner (engine.json fehlt)"
[ -f "$ENTITLEMENTS" ] || die "Entitlements fehlen: $ENTITLEMENTS"
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -d "$IN" ]; then
  mkdir -p "$TMP/Payload"
  cp -R "$IN" "$TMP/Payload/"
else
  unzip -q "$IN" -d "$TMP"
fi
APP="$(find "$TMP/Payload" -maxdepth 1 -name '*.app' -type d | head -n 1)"
[ -n "$APP" ] || die "keine .app gefunden"
PLIST="$APP/Info.plist"
EXE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$PLIST")"

# --- Engine hinein ---
mkdir -p "$APP/Frameworks"
cp -R "$ENGINE/Frameworks/." "$APP/Frameworks/"
for dir in libs libs_caciocavallo libs_caciocavallo17; do
  rm -rf "${APP:?}/$dir"
  cp -R "$ENGINE/$dir" "$APP/$dir"
done
cp "$ENGINE"/resources/* "$APP/"
mkdir -p "$APP/licenses/engine"
cp -R "$ENGINE/LICENSES/." "$APP/licenses/engine/"

# --- Info.plist: Dateien-App (Logs, JIT-Skript), Querformat, Maus/Controller ---
set_bool() {
  /usr/libexec/PlistBuddy -c "Delete :$1" "$PLIST" >/dev/null 2>&1 || true
  /usr/libexec/PlistBuddy -c "Add :$1 bool true" "$PLIST"
}
set_bool UIFileSharingEnabled
set_bool LSSupportsOpeningDocumentsInPlace
set_bool UIApplicationSupportsIndirectInputEvents
set_bool GCSupportsControllerUserInteraction
set_bool GCSupportsGameMode
for key in UISupportedInterfaceOrientations "UISupportedInterfaceOrientations~ipad"; do
  /usr/libexec/PlistBuddy -c "Print :$key" "$PLIST" >/dev/null 2>&1 || /usr/libexec/PlistBuddy -c "Add :$key array" "$PLIST"
  for orientation in UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight; do
    if ! /usr/libexec/PlistBuddy -c "Print :$key" "$PLIST" | grep -q "$orientation"; then
      /usr/libexec/PlistBuddy -c "Add :$key: string $orientation" "$PLIST"
    fi
  done
done

# --- Pseudosignatur: erst alle eingebetteten Mach-O-Dateien, zuletzt die App mit Entitlements ---
rm -rf "$APP/_CodeSignature" "$APP/embedded.mobileprovision"
while IFS= read -r -d '' file; do
  [ "$file" = "$APP/$EXE" ] && continue
  if file "$file" | grep -q 'Mach-O'; then
    ldid -S "$file"
  fi
done < <(find "$APP" -type f -print0)
ldid -S"$ENTITLEMENTS" "$APP/$EXE"
chmod -R u+rwX,go+rX "$TMP/Payload"

# Kontrolle: Entitlements stehen in der App.
ldid -e "$APP/$EXE" | grep -q 'get-task-allow' || die "Entitlements fehlen in $EXE"

rm -f "$OUT"
( cd "$TMP" && zip -qry "$OUT" Payload )
echo "IPA: $OUT ($(du -h "$OUT" | cut -f1))"
