// TRS: SDL3-Hilfen für Minecraft 26.3+ (Teil des TRS Launchers, GPL-3.0-or-later).
// 1. Android kennt nur ein SDL-Fenster. Minecraft legt für den GL-Kontext erst ein Fenster an und
//    danach das eigentliche Spielfenster – das zweite SDL_CreateWindow scheitert ("Android only
//    supports one window"). LWJGL holt sich die SDL-Funktionen per dlsym; der Ersatz für
//    DynamicLinkLoader.ndlsym gibt für SDL_CreateWindow*/SDL_DestroyWindow Stellvertreter zurück,
//    die das erste Fenster mit Referenzzähler wiederverwenden. SDL_SetWindowSize setzt immer die
//    Größe der Spielfläche (Anzeigemodus): Minecraft wünscht sich sonst die halbe Bildschirmgröße
//    und zeichnet nur in eine Ecke.
// 2. Tasten ohne Android-Tastencode (F13–F24, z. B. TRS-Menü) als SDL-Ereignis ins Spiel geben.
// SDL selbst wird nur per dlsym angesprochen.
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

#include "SDL3/SDL_events.h"
#include "SDL3/SDL_video.h"

#define TAG __FILE_NAME__
#include "log.h"

// --- Fenster wiederverwenden ------------------------------------------------------------------

typedef SDL_Window *(*sdl_create_window_t)(const char *title, int w, int h, SDL_WindowFlags flags);
typedef SDL_Window *(*sdl_create_window_props_t)(SDL_PropertiesID props);
typedef void (*sdl_destroy_window_t)(SDL_Window *window);

static pthread_mutex_t window_lock = PTHREAD_MUTEX_INITIALIZER;
static sdl_create_window_t real_create_window;
static sdl_create_window_props_t real_create_window_props;
static sdl_destroy_window_t real_destroy_window;
static SDL_Window *primary_window;
static int primary_refs;

typedef SDL_WindowFlags (*sdl_get_window_flags_t)(SDL_Window *window);
typedef Sint64 (*sdl_get_number_property_t)(SDL_PropertiesID props, const char *name, Sint64 default_value);
typedef bool (*sdl_get_boolean_property_t)(SDL_PropertiesID props, const char *name, bool default_value);

#define API_FLAGS (SDL_WINDOW_OPENGL | SDL_WINDOW_VULKAN)
#define MAX_STALE 8
// Echt zerstörte, vom Spiel aber noch gehaltene Fenster: deren SDL_DestroyWindow ignorieren.
static SDL_Window *stale_windows[MAX_STALE];

static void *sdl_symbol(const char *name) {
    void *sdl = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_NOW);
    if (sdl == NULL) return NULL;
    void *sym = dlsym(sdl, name);
    dlclose(sdl);
    return sym;
}

static SDL_Window *remember(SDL_Window *window) {
    if (window != NULL) {
        // Gleiche Adresse wie ein altes, zerstörtes Fenster: der alte Eintrag gilt nicht mehr.
        for (int i = 0; i < MAX_STALE; i++) {
            if (stale_windows[i] == window) stale_windows[i] = NULL;
        }
        primary_window = window;
        primary_refs = 1;
    }
    return window;
}

/**
 * Vorhandenes Fenster weitergeben (Referenz +1). Passt die Grafik-Art nicht (OpenGL ↔ Vulkan,
 * z. B. nach einem gescheiterten Vulkan-Versuch), wird es wirklich zerstört und ein neues angelegt.
 * Aufruf mit gehaltenem window_lock; NULL = neu anlegen.
 */
