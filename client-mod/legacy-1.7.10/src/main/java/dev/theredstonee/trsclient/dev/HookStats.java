package dev.theredstonee.trsclient.dev;

/** Einfache Aufrufzähler der Hooks (Nachweis im Autotest, praktisch kostenlos). */
public final class HookStats {
	public static long fov;
	public static long mouseEvent;
	public static long scroll;
	public static long mouse;
	public static long lightmap;
	public static long hud;
	public static long crosshair;
	public static long toggle;

	private HookStats() {
	}

	public static String summary() {
		return "FOV-Zoom=" + fov + ", MouseEvent=" + mouseEvent + ", Zoom-Scroll=" + scroll
				+ ", mouseXYChange=" + mouse + ", Fullbright-Gamma=" + lightmap + ", HUD=" + hud
				+ ", Fadenkreuz=" + crosshair + ", Toggle-Taste=" + toggle;
	}
}
