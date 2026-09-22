package dev.theredstonee.trsclient.dev;

/** Einfache Aufrufzähler der Event-/Mixin-Hooks (Nachweis im Autotest, praktisch kostenlos). */
public final class HookStats {
	public static long fov;
	public static long press;
	public static long scroll;
	public static long turn;
	public static long lightmap;
	public static long hud;

	private HookStats() {
	}

	public static String summary() {
		return "ComputeFov=" + fov + ", MouseButton=" + press + ", MouseScrolling=" + scroll
				+ ", CalculatePlayerTurn=" + turn + ", updateLightTexture(Mixin)=" + lightmap + ", HudLayer=" + hud;
	}
}
