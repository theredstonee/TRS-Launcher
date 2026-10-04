#!/usr/bin/env bash
# Unsigniertes IPA der iOS-App: Xcode-Archiv ohne Code-Signatur, die .app als Payload/ gezippt.
# Die Rust-Bibliothek bauen wir hier selbst: Die Build-Phase des Xcode-Projekts
# (`tauri ios xcode-script`) braucht einen laufenden `tauri ios build` und wird deshalb abgeschaltet.
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

# Rust-Bibliothek für iOS (arm64) bauen und dort ablegen, wo das Xcode-Projekt sie erwartet.
cargo_flags=()
if [ "$config" = release ]; then cargo_flags+=(--release); fi
# Die iOS-Überlagerung der Konfiguration mischt sonst der Tauri-Befehl ein.
export TAURI_CONFIG="$(cat src-tauri/tauri.ios.conf.json)"
cargo build --manifest-path src-tauri/Cargo.toml --lib --target aarch64-apple-ios ${cargo_flags[@]+"${cargo_flags[@]}"}
target_dir="${CARGO_TARGET_DIR:-src-tauri/target}"
lib="$target_dir/aarch64-apple-ios/$config/libtrs_launcher_lib.a"
[ -f "$lib" ] || { echo "Rust-Bibliothek fehlt: $lib" >&2; exit 1; }
mkdir -p "src-tauri/gen/apple/Externals/arm64/$config"
cp "$lib" "src-tauri/gen/apple/Externals/arm64/$config/libapp.a"

# Build-Phase „Build Rust Code“ abschalten (die Bibliothek liegt schon da).
python3 scripts/ios/disable-rust-phase.py "$proj/project.pbxproj"

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
