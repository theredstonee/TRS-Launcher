#!/usr/bin/env bash
# Xcode-Projekt der iOS-App (src-tauri/gen/apple): mit `tauri ios init` erzeugen, falls es fehlt, und die
# App-Icons aus src-tauri/icons/ios einsetzen. Braucht macOS mit Xcode – unter Windows lässt sich das Projekt
# nicht erzeugen, deshalb entsteht es im CI (launcher-ios, Release-Job ios).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v xcodegen > /dev/null || brew install xcodegen
if [ ! -d src-tauri/gen/apple ]; then
  pnpm tauri ios init --ci
fi

icons=$(find src-tauri/gen/apple -type d -name 'AppIcon.appiconset' | head -1)
if [ -n "$icons" ]; then
  cp src-tauri/icons/ios/*.png "$icons/"
else
  echo "::warning::AppIcon.appiconset not found – the IPA keeps the default icon."
fi
