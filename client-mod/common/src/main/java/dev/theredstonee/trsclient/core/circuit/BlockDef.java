package dev.theredstonee.trsclient.core.circuit;

import java.util.Collections;
import java.util.List;

/**
 * Ein Block, den Schaltungen der Bibliothek benutzen (aus {@code circuits/blocks.json}): moderne ID ohne
 * {@code minecraft:}, Gegenstand für die Materialliste, ab welcher Minecraft-Version es ihn gibt und welche
 * Zustands-Eigenschaften beim Abgleich mit der Welt zählen.
 */
public final class BlockDef {
	/** Schlüssel = moderne Block-ID ohne Namensraum; {@link #SOLID} = beliebiger voller, leitender Block. */
	public final String key;
	/** Gegenstand (moderne ID ohne Namensraum) bzw. in 1.8.9–1.12.2. */
	public final String item;
	public final String legacyItem;
	/** Ab dieser Minecraft-Version gibt es den Block ("1.8" = immer). */
	public final String since;
	/** Art in der Simulation ({@code solid}, {@code dust}, {@code repeater} …). */
	public final String sim;
	/** Eigenschaften, die mit der Welt verglichen werden (z. B. facing, delay, mode). */
	public final List<String> check;
	/** Sonderregel für alte Versionen: {@code torch}, {@code attach} oder null. */
	public final String rule;

	public static final String SOLID = "solid";

	BlockDef(String key, String item, String legacyItem, String since, String sim, List<String> check, String rule) {
		this.key = key;
		this.item = item;
		this.legacyItem = legacyItem == null ? item : legacyItem;
		this.since = since == null ? "1.8" : since;
		this.sim = sim == null ? "other" : sim;
		this.check = check == null ? Collections.<String>emptyList() : Collections.unmodifiableList(check);
		this.rule = rule;
	}

	/** Beliebiger fester Block statt einer bestimmten Art? */
	public boolean anySolid() {
		return SOLID.equals(key);
	}

	/** Schlüssel des Anzeigenamens in den Schaltungstexten. */
	public String labelKey() {
		return "block." + key;
	}

	@Override
	public String toString() {
		return key;
	}
}
