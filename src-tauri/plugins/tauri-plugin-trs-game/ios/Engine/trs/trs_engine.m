// TRS Launcher – iOS-Spiel-Engine (libtrsengine.dylib).
//
// Der JVM-Start ist eine Umsetzung von launchJVM() aus Amethyst-iOS JavaLauncher.m und
// Teilen von main.m (GPL-3.0, Copyright (C) 2021 Tran Hoang Khanh Duy und Mitwirkende,
// https://github.com/AngelAuraMC/Amethyst-iOS). Statt Amethysts Profilen kommen alle
// Argumente fertig aus dem TRS-Kern (Rust, src/ios/args.rs).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#import <UIKit/UIKit.h>

#include <dlfcn.h>
#include <mach/mach.h>
#include <mach-o/dyld.h>
#include <os/proc.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "JavaLauncher.h"
#include "environ.h"
#include "external/fishhook/fishhook.h"
#include "utils.h"
#import "LauncherPreferences.h"
#import "SurfaceViewController.h"
#include "trs_engine_internal.h"

#define fm NSFileManager.defaultManager

extern UIView *trsSurfaceView;
extern BOOL trsEngineRunning;

enum {
    TRS_OK = 0,
    TRS_ERR_USED = 1,
    TRS_ERR_ARGS = 2,
    TRS_ERR_NO_SURFACE = 3,
    TRS_ERR_NO_JIT = 4,
    TRS_ERR_JIT_SCRIPT = 5,
    TRS_ERR_MEMORY = 6,
    TRS_ERR_JLI = 7,
    TRS_ERR_THREAD = 8,
    TRS_ERR_HOOK = 9,
};

static trs_log_cb sLog;
static trs_exit_cb sExit;
static trs_grab_cb sGrab;
static trs_frame_cb sFrame;

static atomic_int sUsed;
static atomic_int sExiting;
static atomic_int sAwaitAck;
static atomic_int sAcked;
static atomic_int sFirstFrame;
static char *sHomeDir;
static char *sSession;

// Letzte Log-Zeilen fürs Sitzungsende.
#define TAIL_LINES 200
static pthread_mutex_t sTailLock = PTHREAD_MUTEX_INITIALIZER;
static NSMutableArray<NSString *> *sTail;

int trs_engine_api_version(void) {
    return TRS_ENGINE_API_VERSION;
}

void trs_engine_set_callbacks(trs_log_cb log, trs_exit_cb on_exit, trs_grab_cb grab, trs_frame_cb first_frame) {
    sLog = log;
    sExit = on_exit;
    sGrab = grab;
    sFrame = first_frame;
}

#pragma mark - Geräteprüfung

int trs_engine_jit_enabled(void) {
    return isJITEnabled(NO) ? 1 : 0;
}

int trs_engine_cs_debugged(void) {
    int flags = 0;
    csops(getpid(), 0, &flags, sizeof(flags));
    return (flags & CS_DEBUGGED) ? 1 : 0;
}

uint32_t trs_engine_jit_flags(void) {
    return (uint32_t)DeviceGetJITFlags(NO);
}

int trs_engine_debugger_attached(void) {
    return JIT26IsLikelyDebuggerKeepAttached() ? 1 : 0;
}

int trs_engine_entitlement(const char *key) {
    if (!key) return 0;
    return getEntitlementValue(@(key)) ? 1 : 0;
}

uint64_t trs_engine_available_memory_mb(void) {
    return (uint64_t)os_proc_available_memory() >> 20;
}

