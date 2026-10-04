# TRS Launcher – iOS-Spiel-Engine. Gebaut von scripts/ios/build-engine.sh (nur macOS).
#
# Ergebnis (alles kommt nach <App>.app/Frameworks):
#   libtrsengine.dylib    Amethyst-Engine-Teile (vendor/) + TRS-Anbindung (trs/)
#   libawt_xawt.dylib     AWT-Attrappe für Caciocavallo (wird in die Java-Laufzeit kopiert)
#   libtinygl4angle.dylib OpenGL-auf-ANGLE-Hülle (Renderer "auto" ab 1.17)
#
# AMETHYST_DIR = Checkout von Amethyst-iOS am festen Commit (Header, fishhook, mach_exc,
# gl4es-Quellen und die vorgebauten Bibliotheken in Natives/resources/Frameworks).

cmake_minimum_required(VERSION 3.20)
project(TRSEngine C OBJC)

if(NOT AMETHYST_DIR OR NOT EXISTS "${AMETHYST_DIR}/Natives/external/mesa/EGL/egl.h")
  message(FATAL_ERROR "AMETHYST_DIR fehlt oder ist unvollständig (siehe scripts/ios/build-engine.sh)")
endif()
if(NOT EXISTS "${AMETHYST_DIR}/Natives/external/fishhook/fishhook.c")
  message(FATAL_ERROR "Submodul Natives/external/fishhook fehlt in AMETHYST_DIR")
endif()

set(VENDOR "${CMAKE_CURRENT_LIST_DIR}/vendor/amethyst-ios/Natives")
set(TRS "${CMAKE_CURRENT_LIST_DIR}/trs")
set(UPSTREAM "${AMETHYST_DIR}/Natives")
set(PREBUILT_FRAMEWORKS "${UPSTREAM}/resources/Frameworks")

# Wie Amethyst: -fcommon für die globalen Variablen in environ.h/utils.h; manche
# Frameworks liegen in neueren SDKs unter Cryptexes.
set(CRYPTEX_FRAMEWORKS "-F${CMAKE_OSX_SYSROOT}/System/Cryptexes/OS/System/Library/Frameworks")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fcommon -Wno-deprecated-declarations ${CRYPTEX_FRAMEWORKS}")
set(CMAKE_OBJC_FLAGS "${CMAKE_OBJC_FLAGS} -fcommon -fobjc-arc -Wno-deprecated-declarations ${CRYPTEX_FRAMEWORKS}")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${CRYPTEX_FRAMEWORKS}")

# Reihenfolge wichtig: TRS-Ersatzheader vor den Amethyst-Headern.
set(ENGINE_INCLUDES
  "${TRS}/shim"
  "${TRS}"
  "${VENDOR}"
  "${UPSTREAM}/external/mesa"
  "${UPSTREAM}/external/mach"
  "${UPSTREAM}"
)

# --- libtrsengine.dylib ---
add_library(trsengine SHARED
  "${VENDOR}/utils.m"
  "${VENDOR}/input_bridge_v3.m"
  "${VENDOR}/egl_bridge.m"
  "${VENDOR}/main_hook.m"
  "${VENDOR}/dyld_bypass_validation.m"
  "${VENDOR}/dyld_patch_platform.m"
  "${VENDOR}/ctxbridges/gl_bridge.m"
  "${VENDOR}/ctxbridges/osm_bridge.m"
  "${UPSTREAM}/external/fishhook/fishhook.c"
  "${UPSTREAM}/external/mach/mach_excServer.c"
  "${TRS}/trs_engine.m"
  "${TRS}/trs_shims.m"
)
target_include_directories(trsengine PRIVATE ${ENGINE_INCLUDES})
# main_hook.m nutzt Mach-Funktionen ohne eigenes #include (bei Amethyst kam <mach/mach.h> über den
# fishhook-Fork mit) – die Datei bleibt unverändert, der Header kommt per -include dazu.
set_source_files_properties("${VENDOR}/main_hook.m" PROPERTIES COMPILE_OPTIONS "-include;mach/mach.h")
target_link_libraries(trsengine PRIVATE
  "-framework Foundation"
  "-framework UIKit"
  "-framework CoreGraphics"
  "-framework CoreImage"
  "-framework QuartzCore"
  "-framework Security"
  "-weak_framework SafariServices"
)
set_target_properties(trsengine PROPERTIES
  OUTPUT_NAME "trsengine"
  INSTALL_NAME_DIR "@rpath"
  BUILD_WITH_INSTALL_NAME_DIR ON
)
# Liegt in <App>.app/Frameworks; dlopen("@rpath/…") der Renderer sucht dort.
target_link_options(trsengine PRIVATE
  "-Wl,-rpath,@loader_path"
  "-Wl,-rpath,@executable_path/Frameworks"
)

# --- libawt_xawt.dylib (wie Amethyst: gegen eine leere libawt_headless gelinkt, die zur
# Laufzeit aus der Java-Laufzeit kommt) ---
add_library(awt_headless SHARED "${VENDOR}/awt_xawt/empty.m")
set_target_properties(awt_headless PROPERTIES INSTALL_NAME_DIR "@rpath" BUILD_WITH_INSTALL_NAME_DIR ON)
add_library(awt_xawt SHARED "${VENDOR}/awt_xawt/xawt_fake.m")
target_link_libraries(awt_xawt PRIVATE awt_headless)
set_target_properties(awt_xawt PROPERTIES INSTALL_NAME_DIR "@rpath" BUILD_WITH_INSTALL_NAME_DIR ON)
target_link_options(awt_xawt PRIVATE "-Wl,-rpath,@loader_path")

# --- libtinygl4angle.dylib (Amethyst external/gl4es) ---
add_library(tinygl4angle SHARED
  "${UPSTREAM}/external/gl4es/string_utils.c"
  "${UPSTREAM}/external/gl4es/tinygl4angle.c"
)
# Amethyst baut .c mit -ObjC (tinygl4angle.c importiert Foundation).
set_source_files_properties(
  "${UPSTREAM}/external/gl4es/string_utils.c"
  "${UPSTREAM}/external/gl4es/tinygl4angle.c"
  PROPERTIES LANGUAGE OBJC
)
target_include_directories(tinygl4angle PRIVATE "${UPSTREAM}/external/mesa" "${UPSTREAM}/external/gl4es")
target_link_libraries(tinygl4angle PRIVATE
  "-F${PREBUILT_FRAMEWORKS}"
  "-framework Foundation"
  "-framework libEGL"
  "-framework libGLESv2"
)
set_target_properties(tinygl4angle PROPERTIES INSTALL_NAME_DIR "@rpath" BUILD_WITH_INSTALL_NAME_DIR ON)
target_link_options(tinygl4angle PRIVATE "-Wl,-rpath,@loader_path" "-Wl,-rpath,@executable_path/Frameworks")
