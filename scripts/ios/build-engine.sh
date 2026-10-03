#!/usr/bin/env bash
# TRS Launcher – baut die iOS-Spiel-Engine (nur macOS mit Xcode).
#
#   scripts/ios/build-engine.sh <ausgabe-ordner>
#
# Ergebnis in <ausgabe-ordner> (wird von scripts/ios/package-ipa.sh in die .app gelegt):
#   Frameworks/            libtrsengine.dylib, libawt_xawt.dylib, libtinygl4angle.dylib, libmobileglues.dylib,
#                          vorgebaute Renderer/LWJGL-Bibliotheken aus Amethyst-iOS (ANGLE, gl4es, OSMesa, MoltenVK …)
#   libs/                  launcher.jar, lwjgl.jar, patchjna_agent.jar, trs-boot.jar, gson, jsr305
#   libs_caciocavallo*/    Caciocavallo (AWT ohne Fenster-System) für Java 8 bzw. 17+
#   resources/             StikDebug-Skripte für iOS 26 (UniversalJIT26*.js)
#   LICENSES/              Lizenztexte der gebauten/mitgelieferten Teile
#   engine.json            Commit + Dateiliste
#
# Braucht: Xcode (iphoneos-SDK), cmake, git, JDK 8 (JAVA8_HOME oder /usr/libexec/java_home -v 1.8).
# Java-Laufzeiten werden NICHT gebündelt (lädt die App zur Laufzeit, siehe IOS_RUNTIMES in src/runtime.rs des Plugins).
set -euo pipefail

# Muss zu src-tauri/plugins/tauri-plugin-trs-game/ios/Engine/vendor/amethyst-ios/UPSTREAM.md passen.
AMETHYST_REPO="https://github.com/AngelAuraMC/Amethyst-iOS"
AMETHYST_COMMIT="9212a1894865e7ac0466029e25ddb0d895544c76"
DEPLOYMENT_TARGET="14.0"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENGINE_SRC="$ROOT/src-tauri/plugins/tauri-plugin-trs-game/ios/Engine"
OUT="${1:?Aufruf: scripts/ios/build-engine.sh <ausgabe-ordner>}"
WORK="${TRS_ENGINE_WORK:-$ENGINE_SRC/build}"
JOBS="$(sysctl -n hw.logicalcpu 2>/dev/null || echo 4)"

log() { printf '\n== %s\n' "$*"; }
die() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || die "nur auf macOS (Xcode) baubar"
command -v cmake >/dev/null || die "cmake fehlt"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
JAVA8="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)}"
[ -x "$JAVA8/bin/javac" ] || die "JDK 8 fehlt (JAVA8_HOME setzen)"
"$JAVA8/bin/javac" -version 2>&1 | grep -q '1\.8' || die "JAVA8_HOME ist kein JDK 8"
mkdir -p "$WORK"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"

# --- 1. Amethyst-iOS am festen Commit (Header, fishhook, MobileGlues, Java-Teil, vorgebaute Bibliotheken) ---
UP="$WORK/amethyst"
if [ ! -d "$UP/.git" ] || [ "$(git -C "$UP" rev-parse HEAD 2>/dev/null)" != "$AMETHYST_COMMIT" ]; then
  log "Amethyst-iOS $AMETHYST_COMMIT holen"
  rm -rf "$UP"
  mkdir -p "$UP"
  git -C "$UP" init -q
  git -C "$UP" remote add origin "$AMETHYST_REPO"
  git -C "$UP" fetch -q --depth 1 origin "$AMETHYST_COMMIT"
  git -C "$UP" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
[ "$(git -C "$UP" rev-parse HEAD)" = "$AMETHYST_COMMIT" ] || die "falscher Amethyst-Commit"
git -C "$UP" checkout -q -- .
for sub in Natives/external/fishhook Natives/external/MobileGlues; do
  git -C "$UP" submodule update --init --recursive --depth 1 "$sub" \
    || git -C "$UP" submodule update --init --recursive "$sub"
