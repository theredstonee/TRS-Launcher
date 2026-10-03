// Adapted from Amethyst-Android (LGPL-3.0-or-later), app_pojavlauncher/.../ExitActivity.java
// at commit 330c6eae3164df64bdc4828e946a9e62cc5169e4. TRS patch: no activity/dialog any more –
// libpojavexec's exit trap (stdio_is.c nominal_exit) calls showExitMessage(), which now reports
// the exit to the launcher process through dev.theredstonee.trs.game.engine.ExitBridge.
package net.kdt.pojavlaunch;

import android.content.Context;

import androidx.annotation.Keep;

import dev.theredstonee.trs.game.engine.ExitBridge;

@Keep
public final class ExitActivity {
    private ExitActivity() {}

    @Keep
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        ExitBridge.onJvmExit(ctx, code, isSignal);
    }
}
