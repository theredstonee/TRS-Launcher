package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.creator.Milestone;

/**
 * Wen ein Spieler als Fan unterstützt ({@code fan} im Lookup, API.md §35.5): Creator, dessen Fan-Symbol und der höchste
 * erreichte Meilenstein (farbiger Rahmen ums Symbol). Die API liefert {@code fan} nur, wenn der Betrachter das Symbol
 * sehen darf (Blockierungen, {@code showFanSymbols}).
 */
public final class FanInfo {
	/** UUID des Creators (32 Hex, normalisiert). */
	public final String creator;
	/** Anzeigename des Creators (nur zur Info, höchstens 32 Zeichen) oder null. */
	public final String name;
	public final SymbolInfo symbol;
	/** null = noch kein Meilenstein. */
	public final Milestone milestone;

	FanInfo(String creator, String name, SymbolInfo symbol, Milestone milestone) {
		this.creator = creator;
		this.name = name;
		this.symbol = symbol;
		this.milestone = milestone;
	}

	/** Prüft streng; ohne gültigen Creator oder ohne gültiges Symbol → null. Unbekannter Meilenstein → ohne Rahmen. */
	public static FanInfo of(String creator, String name, SymbolInfo symbol, String milestone) {
		String c = Uuids.normalize(creator);
		if (c == null || symbol == null) return null;
		String n = name == null || name.isEmpty() || name.length() > 32 ? null : name;
		return new FanInfo(c, n, symbol, Milestone.parse(milestone));
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof FanInfo)) return false;
		FanInfo f = (FanInfo) o;
		return creator.equals(f.creator) && symbol.equals(f.symbol) && milestone == f.milestone
				&& (name == null ? f.name == null : name.equals(f.name));
	}

	@Override
	public int hashCode() {
		return creator.hashCode() * 31 + symbol.hashCode();
	}
}