static BOOL canMapContiguous(uint64_t mb) {
    size_t size = (size_t)(mb << 20);
    void *map = mmap(0, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (map == MAP_FAILED) return NO;
    return munmap(map, size) == 0;
}

uint64_t trs_engine_max_contiguous_mb(uint64_t limit_mb) {
    // Binärsuche in 64-MB-Schritten (wie validateVirtualMemorySpace in Amethyst).
    uint64_t lo = 0, hi = limit_mb / 64;
    while (lo < hi) {
        uint64_t mid = (lo + hi + 1) / 2;
        if (canMapContiguous(mid * 64)) lo = mid; else hi = mid - 1;
    }
    return lo * 64;
}

int trs_engine_used(void) {
    return atomic_load(&sUsed);
}

#pragma mark - Fläche und Größe

void trs_engine_set_surface(void *view) {
    trsSurfaceView = (__bridge UIView *)view;
}

void trs_engine_set_window_size(int physical_width, int physical_height, int window_width, int window_height) {
    physicalWidth = physical_width;
    physicalHeight = physical_height;
    resolutionScale = physical_width > 0 ? (float)window_width / (float)physical_width : 1.0f;
    CallbackBridge_nativeSendScreenSize(window_width, window_height);
}

#pragma mark - Log

void trs_engine_log_line(const char *line) {
    if (!line) return;
    NSString *text = [NSString stringWithUTF8String:line] ?: @"";
    pthread_mutex_lock(&sTailLock);
    if (!sTail) sTail = [NSMutableArray arrayWithCapacity:TAIL_LINES];
    [sTail addObject:text];
    if (sTail.count > TAIL_LINES) [sTail removeObjectAtIndex:0];
    pthread_mutex_unlock(&sTailLock);
    if (sLog) sLog(line);
}

static NSString *logTail(void) {
    pthread_mutex_lock(&sTailLock);
    NSString *tail = sTail ? [sTail componentsJoinedByString:@"\n"] : @"";
    pthread_mutex_unlock(&sTailLock);
    return tail;
}

// Wie init_redirectStdio in Amethyst main.m: stdout/stderr in eine Pipe, Zeilen ins Log.
static void redirectStdio(NSString *logPath) {
    NSString *oldPath = [logPath stringByAppendingString:@".old"];
    [fm removeItemAtPath:oldPath error:nil];
    [fm moveItemAtPath:logPath toPath:oldPath error:nil];
    [fm createFileAtPath:logPath contents:nil attributes:nil];
    NSFileHandle *file = [NSFileHandle fileHandleForWritingAtPath:logPath];

    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IONBF, 0);
    static int pfd[2];
    if (pipe(pfd) != 0) return;
    dup2(pfd[1], fileno(stdout));
    dup2(pfd[1], fileno(stderr));

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        char buf[4096];
        char line[8192];
        size_t used = 0;
        ssize_t n;
        while ((n = read(pfd[0], buf, sizeof(buf))) > 0) {
            for (ssize_t i = 0; i < n; i++) {
                char c = buf[i];
                if (c != '\n' && used < sizeof(line) - 1) {
                    line[used++] = c;
                    continue;
                }
                line[used] = '\0';
                // Session-ID alter Versionen nie ins Log (wie Amethyst).
                static const char censored[] = "(Session ID is <censored>)";
                char *session = strstr(line, "(Session ID is ");
                if (session && (size_t)(session - line) + sizeof(censored) <= sizeof(line)) {
                    memcpy(session, censored, sizeof(censored));
                }
                size_t len = strlen(line);
                [file writeData:[NSData dataWithBytes:line length:len]];
                [file writeData:[NSData dataWithBytes:"\n" length:1]];
                trs_engine_log_line(line);
                used = 0;
                if (c != '\n') line[used++] = c;
            }
        }
        [file closeFile];
    });
}

#pragma mark - Ende

void trs_engine_grab_changed(int grabbing) {
    if (sGrab) sGrab(grabbing);
}

void trs_engine_first_frame(void) {
    if (atomic_exchange(&sFirstFrame, 1) == 0 && sFrame) sFrame();
}

int trs_engine_wants_exit_ack(void) {
    return atomic_load(&sAwaitAck);
}

void trs_engine_will_exit(int code) {
    if (!atomic_load(&sUsed) || atomic_exchange(&sExiting, 1)) return;
    // Für den nächsten App-Start festhalten (der Prozess endet gleich).
    if (sHomeDir) {
        NSDictionary *info = @{
            @"session": sSession ? @(sSession) : @"",
            @"code": @(code),
            @"logTail": logTail(),
        };
        NSData *json = [NSJSONSerialization dataWithJSONObject:info options:0 error:nil];
        NSString *path = [@(sHomeDir) stringByAppendingPathComponent:@"trs-last-session.json"];
        [json writeToFile:path atomically:YES];
    }
    if (sExit) {
        // Bei Absturz zeigt Swift einen Dialog und ruft danach trs_engine_finish_exit().
        atomic_store(&sAwaitAck, code != 0);
        sExit(code);
    }
}

void trs_engine_finish_exit(void) {
    if (fatalExitGroup && atomic_exchange(&sAcked, 1) == 0) dispatch_group_leave(fatalExitGroup);
}

#pragma mark - JLI

