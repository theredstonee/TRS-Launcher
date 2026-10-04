package dev.theredstonee.trsclient.core.connect;

import java.util.List;

/** Nachschlagen von SRV und A/AAAA – im Spiel {@link SystemResolver}, in Tests eine Attrappe. */
public interface DnsLookup {
	/** Ergebnis einer SRV-Abfrage. */
	final class SrvAnswer {
		/** true = sicher kein Eintrag (Name/Eintrag existiert nicht); false + record null = Fehler (z. B. Zeitlimit). */
		public final boolean definitive;
		public final SrvRecord record;

		SrvAnswer(boolean definitive, SrvRecord record) {
			this.definitive = definitive;
			this.record = record;
		}

		public static final SrvAnswer NONE = new SrvAnswer(true, null);
		public static final SrvAnswer ERROR = new SrvAnswer(false, null);

		public static SrvAnswer of(SrvRecord r) {
			return r == null ? NONE : new SrvAnswer(true, r);
		}
	}

	SrvAnswer srv(String host);

	/** Alle Adressen (Rohbytes) in der Reihenfolge des Systems; leere Liste = unbekannt. */
	List<byte[]> addresses(String host);
}
