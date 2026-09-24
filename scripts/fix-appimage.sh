#!/usr/bin/env bash
# Packt das AppImage ohne die mitgebündelten libwayland-*-Bibliotheken neu.
#
# linuxdeploy legt die Wayland-Bibliotheken des Build-Systems (Ubuntu) ins
# AppImage. Auf Arch, Fedora & Co. passen sie nicht zum EGL/Mesa des Systems:
# WebKit bricht mit „Could not create default EGL display: EGL_BAD_PARAMETER“
# ab und das Fenster bleibt leer. libwayland-client/-egl gehören zu jeder
# GTK-3-Installation, also nimmt das AppImage einfach die des Systems.
#
#   scripts/fix-appimage.sh <datei.AppImage>
#
# Danach muss das AppImage neu signiert werden (`pnpm tauri signer sign <datei>`).
set -euo pipefail

in=$(realpath "$1")
tool_url="https://github.com/AppImage/appimagetool/releases/download/1.9.0/appimagetool-x86_64.AppImage"
tool_sha256="46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

tool=${APPIMAGETOOL:-}
if [ -z "$tool" ]; then
  tool="$work/appimagetool"
  curl -sSLf -o "$tool" "$tool_url"
  echo "$tool_sha256  $tool" | sha256sum -c --quiet
  chmod +x "$tool"
fi

chmod +x "$in"
"$in" --appimage-extract >/dev/null
removed=$(find squashfs-root/usr/lib -maxdepth 1 -name 'libwayland-*.so*' -print -delete | wc -l)
echo "entfernt: $removed libwayland-Bibliotheken"

if ! ARCH=x86_64 "$tool" --appimage-extract-and-run --no-appstream squashfs-root "$in.new" >"$work/appimagetool.log" 2>&1; then
  cat "$work/appimagetool.log" >&2
  exit 1
fi
chmod +x "$in.new"
mv "$in.new" "$in"
rm -f "$in.sig"
echo "neu gepackt: $in"