// Die macOS-JLI startet main() per dlsym(RTLD_DEFAULT, "main") in einem neuen Thread neu.
// Amethysts main() ruft dann JLI_Launch erneut auf; die App-main gehört hier aber Tauri.
// Deshalb wird dlsym nur in libjli umgebogen und liefert diese Funktion.
static void *(*orig_jli_dlsym)(void *handle, const char *symbol);

static int trsJliMain(int argc, char **argv) {
    return pJLI_Launch(argc, (const char **)argv, 0, NULL, 0, NULL, "1.8.0-internal", "1.8", "java", "openjdk", JNI_FALSE, JNI_TRUE, JNI_FALSE, JNI_TRUE);
}

static void *hookedJliDlsym(void *handle, const char *symbol) {
    if (handle == RTLD_DEFAULT && symbol && strcmp(symbol, "main") == 0) {
        return (void *)trsJliMain;
    }
    return orig_jli_dlsym(handle, symbol);
}

static BOOL hookJliMain(void *libjli) {
    void *launch = dlsym(libjli, "JLI_Launch");
    Dl_info info;
    if (!launch || !dladdr(launch, &info)) return NO;
    for (uint32_t i = 0; i < _dyld_image_count(); i++) {
        if ((const void *)_dyld_get_image_header(i) == info.dli_fbase) {
            struct rebinding rebindings[] = {{"dlsym", (void *)hookedJliDlsym, (void **)&orig_jli_dlsym}};
            return rebind_symbols_image((void *)_dyld_get_image_header(i), _dyld_get_image_vmaddr_slide(i), rebindings, 1) == 0;
        }
    }
    return NO;
}

typedef struct {
    int argc;
    char **argv;
} JliArgs;

static void *jliThread(void *arg) {
    JliArgs *args = arg;
    NSLog(@"[TRSEngine] Calling JLI_Launch");
    int result = trsJliMain(args->argc, args->argv);
    NSLog(@"[TRSEngine] JLI_Launch returned %d", result);
    // Wie Amethyst: Rückkehr aus main() beendet den Prozess.
    exit(result);
    return NULL;
}

static char **copyStrings(int count, const char *const *items) {
    char **out = calloc((size_t)count + 1, sizeof(char *));
    for (int i = 0; i < count; i++) {
        out[i] = strdup(items[i] ? items[i] : "");
    }
    return out;
}

// Standard-Umgebung wie init_loadDefaultEnv in Amethyst.
static void loadDefaultEnv(void) {
    setenv("LD_LIBRARY_PATH", "", 1);
    setenv("LIBGL_NOINTOVLHACK", "1", 1);
    setenv("LIBGL_NORMALIZE", "1", 1);
    setenv("MESA_GL_VERSION_OVERRIDE", "4.1", 1);
    setenv("HACK_IGNORE_START_ON_FIRST_THREAD", "1", 1);
}

// Gibt TRS_OK zurück, wenn der JIT-Weg für TXM-Geräte (iOS 26) bereit ist.
static int prepareJit(NSString *home) {
    // Nur einmal pro Prozess (Skript-Übergabe an StikDebug, dyld-Patch), auch bei erneutem Start.
    static BOOL prepared;
    if (prepared) return TRS_OK;
    DeviceGetJITFlags(YES);
    BOOL requiresTXMWorkaround = DeviceHasJITFlags(JIT_FLAG_FORCE_MIRRORED | JIT_FLAG_HAS_TXM);
    BOOL alwaysAttached = getPrefBool(@"debug.debug_always_attached_jit");
    if (requiresTXMWorkaround) {
        static void *result;
        if (!result) result = JIT26CreateRegionLegacy(getpagesize());
        if ((uint32_t)(uintptr_t)result != 0x690000E0) {
            munmap(result, getpagesize());
            // Altes Skript: das neue in Documents legen, damit es in StikDebug zugewiesen werden kann.
            NSString *script = [NSBundle.mainBundle pathForResource:@"UniversalJIT26" ofType:@"js"];
            if (script) {
                NSString *dest = [home stringByAppendingPathComponent:@"UniversalJIT26.js"];
                [fm removeItemAtPath:dest error:nil];
                [fm copyItemAtPath:script toPath:dest error:nil];
            }
            return TRS_ERR_JIT_SCRIPT;
        }
        NSString *extension = [NSBundle.mainBundle pathForResource:@"UniversalJIT26Extension" ofType:@"js"];
        NSString *extensionText = extension ? [NSString stringWithContentsOfFile:extension encoding:NSUTF8StringEncoding error:nil] : nil;
        if (!extensionText) return TRS_ERR_JIT_SCRIPT;
        JIT26SendJITScript(extensionText);
        JIT26SetDetachAfterFirstBr(!alwaysAttached);
        // Nicht in EXC_BAD_ACCESS hängen bleiben.
        task_set_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, 0, EXCEPTION_DEFAULT, MACHINE_THREAD_STATE);
    }
    if (!requiresTXMWorkaround || alwaysAttached) {
        if (alwaysAttached) {
            task_set_exception_ports(mach_task_self(), EXC_MASK_ALL & ~EXC_MASK_BREAKPOINT, 0, EXCEPTION_DEFAULT, THREAD_STATE_NONE);
        }
        // Erlaubt das Laden der nachgeladenen (unsignierten) JRE-Bibliotheken.
        init_bypassDyldLibValidation();
    } else {
        NSLog(@"[DyldLVBypass] Hook disabled! Loading unsigned dylib will cause code signature error.");
    }
    prepared = YES;
    return TRS_OK;
}