done

# --- 2. Vendored Dateien gegen Upstream prüfen (nur die in UPSTREAM.md genannten dürfen abweichen) ---
log "Vendored Amethyst-Dateien prüfen"
VENDOR="$ENGINE_SRC/vendor/amethyst-ios"
PATCHED="Natives/input_bridge_v3.m Natives/main_hook.m Natives/egl_bridge.m"
( cd "$VENDOR" && find . -type f ! -name 'UPSTREAM.md' | sed 's#^\./##' ) | while read -r rel; do
  if ! cmp -s <(tr -d '\r' < "$VENDOR/$rel") <(tr -d '\r' < "$UP/$rel"); then
    case " $PATCHED " in
      *" $rel "*) echo "  gepatcht (erwartet): $rel" ;;
      *) die "vendored Datei weicht von Upstream ab: $rel (UPSTREAM.md/Patchliste anpassen)" ;;
    esac
  fi
done

# --- 3. Native Engine (CMake) ---
log "libtrsengine, libawt_xawt, libtinygl4angle bauen"
cmake -S "$ENGINE_SRC" -B "$WORK/engine" \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT="$SDK" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET" \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
  -DCMAKE_BUILD_TYPE=Release \
  -DAMETHYST_DIR="$UP"
cmake --build "$WORK/engine" --config Release -j "$JOBS"

# --- 4. MobileGlues (wie Amethysts Makefile-Ziel dep_mg) ---
log "MobileGlues bauen"
MG_SRC="$UP/Natives/external/MobileGlues/src/main/cpp"
cmake -S "$MG_SRC" -B "$WORK/mobileglues" \
  -DMACOS="1" \
  -DCMAKE_CROSSCOMPILING=true \
  -DCMAKE_SYSTEM_NAME=Darwin \
  -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_OSX_SYSROOT="$SDK" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET" \
  -DCMAKE_C_FLAGS="-arch arm64"
cmake --build "$WORK/mobileglues" --config RelWithDebInfo -j "$JOBS" --target mobileglues

