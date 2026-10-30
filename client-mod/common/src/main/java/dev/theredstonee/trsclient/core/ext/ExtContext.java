package dev.theredstonee.trsclient.core.ext;

import dev.theredstonee.trsclient.core.module.TrsModules;

import java.io.File;

/** What the loader-specific client code hands to {@link TrsExtension}s. Plain values, no Minecraft types. */
public final class ExtContext {
	private ExtContext() {
	}

	/** Is a mod with this id loaded? */
	public interface ModCheck {
		boolean loaded(String id);
	}

	/** Log sink of the TRS Client. */
	public interface Log {
		void info(String line);
	}

	/** Values available before Minecraft starts. */
	public static final class Early {
		/** {@code config/} of the game. */
		public final File configDir;
		public final ModCheck mods;
		/** Minecraft version, e.g. {@code 1.21.11} (may be empty if the loader does not know it yet). */
		public final String minecraft;
		/** {@code fabric}, {@code neoforge}, {@code forge}, … */
		public final String loader;
		public final Log log;

		public Early(File configDir, ModCheck mods, String minecraft, String loader, Log log) {
			this.configDir = configDir;
			this.mods = mods;
			this.minecraft = minecraft == null ? "" : minecraft;
			this.loader = loader;
			this.log = log;
		}
	}

	/** Values available once the client is set up. */
	public static final class Client {
		public final TrsModules modules;
		public final File configDir;
		public final ModCheck mods;
		public final String minecraft;
		public final String loader;
		public final Log log;
		/** Can the size of Minecraft's background pool still be chosen at this point of the start? */
		public final boolean poolStartup;

		public Client(TrsModules modules, File configDir, ModCheck mods, String minecraft, String loader, Log log,
				boolean poolStartup) {
			this.modules = modules;
			this.configDir = configDir;
			this.mods = mods;
			this.minecraft = minecraft == null ? "" : minecraft;
			this.loader = loader;
			this.log = log;
			this.poolStartup = poolStartup;
		}
	}
}
