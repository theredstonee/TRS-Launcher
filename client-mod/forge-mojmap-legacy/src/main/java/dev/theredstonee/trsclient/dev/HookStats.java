package dev.theredstonee.trsclient.dev;

/** Einfache Aufrufzähler der Mixin-Hooks (Nachweis im Autotest, praktisch kostenlos). */
public final class HookStats {
	public static long fov;
	public static long press;
	public static long scroll;
	public static long turn;
	public static long lightmap;

	private HookStats() {
	}

	public static String summary() {
		return "getFov=" + fov + ", onPress=" + press + ", onScroll=" + scroll
				+ ", turnPlayer=" + turn + ", updateLightTexture=" + lightmap;
	}
}
