package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.module.TrsModules;

import java.util.regex.Pattern;

/**
 * Teleport von der Karte: nur der normale Befehl, den auch der Chat senden würde. Ob er wirkt, entscheidet allein der
 * Server – es werden nie Bewegungspakete geschickt. Der Eintrag erscheint nur, wenn die Version meldet, dass der Spieler
 * den Befehl benutzen darf ({@link MapPlatform#commandAccess()}).
 *
 * <p>Syntax: {@code tp X Y Z} geht von 1.8.9 bis heute (der Selektor {@code @s} fehlt auf 1.8.9 und wird hier gar nicht
 * gebraucht: Ohne Ziel teleportiert der Befehl den Absender). In eine andere Dimension {@code execute in <dim> run tp X Y
 * Z} – das gibt es ab 1.13.
 */
public final class WaypointTeleport {
	/** {@link MapPlatform#commandAccess()}: {@code tp} erlaubt. */
	public static final int TP = 1;
	/** {@link MapPlatform#commandAccess()}: zusätzlich {@code execute in} erlaubt (ab 1.13). */
	public static final int EXECUTE_IN = 2;

	/** Nur so geformte Dimensionen kommen in einen Befehl (sie können von Freunden übernommen sein). */
	private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
	private static final int MAX_XZ = 30_000_000;
	private static final int MAX_Y = 20_000;

	private WaypointTeleport() {
	}

	/**
	 * Befehl ohne Schrägstrich, null = in diesem Fall nicht möglich.
	 *
	 * @param access     {@link #TP}, {@link #EXECUTE_IN}
	 * @param targetDim  Dimension des Ziels ("" oder null = die eigene)
	 * @param currentDim eigene Dimension
	 */
	public static String command(int access, int x, int y, int z, String targetDim, String currentDim) {
		if ((access & TP) == 0) return null;
		if (Math.abs((long) x) > MAX_XZ || Math.abs((long) z) > MAX_XZ || Math.abs((long) y) > MAX_Y) return null;
		String tp = "tp " + x + " " + y + " " + z;
		if (targetDim == null || targetDim.isEmpty() || targetDim.equals(currentDim)) return tp;
		if ((access & EXECUTE_IN) == 0 || !DIMENSION.matcher(targetDim).matches()) return null;
		return "execute in " + targetDim + " run " + tp;
	}

	/** Darf der Eintrag erscheinen? (Einstellung/Server-Regel und Rechte des Spielers.) */
	public static boolean possible(TrsModules modules, MapPlatform platform, String targetDim) {
		if (modules == null || platform == null || !modules.waypointTeleport.get()) return false;
		return command(platform.commandAccess(), 0, 64, 0, targetDim, platform.dimension()) != null;
	}

	/** Sendet den Befehl; false = in diesem Fall nicht möglich. */
	public static boolean send(TrsModules modules, MapPlatform platform, int x, int y, int z, String targetDim) {
		if (modules == null || platform == null || !modules.waypointTeleport.get()) return false;
		String cmd = command(platform.commandAccess(), x, y, z, targetDim, platform.dimension());
		if (cmd == null) return false;
		platform.sendChat("/" + cmd);
		return true;
	}
}