static SDL_Window *reuse(SDL_WindowFlags wanted) {
    if (primary_window == NULL) return NULL;
    sdl_get_window_flags_t get_flags = (sdl_get_window_flags_t) sdl_symbol("SDL_GetWindowFlags");
    SDL_WindowFlags have = get_flags != NULL ? get_flags(primary_window) : wanted;
    if ((wanted & API_FLAGS & ~have) != 0) {
        LOGI("TRS: SDL-Fenster passt nicht (Grafik-Art), ersetze es");
        for (int i = 0; i < MAX_STALE; i++) {
            if (stale_windows[i] == NULL) {
                stale_windows[i] = primary_window;
                break;
            }
        }
        real_destroy_window(primary_window);
        primary_window = NULL;
        primary_refs = 0;
        return NULL;
    }
    primary_refs++;
    LOGI("TRS: SDL-Fenster wiederverwendet (%d Referenzen)", primary_refs);
    return primary_window;
}

static SDL_Window *trs_create_window(const char *title, int w, int h, SDL_WindowFlags flags) {
    pthread_mutex_lock(&window_lock);
    SDL_Window *out = reuse(flags);
    if (out == NULL) out = remember(real_create_window(title, w, h, flags));
    pthread_mutex_unlock(&window_lock);
    return out;
}

static SDL_Window *trs_create_window_props(SDL_PropertiesID props) {
    sdl_get_number_property_t number = (sdl_get_number_property_t) sdl_symbol("SDL_GetNumberProperty");
    sdl_get_boolean_property_t boolean = (sdl_get_boolean_property_t) sdl_symbol("SDL_GetBooleanProperty");
    SDL_WindowFlags wanted = number != NULL ? (SDL_WindowFlags) number(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, 0) : 0;
    if (boolean != NULL && boolean(props, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, false)) wanted |= SDL_WINDOW_OPENGL;
    if (boolean != NULL && boolean(props, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, false)) wanted |= SDL_WINDOW_VULKAN;
    pthread_mutex_lock(&window_lock);
    SDL_Window *out = reuse(wanted);
    if (out == NULL) out = remember(real_create_window_props(props));
    pthread_mutex_unlock(&window_lock);
    return out;
}

static void trs_destroy_window(SDL_Window *window) {
    pthread_mutex_lock(&window_lock);
    int destroy = 1;
    for (int i = 0; window != NULL && i < MAX_STALE; i++) {
        if (stale_windows[i] == window) {
            stale_windows[i] = NULL;
            destroy = 0;
        }
    }
    if (destroy && window != NULL && window == primary_window) {
        primary_refs--;
        if (primary_refs > 0) {
            destroy = 0;
        } else {
            primary_window = NULL;
        }
    }
    pthread_mutex_unlock(&window_lock);
    if (destroy) real_destroy_window(window);
}

typedef bool (*sdl_set_window_size_t)(SDL_Window *window, int w, int h);
typedef SDL_DisplayID (*sdl_get_display_for_window_t)(SDL_Window *window);
typedef const SDL_DisplayMode *(*sdl_get_desktop_display_mode_t)(SDL_DisplayID display);
static sdl_set_window_size_t real_set_window_size;

static bool trs_set_window_size(SDL_Window *window, int w, int h) {
    void *sdl = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_NOW);
    if (sdl != NULL) {
        sdl_get_display_for_window_t display_for = (sdl_get_display_for_window_t) dlsym(sdl, "SDL_GetDisplayForWindow");
        sdl_get_desktop_display_mode_t desktop = (sdl_get_desktop_display_mode_t) dlsym(sdl, "SDL_GetDesktopDisplayMode");
        const SDL_DisplayMode *mode = (display_for && desktop) ? desktop(display_for(window)) : NULL;
        if (mode != NULL && mode->w > 0 && mode->h > 0) {
            LOGI("TRS: SDL_SetWindowSize(%d, %d) -> %dx%d (Spielfläche)", w, h, mode->w, mode->h);
            w = mode->w;
            h = mode->h;
        }
        dlclose(sdl);
    }
    return real_set_window_size(window, w, h);
}