# --- 5. Java-Teil: Amethyst JavaApp (mit TRS-Patch) + trs-boot.jar ---
log "Java-Teil bauen"
for patch in "$ENGINE_SRC"/patches/*.patch; do
  git -C "$UP" apply --whitespace=nowarn "$patch"
done
make -C "$UP/JavaApp" BOOTJDK="$JAVA8/bin"
rm -rf "$WORK/trsboot"
mkdir -p "$WORK/trsboot"
"$JAVA8/bin/javac" -encoding UTF-8 -d "$WORK/trsboot" "$ENGINE_SRC/java/dev/theredstonee/trs/ios/TrsBoot.java"
"$JAVA8/bin/jar" cf "$WORK/trs-boot.jar" -C "$WORK/trsboot" .
git -C "$UP" checkout -q -- JavaApp

# --- 6. Zusammenstellen ---
log "Ausgabe nach $OUT"
rm -rf "$OUT"/{Frameworks,libs,libs_caciocavallo,libs_caciocavallo17,resources,LICENSES,engine.json}
mkdir -p "$OUT"/{Frameworks,libs,libs_caciocavallo,libs_caciocavallo17,resources,LICENSES}
cp "$WORK/engine/libtrsengine.dylib" "$WORK/engine/libawt_xawt.dylib" "$WORK/engine/libtinygl4angle.dylib" "$OUT/Frameworks/"
cp "$WORK/mobileglues/libmobileglues.dylib" "$OUT/Frameworks/"
cp "$MG_SRC/libraries/ios/libspirv-cross-c-shared.0.dylib" "$OUT/Frameworks/"
PREBUILT="$UP/Natives/resources/Frameworks"
for item in libEGL.framework libGLESv2.framework libMoltenVK.dylib libOSMesa.8.dylib libfreetype.dylib libgl4es_114.dylib \
  libglapi.0.dylib liblwjgl.dylib liblwjgl_nanovg.dylib liblwjgl_opengl.dylib liblwjgl_stb.dylib liblwjgl_tinyfd.dylib \
  liblwjgl_vma.dylib libopenal.dylib libshaderc.dylib; do
  [ -e "$PREBUILT/$item" ] || die "vorgebaute Bibliothek fehlt: $item"
  cp -R "$PREBUILT/$item" "$OUT/Frameworks/"
done
cp "$UP/JavaApp/build/launcher.jar" "$UP/JavaApp/build/lwjgl.jar" "$UP/JavaApp/build/patchjna_agent.jar" "$OUT/libs/"
cp "$WORK/trs-boot.jar" "$OUT/libs/"
cp "$UP"/JavaApp/libs/others/gson-*.jar "$UP/JavaApp/libs/others/jsr305.jar" "$OUT/libs/"
cp -R "$UP/JavaApp/libs/caciocavallo/." "$OUT/libs_caciocavallo/"
cp -R "$UP/JavaApp/libs/caciocavallo17/." "$OUT/libs_caciocavallo17/"
cp "$UP/Natives/resources/UniversalJIT26.js" "$UP/Natives/resources/UniversalJIT26Extension.js" "$OUT/resources/"
cp "$UP/LICENSE" "$OUT/LICENSES/Amethyst-iOS-GPL-3.0.txt"
cp "$UP/Natives/external/MobileGlues/LICENSE" "$OUT/LICENSES/MobileGlues-LGPL-2.1.txt"
cp "$UP/Natives/external/fishhook/LICENSE" "$OUT/LICENSES/fishhook-BSD-3-Clause.txt"
cat > "$OUT/LICENSES/README.txt" <<EOF
TRS Launcher iOS game engine – third-party parts
Built from Amethyst-iOS $AMETHYST_COMMIT ($AMETHYST_REPO).
Source of every part: see src-tauri/plugins/tauri-plugin-trs-game/ios/Engine/vendor/amethyst-ios/UPSTREAM.md
in the TRS Launcher repository (GPL-3.0). Java runtimes are downloaded by the app at runtime
(OpenJDK, GPL-2.0 with Classpath Exception).
EOF

# --- 7. Prüfen ---
log "Ergebnis prüfen"
for lib in libtrsengine.dylib libawt_xawt.dylib libtinygl4angle.dylib libmobileglues.dylib; do
  file "$OUT/Frameworks/$lib" | grep -q 'Mach-O 64-bit dynamically linked shared library arm64' || die "$lib ist keine arm64-dylib"
done
SYMBOLS="$(nm -gU "$OUT/Frameworks/libtrsengine.dylib")"
for sym in _trs_engine_api_version _trs_engine_launch _trs_input_key _JNI_OnLoad _pojavCreateContext _pojavSwapBuffers \
  _Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing _Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow \
  _Java_net_kdt_pojavlaunch_uikit_UIKit_showError; do
  grep -q " T $sym\$" <<<"$SYMBOLS" || die "Symbol fehlt in libtrsengine: $sym"
done
otool -L "$OUT/Frameworks/libtrsengine.dylib"
unzip -l "$OUT/libs/lwjgl.jar" | grep -q 'org/lwjgl/glfw/GLFW.class' || die "lwjgl.jar ohne GLFW"
unzip -l "$OUT/libs/trs-boot.jar" | grep -q 'dev/theredstonee/trs/ios/TrsBoot.class' || die "trs-boot.jar unvollständig"

( cd "$OUT" && find Frameworks libs libs_caciocavallo libs_caciocavallo17 resources -type f | sort ) > "$WORK/files.txt"
{
  printf '{\n  "amethystCommit": "%s",\n  "builtAt": "%s",\n  "files": [\n' "$AMETHYST_COMMIT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  sed 's/.*/    "&"/' "$WORK/files.txt" | sed '$!s/$/,/'
  printf '  ]\n}\n'
} > "$OUT/engine.json"
log "Fertig: $(wc -l < "$WORK/files.txt" | tr -d ' ') Dateien"
