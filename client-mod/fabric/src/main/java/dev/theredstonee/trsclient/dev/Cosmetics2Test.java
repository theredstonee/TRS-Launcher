package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.wardrobe.WardrobeUi;
import dev.theredstonee.trsclient.core.wardrobe.CosmeticCatalog;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeContext;
import dev.theredstonee.trsclient.online.OnlineHooks;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest „Kopf-Kosmetik v2“ ({@code -PtrsAutotestOnly=cosmetics2}, mit der v2-Attrappe
 * {@code scratchpad/cos2mod/mock-api.mjs}): setzt über den Katalog ({@code PUT /v1/me/cosmetics}) nacheinander alle
 * sechs Studio-Teile auf, wartet, bis Modell und Texturen geladen sind, und fotografiert jedes in der 3. Person von vorne,
 * schräg und hinten – bei Tag und bei Nacht (Kamera per Freelook um die Figur, Zoom auf den Kopf). Dazu die Garderobe
 * (Reiter „Kosmetik“ bei Tag/Nacht, gesperrtes Teil) und die Quietscheente (Format 1).
 */
public final class Cosmetics2Test {
	private static final String[] IDS = { "redstone_crown", "team_crown", "trs_cap", "lamp_helmet", "top_hat", "halo" };

	private int phase;
	private int wait;
	private int tries;
	private int item;
	private boolean night;
	private CosmeticCatalog catalog;
	private WardrobeUi ui;
	/** -PtrsAutotestOnly=crowntime: nur die Redstone-Krone, Tempo der Animation belegen (Bildfolge + Frame-Protokoll). */
	private final boolean timing = "crowntime".equals(System.getProperty("trsclient.autotest.only"));
	private final java.util.List<long[]> trace = java.util.Collections.synchronizedList(new java.util.ArrayList<long[]>());
	private int ticks;
	private long framesAtStart;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		face(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase) {
			case 0:
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("effect clear @p");
				//? if >=1.17 {
				actions.command("item replace entity @p armor.head with air");
				//?} else
				/*actions.command("replaceitem entity @p armor.head air");*/
				actions.command("execute as @p at @s run fill ~-8 ~ ~-8 ~8 ~8 ~8 air");
				actions.command("execute as @p at @s run fill ~-8 ~-1 ~-8 ~8 ~-1 ~8 grass_block");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				modules.fullbright.setEnabled(false);
				Mc.setScreen(null);
				Mc.setHudHidden(true);
				catalog = CosmeticCatalog.shared(new WardrobeContext(I18n.configDir(), "TRS-Client", OnlineHooks.features()));
				catalog.refresh(true);
				phase = 1;
				wait = 20;
				return true;
			case 1: {
				CosmeticCatalog.State s = catalog.state();
				if (!s.loaded && tries++ < 400) {
					if (tries % 100 == 0) catalog.refresh(true);
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Kosmetik: Katalog {} ({} Teile, getragen {}) nach {} Ticks",
						s.loaded ? "geladen" : "FEHLT", s.items.size(), s.equipped, tries);
				dev.theredstonee.trsclient.compat.ChatLines.chat().clearMessages(false);
				tries = 0;
				item = 0;
				phase = 2;
				return true;
			}
			case 2:
				// Teil aufsetzen (über die API wie in der Garderobe)
				catalog.wear(IDS[item]);
				night = false;
				tries = 0;
				phase = 3;
				wait = 5;
				return true;
			case 3: {
				V2Hat<Object> hat = OnlineHooks.features() == null ? null : OnlineHooks.features().hatV2(mc.player.getUUID(), false);
				boolean ok = hat != null && IDS[item].equals(hat.model.id);
				if (!ok && tries++ < 600) {
					// eigener Lookup wird nach dem Aufsetzen aufgefrischt; zur Not noch einmal
					if (tries % 200 == 0) OnlineHooks.features().online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Kosmetik {}: {} nach {} Ticks", IDS[item], ok ? "geladen" : "FEHLT", tries);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				if (timing) {
					actions.command("time set midnight");
					camera(mc, 160F, 10F);
					phase = 40;
					wait = 20;
					return true;
				}
				phase = 4;
				wait = 4;
				return true;
			}
			case 40:
				// 3 s lang jeden gezeichneten Durchgang protokollieren (ohne Screenshots, volle Bildrate), danach alle
				// 2 Ticks (100 ms) ein Bildschirmfoto
				trace.clear();
				framesAtStart = dev.theredstonee.trsclient.perf.PerfHooks.frames;
				dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Trace = (hat, pass) -> trace.add(new long[]{System.nanoTime(),
						hat.now, glowIndex(hat.glow), pass, dev.theredstonee.trsclient.perf.PerfHooks.frames});
				ticks = 0;
				phase = 41;
				return true;
			case 41:
				if (++ticks < 60) return true;
				dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Trace = null;
				report(dev.theredstonee.trsclient.perf.PerfHooks.frames - framesAtStart);
				ticks = 0;
				camera(mc, 180F, 12F);
				phase = 42;
				return true;
			case 42: {
				// Feste Uhrzeiten wie die Werkbank mit ?t= (Vergleichsbilder: workbench.html?model=redstone_crown&shot=front&t=…)
				int k = ticks / 3;
				if (ticks % 3 == 0) {
					if (k > 0) actions.shot(String.format("trsclient-crownfixed-t%04d", (k - 1) * 140));
					if (k < 12) {
						final long fixed = k * 140L;
						dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Clock = () -> fixed;
					}
				}
				if (++ticks <= 36) return true;
				dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Clock = null;
				camera(mc, 160F, 10F);
				ticks = 0;
				phase = 43;
				wait = 4;
				return true;
			}
			case 43:
				if (ticks % 2 == 0) actions.shot(String.format("trsclient-crowntime-%02d", ticks / 2));
				if (++ticks < 36) return true;
				// Normale Spielansicht (3. Person, ohne Zoom): Flimmern der HD-Texturen aus normaler Entfernung
				TrsClient.get().setForceZoom(false);
				camera(mc, 160F, 10F);
				ticks = 0;
				phase = 44;
				wait = 10;
				return true;
			case 44:
				actions.shot(String.format("trsclient-crowndist-%02d", ticks));
				if (++ticks < 20) return true;
				// Animation angehalten (feste Uhrzeit), nur die Kamera kreist langsam (0,5° je Tick): was sich jetzt von Bild zu
				// Bild ändert, ist reines Abtast-Flimmern der HD-Texturen
				dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Clock = () -> 0L;
				ticks = 0;
				phase = 45;
				wait = 4;
				return true;
			case 45:
				camera(mc, 160F + ticks * 0.5F, 10F);
				if (ticks > 0) actions.shot(String.format("trsclient-crownorbit-%02d", ticks - 1));
				if (++ticks <= 20) return true;
				dev.theredstonee.trsclient.core.online.OnlineFeatures.v2Clock = null;
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				Mc.setHudHidden(false);
				return false;
			case 4:
				camera(mc, 180F, 0F);
				phase = 5;
				wait = 8;
				return true;
			case 5:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-front");
				camera(mc, 142F, 14F);
				phase = 6;
				wait = 4;
				return true;
			case 6:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-oblique");
				camera(mc, 0F, 8F);
				phase = 7;
				wait = 4;
				return true;
			case 7:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-back");
				if (!night) {
					night = true;
					actions.command("time set midnight");
					camera(mc, 180F, 0F);
					phase = 5;
					wait = 12;
					return true;
				}
				actions.command("time set day");
				item++;
				phase = item < IDS.length ? 2 : 9;
				wait = 6;
				return true;
			case 9:
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				Mc.setHudHidden(false);
				phase = 10;
				wait = 5;
				return true;
			case 10:
				// Garderobe, Reiter „Kosmetik“
				Mc.setScreen(WardrobeScreen.create(null));
				ui = WardrobeScreen.last;
				ui.category("cosmetics");
				ui.selectCosmetic("redstone_crown");
				tries = 0;
				phase = 11;
				wait = 10;
				return true;
			case 11: {
				CosmeticCatalog.State s = catalog.state();
				boolean ready = s.previews.containsKey("redstone_crown") && s.cards.size() >= IDS.length;
				if (!ready && tries++ < 300) return true;
				TrsClient.LOGGER.info("[Autotest] Garderobe Kosmetik: Vorschau {}, {} Karten", s.previews.containsKey("redstone_crown"),
						s.cards.size());
				phase = 12;
				wait = 20;
				return true;
			}
			case 12:
				actions.shot("trsclient-cos2-wardrobe-day");
				ui.cosmeticNight(true);
				phase = 13;
				wait = 10;
				return true;
			case 13:
				actions.shot("trsclient-cos2-wardrobe-night");
				ui.cosmeticNight(false);
				ui.selectCosmetic("halo");
				tries = 0;
				phase = 14;
				wait = 5;
				return true;
			case 14:
				if (!catalog.state().previews.containsKey("halo") && tries++ < 200) return true;
				phase = 15;
				wait = 15;
				return true;
			case 15:
				actions.shot("trsclient-cos2-wardrobe-locked");
				ui.selectCosmetic("top_hat");
				tries = 0;
				phase = 16;
				wait = 5;
				return true;
			case 16:
				if (!catalog.state().previews.containsKey("top_hat") && tries++ < 200) return true;
				phase = 17;
				wait = 15;
				return true;
			case 17:
				actions.shot("trsclient-cos2-wardrobe-tophat");
				// Format 1 (Quietscheente) in der Garderobe und im Spiel – darf durch v2 nicht kaputtgehen
				ui.selectCosmetic("rubber_duck");
				tries = 0;
				phase = 18;
				wait = 5;
				return true;
			case 18:
				if (!catalog.state().previews.containsKey("rubber_duck") && tries++ < 200) return true;
				phase = 19;
				wait = 15;
				return true;
			case 19:
				actions.shot("trsclient-cos2-wardrobe-duck");
				Mc.setScreen(null);
				catalog.wear("rubber_duck");
				tries = 0;
				phase = 20;
				wait = 5;
				return true;
			case 20: {
				Object tex = OnlineHooks.features() == null ? null : OnlineHooks.features().hatTexture(mc.player.getUUID());
				if (tex == null && tries++ < 400) return true;
				TrsClient.LOGGER.info("[Autotest] Kosmetik rubber_duck (v1): {} nach {} Ticks", tex != null ? "geladen" : "FEHLT", tries);
				Mc.setHudHidden(true);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				phase = 21;
				wait = 4;
				return true;
			}
			case 21:
				camera(mc, 150F, 10F);
				phase = 22;
				wait = 10;
				return true;
			case 22:
				actions.shot("trsclient-cos2-duck-oblique");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				Mc.setHudHidden(false);
				catalog.wear("redstone_crown");
				phase = 23;
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	/** Nummer des Leucht-Bildes aus dem Textur-Namen ({@code …/g<n>}), −1 = keins. */
	private static long glowIndex(Object tex) {
		if (tex == null) return -1;
		String s = tex.toString();
		int i = s.lastIndexOf("/g");
		try {
			return i < 0 ? -1 : Long.parseLong(s.substring(i + 2));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Protokoll auswerten: Bildwechsel je Sekunde, Dauer je Leucht-Bild, Abweichungen von der Wanduhr-Formel. */
	private void report(long frames) {
		java.util.List<long[]> all;
		synchronized (trace) {
			all = new java.util.ArrayList<long[]>(trace);
		}
		// Durchgänge je gezeichnetem Bild (Bildzähler PerfHooks.frames): fehlt die Krone in manchen Bildern oder wird sie
		// mehrfach gezeichnet?
		java.util.Map<Long, int[]> perFrame = new java.util.TreeMap<Long, int[]>();
		java.util.Set<Long> times = new java.util.HashSet<Long>();
		java.util.List<long[]> t = new java.util.ArrayList<long[]>();
		for (long[] e : all) {
			int[] c = perFrame.get(e[4]);
			if (c == null) perFrame.put(e[4], c = new int[5]);
			c[(int) e[3]]++;
			times.add(e[1]);
			if (e[3] == dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer.PASS_GLOW) t.add(e);
		}
		int noGlow = 0, multi = 0;
		StringBuilder counts = new StringBuilder();
		for (java.util.Map.Entry<Long, int[]> e : perFrame.entrySet()) {
			int[] c = e.getValue();
			if (c[3] == 0) noGlow++;
			if (c[3] > 1) multi++;
			if (counts.length() < 300) counts.append(java.util.Arrays.toString(c)).append(' ');
		}
		TrsClient.LOGGER.info("[Autotest] Kronen-Tempo: {} Bilder gezeichnet, Krone in {} davon ({} verschiedene Uhrzeiten),"
				+ " {} ohne Leuchten, {} mit mehrfachem Leuchten; Durchgänge je Bild [Grund, Emissiv, Durchsch., Leuchten, Höfe]: {}",
				frames, perFrame.size(), times.size(), noGlow, multi, counts);
		if (t.isEmpty()) {
			TrsClient.LOGGER.info("[Autotest] Kronen-Tempo: KEINE Leucht-Bilder gezeichnet");
			return;
		}
		V2Hat<Object> hat = OnlineHooks.features().hatV2(Minecraft.getInstance().player.getUUID(), false);
		dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2 m = hat.model;
		double seconds = (t.get(t.size() - 1)[0] - t.get(0)[0]) / 1e9;
		int changes = 0;
		int wrong = 0;
		long runStart = t.get(0)[1];
		StringBuilder runs = new StringBuilder();
		StringBuilder seq = new StringBuilder();
		for (int i = 0; i < t.size(); i++) {
			long[] e = t.get(i);
			int expect = dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer.frameAt(e[1], m.glowFrames, m.glowFrameTimeMs);
			if (expect != e[2]) wrong++;
			if (i > 0 && e[2] != t.get(i - 1)[2]) {
				changes++;
				if (runs.length() < 400) runs.append(e[1] - runStart).append("ms ");
				runStart = e[1];
				seq.append(e[2]).append(' ');
			}
		}
		TrsClient.LOGGER.info("[Autotest] Kronen-Tempo: {} Leucht-Bilder in {} s ({} Bilder/s gezeichnet), {} Wechsel = {} /s"
						+ " (Werkbank: {} /s = {} Bilder à {} ms), {} weichen von der Formel ab",
				t.size(), String.format("%.2f", seconds), String.format("%.1f", t.size() / seconds), changes,
				String.format("%.2f", changes / seconds), String.format("%.2f", 1000.0 / m.glowFrameTimeMs), m.glowFrames,
				m.glowFrameTimeMs, wrong);
		TrsClient.LOGGER.info("[Autotest] Kronen-Tempo: Dauer je Leucht-Bild: {}", runs);
		TrsClient.LOGGER.info("[Autotest] Kronen-Tempo: Folge: {}", seq);
	}

	/** Figur schaut stur nach Süden (Yaw 0), Kopf gerade – die Kamera kreist per Freelook. */
	private static void face(Minecraft mc) {
		//? if >=1.17 {
		mc.player.setYRot(0);
		mc.player.setXRot(0);
		//?} else {
		/*mc.player.yRot = 0;
		mc.player.xRot = 0;
		*///?}
		mc.player.yHeadRot = 0;
		mc.player.yBodyRot = 0;
	}

	/** Kamera: Freelook-Winkel relativ zur Figur ({@code 180} = von vorne, {@code 0} = von hinten), 3. Person. */
	private static void camera(Minecraft mc, float yawOffset, float pitch) {
		TrsClient.get().pvp().freelook().start(yawOffset, pitch);
		//? if >=1.17 {
		mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		//?}
	}
}
