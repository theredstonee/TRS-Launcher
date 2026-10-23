package dev.theredstonee.trsrules.common;

import java.util.Locale;

/** What happens to players whose client does not answer with a TRS Client hello in time ({@code missing-client:}). */
public final class MissingClientPolicy {
	public enum Action {
		/** Nothing (default). */
		IGNORE,
		/** Send {@code notify-message} once. */
		NOTIFY,
		/** Disconnect with {@code kick-message}. */
		KICK;

		public static Action parse(String s) {
			if (s == null) return null;
			try {
				return valueOf(s.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	public static final String DEFAULT_NOTIFY = "&cThis server is made for the &fTRS Client&c. &7Get it free at &fhttps://trs-launcher.theredstonee.de";
	public static final String DEFAULT_KICK = "&cThis server requires the &fTRS Client&c.\n&7Get it free at &fhttps://trs-launcher.theredstonee.de";
	public static final int DEFAULT_TIMEOUT_SECONDS = 10;
	public static final int MIN_TIMEOUT_SECONDS = 1;
	public static final int MAX_TIMEOUT_SECONDS = 600;

	private final Action action;
	private final int timeoutSeconds;
	private final String notifyMessage;
	private final String kickMessage;

	public MissingClientPolicy(Action action, int timeoutSeconds, String notifyMessage, String kickMessage) {
		this.action = action == null ? Action.IGNORE : action;
		this.timeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
		this.notifyMessage = notifyMessage == null ? DEFAULT_NOTIFY : notifyMessage;
		this.kickMessage = kickMessage == null ? DEFAULT_KICK : kickMessage;
	}

	public static MissingClientPolicy defaults() {
		return new MissingClientPolicy(Action.IGNORE, DEFAULT_TIMEOUT_SECONDS, DEFAULT_NOTIFY, DEFAULT_KICK);
	}

	public Action action() {
		return action;
	}

	public int timeoutSeconds() {
		return timeoutSeconds;
	}

	public long timeoutMillis() {
		return timeoutSeconds * 1000L;
	}

	public String notifyMessage() {
		return notifyMessage;
	}

	public String kickMessage() {
		return kickMessage;
	}
}
