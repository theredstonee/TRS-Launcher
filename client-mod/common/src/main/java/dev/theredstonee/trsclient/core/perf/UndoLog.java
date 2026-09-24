package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * „Rückgängig“ für FPS-Boost und Leistungs-Check: merkt sich beim ersten Ändern den
 * ursprünglichen Wert jeder Vanilla-Option und jedes Leistungs-Moduls (spätere Änderungen
 * überschreiben den Ursprungswert nicht). Wird in {@code trsclient.json} gespeichert – auch nach
 * einem Neustart lässt sich also alles zurücksetzen.
 */
public final class UndoLog implements ConfigPart {
	private final Map<GameOptions.Opt, Integer> options = new EnumMap<GameOptions.Opt, Integer>(GameOptions.Opt.class);
	private final Map<String, ModuleConfig> modules = new LinkedHashMap<String, ModuleConfig>();

	public void rememberOption(GameOptions.Opt opt, int old) {
		if (old != GameOptions.NONE && !options.containsKey(opt)) options.put(opt, Integer.valueOf(old));
	}

	public void rememberModule(Module module) {
		if (!modules.containsKey(module.id())) modules.put(module.id(), module.write());
	}

	public boolean isEmpty() {
		return options.isEmpty() && modules.isEmpty();
	}

	/** Gemerkte Vanilla-Optionen (nur lesen). */
	public Map<GameOptions.Opt, Integer> options() {
		return options;
	}

	/**
	 * Stellt alles Gemerkte wieder her und leert das Protokoll.
	 * @return Anzahl zurückgesetzter Optionen + Module
	 */
	public int undo(GameOptions game, ModuleRegistry registry) {
		int n = 0;
		if (game != null) {
			for (Map.Entry<GameOptions.Opt, Integer> e : options.entrySet()) {
				if (game.set(e.getKey(), e.getValue().intValue())) n++;
			}
			if (!options.isEmpty()) game.save();
		}
		for (Map.Entry<String, ModuleConfig> e : modules.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (m == null) continue;
			m.read(e.getValue().copy().normalized());
			n++;
		}
		options.clear();
		modules.clear();
		return n;
	}

	public void clear() {
		options.clear();
		modules.clear();
	}

	@Override
	public void read(TrsConfig config) {
		options.clear();
		modules.clear();
		TrsConfig.PerfUndo stored = config.perfUndo;
		if (stored == null) return;
		if (stored.options != null) {
			for (Map.Entry<String, Integer> e : stored.options.entrySet()) {
				if (e.getKey() == null || e.getValue() == null) continue;
				for (GameOptions.Opt o : GameOptions.Opt.values()) {
					if (o.name().equalsIgnoreCase(e.getKey())) options.put(o, Integer.valueOf(o.clamp(e.getValue().intValue())));
				}
			}
		}
		if (stored.modules != null) {
			for (Map.Entry<String, ModuleConfig> e : stored.modules.entrySet()) {
				if (e.getKey() != null && e.getValue() != null) modules.put(e.getKey(), e.getValue().normalized());
			}
		}
	}

	@Override
	public void write(TrsConfig config) {
		if (isEmpty()) {
			config.perfUndo = null;
			return;
		}
		TrsConfig.PerfUndo out = new TrsConfig.PerfUndo();
		for (Map.Entry<GameOptions.Opt, Integer> e : options.entrySet()) {
			out.options.put(e.getKey().name().toLowerCase(java.util.Locale.ROOT), e.getValue());
		}
		for (Map.Entry<String, ModuleConfig> e : modules.entrySet()) out.modules.put(e.getKey(), e.getValue().copy());
		config.perfUndo = out;
	}
}
