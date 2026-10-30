package dev.theredstonee.trsclient.core.ext;

/**
 * Optional add-on module of the TRS Client that is not part of the open-source build (it is shipped only inside the
 * release jars). The open-source client runs fine without any extension: every call site below is a no-op then and
 * the menu shows the affected pages as "not available in this build".
 * <p>
 * Extensions are listed in {@code META-INF/services/dev.theredstonee.trsclient.core.ext.TrsExtension} and loaded by
 * {@link TrsExtensions}. Every method is called inside a try/catch – an extension can never stop the game from
 * starting. All callbacks run on the thread named in their documentation and must return quickly.
 */
public interface TrsExtension {
	/** Stable id, e.g. {@code smartThreads}. */
	String id();

	/** Earliest hook (before Minecraft's classes load where the loader allows it). */
	default void preLaunch(ExtContext.Early early) {
	}

	/** Client start, after the TRS Client config and modules are ready (client thread). */
	default void clientInit(ExtContext.Client client) {
	}

	/** Every client tick (client thread). {@code minecraft} is the game instance of the running version. */
	default void clientTick(Object minecraft) {
	}

	/** Start of every frame, before the frame limiter (render thread). */
	default void frameStart() {
	}

	/**
	 * Block entity culling: may the block entity at x/y/z be skipped this frame (render thread)? Only asked when the
	 * client's own distance check kept it.
	 */
	default boolean hideBlockEntity(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
		return false;
	}

	/** Runs the autotest scenario {@code name} (dev builds, {@code -Dtrsclient.autotest.only}); true if handled. */
	default boolean autotest(String name) {
		return false;
	}
}
