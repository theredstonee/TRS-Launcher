#!/usr/bin/env bash
# Unsigniertes IPA der iOS-App: Xcode-Archiv ohne Code-Signatur, die .app als Payload/ gezippt.
# Die Rust-Bibliothek baut die Build-Phase des Xcode-Projekts (`tauri ios xcode-script`).
# AltStore/SideStore signieren beim Installieren mit der Apple-ID des Nutzers.
#
#   scripts/ios-unsigned-ipa.sh <debug|release> <ziel.ipa>
set -euo pipefail
config="${1:?debug oder release}"
out="${2:?Ziel-IPA}"
case "$config" in debug|release) ;; *) echo "Konfiguration muss debug oder release sein" >&2; exit 1 ;; esac
cd "$(dirname "$0")/.."

proj=$(ls -d src-tauri/gen/apple/*.xcodeproj | head -1)
scheme="$(basename "$proj" .xcodeproj)_iOS"
work=$(mktemp -d)
archive="$work/trs-launcher.xcarchive"

xcodebuild -project "$proj" -scheme "$scheme" -configuration "$config" \
  -sdk iphoneos -destination 'generic/platform=iOS' -archivePath "$archive" \
  ARCHS=arm64 CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" DEVELOPMENT_TEAM="" \
  archive

app=$(ls -d "$archive"/Products/Applications/*.app | head -1)
mkdir -p "$work/Payload"
cp -R "$app" "$work/Payload/"
(cd "$work" && zip -qry app.ipa Payload)
mkdir -p "$(dirname "$out")"
mv "$work/app.ipa" "$out"
ls -la "$out"