int trs_engine_launch(const char *java_home, int java_major, int xmx_mb, const char *home_dir, const char *session,
                      int argc, const char *const *argv, int envc, const char *const *envp) {
    if (!java_home || !home_dir || !argv || argc < 2 || xmx_mb <= 0 || envc < 0 || (envc > 0 && !envp)) return TRS_ERR_ARGS;
    if (atomic_exchange(&sUsed, 1)) return TRS_ERR_USED;
    if (!trsSurfaceView) {
        atomic_store(&sUsed, 0);
        return TRS_ERR_NO_SURFACE;
    }
    // Ohne JIT kein Start: der Interpreter ist für Minecraft unbrauchbar.
    if (!isJITEnabled(NO)) {
        atomic_store(&sUsed, 0);
        return TRS_ERR_NO_JIT;
    }

    NSString *home = @(home_dir);
    NSString *javaHome = @(java_home);
    free(sHomeDir);
    sHomeDir = strdup(home_dir);
    free(sSession);
    sSession = strdup(session ? session : "");

    loadDefaultEnv();
    for (int i = 0; i < envc; i++) {
        const char *entry = envp[i];
        const char *eq = entry ? strchr(entry, '=') : NULL;
        if (!eq || eq == entry) continue;
        char *key = strndup(entry, (size_t)(eq - entry));
        setenv(key, eq + 1, 1);
        free(key);
    }
    setenv("JAVA_HOME", java_home, 1);

    static dispatch_once_t once;
    dispatch_once(&once, ^{
        redirectStdio([home stringByAppendingPathComponent:@"trs-engine.log"]);
        // abort/exit/dlopen/open umleiten (Amethyst main_hook.m).
        init_hookFunctions();
    });
    NSLog(@"[TRSEngine] Java %d, -Xmx%dM, JAVA_HOME=%@", java_major, xmx_mb, javaHome);

    // resolv.conf für die JVM (open("/etc/resolv.conf") wird umgeleitet).
    NSString *resolv = [home stringByAppendingPathComponent:@"resolv.conf"];
    if (![fm fileExistsAtPath:resolv]) {
        [@"nameserver 8.8.8.8\nnameserver 8.8.4.4" writeToFile:resolv atomically:YES encoding:NSUTF8StringEncoding error:nil];
    }

    int jit = prepareJit(home);
    if (jit != TRS_OK) {
        atomic_store(&sUsed, 0);
        return jit;
    }

    // Caciocavallo braucht libawt_xawt.dylib in der Laufzeit.
    NSString *awtDest = [javaHome stringByAppendingPathComponent:@"lib/libawt_xawt.dylib"];
    NSString *awtSource = [NSBundle.mainBundle.privateFrameworksPath stringByAppendingPathComponent:@"libawt_xawt.dylib"];
    [fm removeItemAtPath:awtDest error:nil];
    NSError *copyError;
    if (![fm copyItemAtPath:awtSource toPath:awtDest error:&copyError]) {
        NSLog(@"[TRSEngine] Copy libawt_xawt.dylib failed: %@", copyError.localizedDescription);
    }

    if (!canMapContiguous((uint64_t)xmx_mb)) {
        NSLog(@"[TRSEngine] Insufficient contiguous virtual memory for -Xmx%dM", xmx_mb);
        atomic_store(&sUsed, 0);
        return TRS_ERR_MEMORY;
    }

    NSString *jliPath = java_major <= 8 ? [javaHome stringByAppendingPathComponent:@"lib/jli/libjli.dylib"]
                                        : [javaHome stringByAppendingPathComponent:@"lib/libjli.dylib"];
    setenv("INTERNAL_JLI_PATH", jliPath.UTF8String, 1);
    void *libjli = dlopen(jliPath.UTF8String, RTLD_GLOBAL);
    if (!libjli) {
        NSLog(@"[TRSEngine] JLI lib = NULL: %s", dlerror());
        atomic_store(&sUsed, 0);
        return TRS_ERR_JLI;
    }
    pJLI_Launch = (JLI_Launch_func *)dlsym(libjli, "JLI_Launch");
    if (!pJLI_Launch) {
        NSLog(@"[TRSEngine] JLI_Launch = NULL");
        atomic_store(&sUsed, 0);
        return TRS_ERR_JLI;
    }
    // Ohne Umleitung würde die JLI die main() der App (Tauri) ein zweites Mal starten.
    if (!hookJliMain(libjli)) {
        NSLog(@"[TRSEngine] Could not redirect main() lookup in libjli");
        atomic_store(&sUsed, 0);
        return TRS_ERR_HOOK;
    }

    // Wie Amethyst: Standard-Signalbehandlung, damit die JVM ihre eigenen setzen kann.
    signal(SIGSEGV, SIG_DFL);
    signal(SIGPIPE, SIG_DFL);
    signal(SIGBUS, SIG_DFL);
    signal(SIGILL, SIG_DFL);
    signal(SIGFPE, SIG_DFL);

    JliArgs *args = calloc(1, sizeof(JliArgs));
    args->argc = argc;
    args->argv = copyStrings(argc, argv);
    trsEngineRunning = YES;

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 4 * 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    int rc = pthread_create(&thread, &attr, jliThread, args);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        trsEngineRunning = NO;
        return TRS_ERR_THREAD;
    }
    return TRS_OK;
}

