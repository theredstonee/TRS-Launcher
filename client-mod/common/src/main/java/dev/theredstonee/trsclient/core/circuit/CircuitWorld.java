package dev.theredstonee.trsclient.core.circuit;

import java.util.Map;

/**
 * Lesezugriff auf die Client-Welt für den Abgleich „Vorlage gegen Welt“ – je Minecraft-Version ein kleiner Adapter
 * (neben {@code compat/RedstoneProbe}). Nur Lesen dessen, was der Client ohnehin kennt; nichts wird gesendet.
 */
public interface CircuitWorld {
	/**
	 * Registry-Name des Blocks ({@code minecraft:repeater}) und seine Zustands-Eigenschaften (Name → Wert).
	 *
	 * @return null, wenn der Chunk nicht geladen ist
	 */
	String block(int x, int y, int z, Map<String, String> props);

	/** Voller, leitender Block (für „beliebiger fester Block“)? */
	boolean conductor(int x, int y, int z);

	/** Minecraft 1.8.9–1.12.2 (alte Block-Namen)? */
	boolean legacy();
}
