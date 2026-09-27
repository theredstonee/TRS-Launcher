package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.circuit.Circuit;
import dev.theredstonee.trsclient.core.circuit.CircuitCapture;
import dev.theredstonee.trsclient.core.circuit.CircuitCheck;
import dev.theredstonee.trsclient.core.circuit.CircuitLibrary;
import dev.theredstonee.trsclient.core.circuit.CircuitLibraryPage;
import dev.theredstonee.trsclient.core.circuit.CircuitSubmissions;
import dev.theredstonee.trsclient.core.circuit.CircuitSync;
import dev.theredstonee.trsclient.core.circuit.Circuits;
import dev.theredstonee.trsclient.core.circuit.Placement;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Map;

/**
 * Selbsttest „Schaltungs-Bibliothek“ ({@code -PtrsAutotestOnly=circuits}, mit API-Attrappe
 * {@code scratchpad/circuits/mock-api.mjs}): Bibliothek vom Server, Detailseite mit Vorschau, Vorlage an fester Stelle
 * + teilweise per Befehl nachgebaut (grün/rot/grau + Fortschritt), Platzieren nach Blick, Bereich markieren →
 * Einreichen → „Meine Einreichungen“. Screenshots trsclient-&lt;mc&gt;-circuits-*.png.
 */
public final class CircuitTest {
	private int phase;
	private int wait;
	private int waited;
	private int bx;
	private int by;
	private int bz;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		Circuits circuits = Circuits.get();
		CircuitLibrary lib = CircuitLibrary.get();
		switch (phase++) {
			case 0: {
				if (mc.player == null) return false;
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule send_command_feedback false");
				actions.command("gamemode creative @p");
				actions.command("time set day");
				actions.command("weather clear");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.dynamicFps.setEnabled(false);
				modules.circuits.circuitLibrary.setEnabled(true);
				bx = (int) Math.floor(Mc.cameraX()) + 3;
				by = (int) Math.floor(Mc.cameraY() - 1.62);
				bz = (int) Math.floor(Mc.cameraZ()) + 3;
				// ebene Fläche: Boden aus Stein, darüber frei
				actions.command(String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", bx - 6, by - 1, bz - 6, bx + 12, by - 1, bz + 12));
				actions.command(String.format("fill %d %d %d %d %d %d minecraft:air", bx - 6, by, bz - 6, bx + 12, by + 8, bz + 12));
				Mc.setScreen(null);
				wait = 30;
				return true;
			}
			case 1:
				// Bibliothek kommt vom Server (Attrappe): warten, bis sie da ist
				if (lib.byId("xor_gate") == null && waited++ < 600) {
					phase--;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Bibliothek: {} Schaltungen, Abgleich {}", lib.all().size(), CircuitSync.status());
				CircuitLibraryPage.requestOpen();
				Mc.setScreen(new TrsMenuScreen(null));
				wait = 25;
				return true;
			case 2:
				actions.shot("trsclient-circuits-library");
				CircuitLibraryPage.requestOpen(lib.byId("piston_door_2x2"));
				Mc.setScreen(new TrsMenuScreen(null));
				wait = 30;
				return true;
			case 3:
				actions.shot("trsclient-circuits-detail");
				CircuitLibraryPage.requestOpen(lib.byId("xor_gate"));
				Mc.setScreen(new TrsMenuScreen(null));
				wait = 30;
				return true;
			case 4: {
				actions.shot("trsclient-circuits-detail-xor");
				Mc.setScreen(null);
				Circuit xor = lib.byId("xor_gate");
				circuits.placeAt(xor, bx, by, bz, 0, false);
				build(actions, xor, new Placement(bx, by, bz, 0, false), true);
				// Kamera schräg über die Vorlage – auf einem unsichtbaren Barriere-Block (sonst fällt der Spieler)
				actions.command(String.format("setblock %d %d %d minecraft:barrier", bx + 2, by + 4, bz - 4));
				actions.command(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 0 50", bx + 2.5, by + 5, bz - 3.5));
				wait = 40;
				return true;
			}
			case 5: {
				CircuitCheck check = circuits.check();
				TrsClient.LOGGER.info("[Autotest] Schaltung: {}/{} richtig, {} falsch, {} fehlen", check == null ? -1 : check.correct(),
						check == null ? -1 : check.total(), check == null ? -1 : check.wrong(), check == null ? -1 : check.missing());
				actions.shot("trsclient-circuits-ghost");
				// Platzieren nach Blick (blaue Vorschau)
				circuits.startPlacing(lib.byId("piston_door_2x2"), false);
				actions.command(String.format("setblock %d %d %d minecraft:barrier", bx + 3, by + 3, bz - 3));
				actions.command(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 0 38", bx + 3.5, by + 4, bz - 2.5));
				wait = 30;
				return true;
			}
			case 6: {
				actions.shot("trsclient-circuits-placing");
				circuits.remove();
				// Eigene Schaltung: NICHT-Gatter vollständig bauen, Bereich markieren
				Circuit not = lib.byId("not_gate");
				actions.command(String.format("fill %d %d %d %d %d %d minecraft:air", bx, by, bz, bx + 8, by + 3, bz + 8));
				build(actions, not, new Placement(bx + 2, by, bz + 4, 0, false), false);
				circuits.startSelecting();
				circuits.selectCorner(new int[] {bx + 2, by, bz + 4});
				actions.command(String.format("setblock %d %d %d minecraft:barrier", bx + 5, by + 1, bz + 2));
				actions.command(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 25 50", bx + 5.5, by + 2, bz + 2.5));
				wait = 30;
				return true;
			}
			case 7: {
				actions.shot("trsclient-circuits-select");
				Circuit not = lib.byId("not_gate");
				CircuitCapture.Result r = circuits.captureNow(circuits.world(), new int[] {bx + 2, by, bz + 4},
						new int[] {bx + 2 + not.sizeX - 1, by + not.sizeY - 1, bz + 4 + not.sizeZ - 1});
				TrsClient.LOGGER.info("[Autotest] Ausgelesen: {} {}", r.error == null ? "ok" : r.error,
						r.parsed == null ? "-" : r.parsed.blockCount() + " Blöcke " + r.parsed.sizeX + "x" + r.parsed.sizeY + "x" + r.parsed.sizeZ);
				circuits.cancelSelecting();
				CircuitLibraryPage.requestSubmit();
				Mc.setScreen(new TrsMenuScreen(null));
				wait = 25;
				return true;
			}
			case 8: {
				actions.shot("trsclient-circuits-submit");
				CircuitSubmissions api = circuits.submissions();
				CircuitCapture.Result r = circuits.capture();
				TrsClient.LOGGER.info("[Autotest] Angemeldet: {}", api != null && api.loggedIn());
				if (api != null && api.loggedIn() && r != null && r.circuit != null) {
					com.google.gson.JsonObject c = new com.google.gson.JsonParser().parse(r.circuit.toString()).getAsJsonObject();
					c.addProperty("id", "autotest_inverter");
					api.submitAsync(c, "Autotest-Inverter", "basics", "Aus dem Spiel eingereicht", "de");
				}
				wait = 40;
				return true;
			}
			case 9: {
				CircuitSubmissions api = circuits.submissions();
				CircuitSubmissions.Result res = api == null ? null : api.lastResult();
				TrsClient.LOGGER.info("[Autotest] Einreichen: {}", res == null ? "-" : res.ok ? "ok " + res.id + " " + res.status : res.error);
				actions.shot("trsclient-circuits-submitted");
				CircuitLibraryPage.requestMine();
				Mc.setScreen(new TrsMenuScreen(null));
				wait = 40;
				return true;
			}
			case 10:
				actions.shot("trsclient-circuits-mine");
				Mc.setScreen(null);
				circuits.clearCapture();
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	/**
	 * Setzt die Schaltung per Befehl. {@code partly}: etwa zwei Drittel richtig, einen Verstärker falsch herum, den Rest
	 * weglassen.
	 */
	private void build(CapeTest.Actions actions, Circuit c, Placement p, boolean partly) {
		int[] pos = new int[3];
		int n = 0;
		boolean wrongDone = !partly;
		for (Circuit.Cell cell : c.cells) {
			if (cell.spec.optional) continue;
			n++;
			p.toWorld(c, cell.x, cell.y, cell.z, pos);
			Map<String, String> props = p.props(cell.spec);
			String key = cell.spec.def.anySolid() ? "stone" : cell.spec.def.key;
			if (!wrongDone && "repeater".equals(key)) {
				props = new java.util.TreeMap<String, String>(props);
				props.put("facing", "north".equals(props.get("facing")) ? "south" : "north");
				wrongDone = true;
			} else if (partly && n % 3 == 0) {
				continue; // fehlt
			}
			StringBuilder b = new StringBuilder("minecraft:").append(key);
			if (!props.isEmpty()) {
				b.append('[');
				boolean first = true;
				for (Map.Entry<String, String> e : props.entrySet()) {
					if (!first) b.append(',');
					b.append(e.getKey()).append('=').append(e.getValue());
					first = false;
				}
				b.append(']');
			}
			actions.command(String.format("setblock %d %d %d %s", pos[0], pos[1], pos[2], b));
		}
	}
}