/** Ersatz für org.lwjgl.system.linux.DynamicLinkLoader.ndlsym (LWJGL: dlsym). */
static jlong trs_ndlsym(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz,
                        jlong handle, jlong name_ptr) {
    const char *name = (const char *) (intptr_t) name_ptr;
    void *sym = dlsym((void *) (intptr_t) handle, name);
    if (sym == NULL || name == NULL || strncmp(name, "SDL_", 4) != 0) return (jlong) (intptr_t) sym;
    if (strcmp(name, "SDL_CreateWindow") == 0) {
        real_create_window = (sdl_create_window_t) sym;
        return (jlong) (intptr_t) trs_create_window;
    }
    if (strcmp(name, "SDL_CreateWindowWithProperties") == 0) {
        real_create_window_props = (sdl_create_window_props_t) sym;
        return (jlong) (intptr_t) trs_create_window_props;
    }
    if (strcmp(name, "SDL_SetWindowSize") == 0) {
        real_set_window_size = (sdl_set_window_size_t) sym;
        return (jlong) (intptr_t) trs_set_window_size;
    }
    if (strcmp(name, "SDL_DestroyWindow") == 0) {
        real_destroy_window = (sdl_destroy_window_t) sym;
        return (jlong) (intptr_t) trs_destroy_window;
    }
    return (jlong) (intptr_t) sym;
}

void installTrsSdlWindowHook(JNIEnv *env) {
    jclass loader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if (loader == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod methods[] = {
            {"ndlsym", "(JJ)J", (void *) &trs_ndlsym}
    };
    if ((*env)->RegisterNatives(env, loader, methods, 1) != 0) {
        LOGE("TRS: ndlsym-Ersatz nicht registriert");
        (*env)->ExceptionClear(env);
    }
}

// --- Tasten ohne Android-Code -----------------------------------------------------------------

typedef bool (*sdl_push_event_t)(SDL_Event *event);
typedef SDL_Window *(*sdl_get_keyboard_focus_t)(void);
typedef SDL_WindowID (*sdl_get_window_id_t)(SDL_Window *window);
typedef Uint64 (*sdl_get_ticks_ns_t)(void);
typedef SDL_Keymod (*sdl_get_mod_state_t)(void);

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSdlKey(__attribute__((unused)) JNIEnv *env,
                                                __attribute__((unused)) jclass clazz,
                                                jint scancode, jboolean down) {
    if (scancode <= 0 || scancode >= SDL_SCANCODE_COUNT) return;
    void *sdl = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_NOW);
    if (sdl == NULL) return;
    sdl_push_event_t push = (sdl_push_event_t) dlsym(sdl, "SDL_PushEvent");
    sdl_get_keyboard_focus_t focus = (sdl_get_keyboard_focus_t) dlsym(sdl, "SDL_GetKeyboardFocus");
    sdl_get_window_id_t window_id = (sdl_get_window_id_t) dlsym(sdl, "SDL_GetWindowID");
    sdl_get_ticks_ns_t ticks = (sdl_get_ticks_ns_t) dlsym(sdl, "SDL_GetTicksNS");
    sdl_get_mod_state_t mods = (sdl_get_mod_state_t) dlsym(sdl, "SDL_GetModState");
    if (push == NULL || focus == NULL || window_id == NULL || ticks == NULL || mods == NULL) {
        dlclose(sdl);
        return;
    }
    SDL_Event event;
    memset(&event, 0, sizeof(event));
    event.key.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
    event.key.timestamp = ticks();
    SDL_Window *window = focus();
    event.key.windowID = window != NULL ? window_id(window) : 0;
    event.key.which = 0;
    event.key.scancode = (SDL_Scancode) scancode;
    event.key.key = SDL_SCANCODE_TO_KEYCODE(scancode);
    event.key.mod = mods();
    event.key.down = down ? true : false;
    event.key.repeat = false;
    if (!push(&event)) LOGW("TRS: SDL_PushEvent fehlgeschlagen");
    dlclose(sdl);
}
