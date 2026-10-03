# TRS-Anpassung des Amethyst-Android.mk (siehe ../UPSTREAM.md, Patch 1):
# - androidnsbypass wird aus ../../androidnsbypass mitgebaut statt per Prefab
# - ohne AWT-Brücke (pojavexec_awt/awt_xawt), glxshim und linkerhook (nur Zink/Turnip)
# - kein `rm` in ../jniLibs
LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

$(call import-module,prefab/bytehook)

# androidnsbypass (MIT, alexytomi) – Linker-Namespaces umgehen (Treiber/Renderer laden)
LOCAL_PATH := $(HERE_PATH)/../../androidnsbypass/cpp
include $(CLEAR_VARS)
LOCAL_MODULE := androidnsbypass
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -llog
LOCAL_SRC_FILES := \
    jni.c \
    elf_soname_patcher.c \
    nsbypass.c \
    nsbypass_dlfcn.c \
    utils.c \
    android_linker_ns.cpp
include $(BUILD_SHARED_LIBRARY)

LOCAL_PATH := $(HERE_PATH)

include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := pojavexec
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_SHARED_LIBRARIES := androidnsbypass
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/loader_dlopen.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    environ/environ.c \
    jvm_hooks/emui_iterator_fix_hook.c \
    jvm_hooks/java_exec_hooks.c \
    jvm_hooks/lwjgl_dlopen_hook.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
endif
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_SRC_FILES := \
    native_hooks/exit_hook.c \
    native_hooks/chmod_hook.c \
    native_hooks/sdl_hook.c \
    native_hooks/dlopen_hook.c
include $(BUILD_SHARED_LIBRARY)