const char *trs_engine_error_name(int code) {
    switch (code) {
        case TRS_OK: return "ok";
        case TRS_ERR_USED: return "restartRequired";
        case TRS_ERR_ARGS: return "invalidArgs";
        case TRS_ERR_NO_SURFACE: return "noSurface";
        case TRS_ERR_NO_JIT: return "jitRequired";
        case TRS_ERR_JIT_SCRIPT: return "jitScript";
        case TRS_ERR_MEMORY: return "notEnoughMemory";
        case TRS_ERR_JLI: return "runtimeBroken";
        case TRS_ERR_THREAD: return "thread";
        case TRS_ERR_HOOK: return "engineHook";
        default: return "unknown";
    }
}

#pragma mark - Eingabe

void trs_input_key(int key, int scancode, int down, int mods) {
    CallbackBridge_nativeSendKey(key, scancode, down ? 1 : 0, mods);
}

void trs_input_char(uint32_t codepoint) {
    // Wie CallbackBridge_nativeSendChar, aber mit vollem Unicode-Codepunkt.
    if (!GLFW_invoke_Char || !isInputReady) return;
    if (isUseStackQueueCall) {
        sendData(EVENT_TYPE_CHAR, (int)codepoint, 0, 0, 0);
    } else {
        GLFW_invoke_Char((void *)showingWindow, codepoint);
    }
}

void trs_input_mouse_button(int button, int down) {
    CallbackBridge_nativeSendMouseButton(button, down ? 1 : 0, 0);
}

void trs_input_mouse_move_relative(float dx, float dy) {
    CallbackBridge_nativeSendCursorPos(ACTION_MOVE_MOTION, dx, dy);
}

void trs_input_mouse_move_absolute(float x, float y) {
    // Im Spiel (Maus gefangen) gibt es keine absolute Position.
    if (isGrabbing) return;
    CallbackBridge_nativeSendCursorPos(ACTION_DOWN, x, y);
}

void trs_input_scroll(float dx, float dy) {
    CallbackBridge_nativeSendScroll(dx, dy);
}

int trs_input_is_grabbing(void) {
    return isGrabbing ? 1 : 0;
}

void trs_engine_pause_if_needed(void) {
    CallbackBridge_pauseGameIfNeed();
}
