// TRS Launcher – Ersatz für die Amethyst-Oberfläche, die der Engine-Code erwartet.
// Teile (Zwischenablage, showError) sind aus Amethyst-iOS ios_uikit_bridge.m übernommen
// (GPL-3.0, Copyright (C) 2021 Tran Hoang Khanh Duy und Mitwirkende).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#import <UIKit/UIKit.h>

#import "LauncherPreferences.h"
#import "MinecraftOptionUtils.h"
#import "PLLogOutputView.h"
#import "SurfaceViewController.h"
#import "UIKit+hook.h"

#include "ios_uikit_bridge.h"
#include "jni.h"
#include "trs_engine_internal.h"
#include "utils.h"

BOOL canAppendToLog;
dispatch_group_t fatalExitGroup;

// Gesetzt von trs_engine_set_surface (starke Referenz, solange das Spiel läuft).
UIView *trsSurfaceView;
BOOL trsEngineRunning;

@implementation SurfaceViewController
+ (UIView *)surface {
    return trsSurfaceView;
}
+ (BOOL)isRunning {
    return trsEngineRunning;
}
- (void)updateGrabState {
}
@end

@implementation PLLogOutputView
+ (void)appendToLog:(NSString *)line {
}
+ (BOOL)handleExitCode:(int)code {
    trs_engine_will_exit(code);
    // Nur warten, wenn Swift wirklich einen Dialog zeigt (sonst hinge der Prozess).
    return trs_engine_wants_exit_ack() ? YES : NO;
}
@end

@implementation MinecraftOptionUtils
+ (instancetype)sharedInstance {
    static MinecraftOptionUtils *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        instance = [MinecraftOptionUtils new];
    });
    return instance;
}
- (void)updateMCGuiScale {
    // Nur für Amethysts Hotbar-Tippen gebraucht; das TRS-Overlay rechnet selbst.
}
@end

@implementation UIWindow (TRSMainWindow)
+ (UIWindow *)mainWindow {
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if (![scene isKindOfClass:UIWindowScene.class]) continue;
        for (UIWindow *window in ((UIWindowScene *)scene).windows) {
            if (window.isKeyWindow) return window;
        }
    }
    return UIApplication.sharedApplication.windows.firstObject;
}
@end

// Feste Einstellungen statt Amethysts Einstellungsdatei.
id getPrefObject(NSString *key) {
    return nil;
}
BOOL getPrefBool(NSString *key) {
    // StikDebug-Debugger dauerhaft angehängt lassen: nur über die Umgebung TRS_JIT_ALWAYS_ATTACHED.
    if ([key isEqualToString:@"debug.debug_always_attached_jit"]) {
        return getenv("TRS_JIT_ALWAYS_ATTACHED") != NULL;
    }
    return NO;
}
float getPrefFloat(NSString *key) {
    return [key isEqualToString:@"video.resolution"] ? 100.0f : 0.0f;
}
NSInteger getPrefInt(NSString *key) {
    return 0;
}

static void showDialogNow(NSString *title, NSString *message) {
    UIViewController *vc = UIWindow.mainWindow.rootViewController;
    while (vc.presentedViewController) vc = vc.presentedViewController;
    if (!vc) return;
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:title message:message preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
    [vc presentViewController:alert animated:YES completion:nil];
}

void showDialog(NSString *title, NSString *message) {
    NSLog(@"[UI] Dialog: %@: %@", title, message);
    dispatch_async(dispatch_get_main_queue(), ^{
        showDialogNow(title, message);
    });
}

void UIKit_returnToSplitView(void) {
}

// Aus Amethyst ios_uikit_bridge.m.
jstring UIKit_accessClipboard(JNIEnv *env, jint action, jstring copySrc) {
    if (action == CLIPBOARD_PASTE) {
        if (UIPasteboard.generalPasteboard.hasStrings) {
            return (*env)->NewStringUTF(env, [UIPasteboard.generalPasteboard.string UTF8String]);
        }
        return (*env)->NewStringUTF(env, "");
    } else if (action == CLIPBOARD_COPY) {
        const char *copySrcC = (const char *)(*env)->GetByteArrayElements(env, copySrc, 0);
        if (copySrcC) {
            UIPasteboard.generalPasteboard.string = @(copySrcC);
            (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
        }
        return NULL;
    }
    NSLog(@"Warning: unknown clipboard action: %x", action);
    return NULL;
}

// net.kdt.pojavlaunch.uikit.UIKit.showError – im Spiel nur ins Log, sonst Dialog.
JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_uikit_UIKit_showError(JNIEnv *env, jclass clazz, jstring title, jstring message, jboolean exitIfOk) {
    const char *title_c = (*env)->GetStringUTFChars(env, title, 0);
    const char *message_c = (*env)->GetStringUTFChars(env, message, 0);
    NSString *title_o = @(title_c);
    NSString *message_o = @(message_c);
    (*env)->ReleaseStringUTFChars(env, title, title_c);
    (*env)->ReleaseStringUTFChars(env, message, message_c);
    NSLog(@"%@\n%@", title_o, message_o);
    if (trsEngineRunning || exitIfOk) {
        return;
    }
    showDialog(title_o, message_o);
}
