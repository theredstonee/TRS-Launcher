package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.creator.CreatorTier;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.FanInfo;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.online.FanHooks;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Selbsttest Creator-Programm ({@code -PtrsAutotestOnly=creator}, braucht {@code scripts/mock-creator-api.mjs} über
 * {@code -PtrsApi}): Die Attrappe macht den eigenen Spieler zum Creator (Stern) und Fan mit animiertem Symbol und
 * Gold-Rahmen; ein Testspieler (feste UUID) wird als Figur vor die Kamera gestellt – Stern „top“, Fuchs-Symbol mit
 * Diamant-Rahmen. Geprüft: Lookup, Symbol-Texturen, erweiterte Namen und dass die Zeichen-Hooks wirklich zeichnen
 * (Namensschild über dem Kopf und Tabliste). Screenshots {@code creator-nametag}, {@code creator-tab}.
 */
public final class CreatorTest {
	/** Gleiche UUID wie {@code FAN_UUID} in der Attrappe. */
	static final UUID FAN = Uuids.toUuid("c7ea7e00f00d4000a000000000000001");

	private int phase;
	private int wait;
	private int tries;
	private net.minecraft.world.entity.player.Player fake;
	private boolean ok = true;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				modules.trsOnline.setEnabled(true);
				modules.badgeTab.set(true);
				modules.badgeNametag.set(true);
				modules.fanSymbolTab.set(true);
				modules.fanSymbolNametag.set(true);
				modules.minimap.setEnabled(false);
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("execute as @p at @s run fill ~-4 ~ ~-2 ~4 ~4 ~8 air");
				actions.command("execute as @p at @s run fill ~-4 ~-1 ~-2 ~4 ~-1 ~8 grass_block");
				actions.command("execute as @p at @s run tp @s ~ ~ ~ 0 -12");
				// Tabliste im Einzelspieler nur mit Anzeige-Ziel sichtbar.
				actions.command("scoreboard objectives add trstest dummy");
				actions.command("scoreboard objectives setdisplay list trstest");
				wait = 20;
				return true;
			case 1:
				spawnFake(mc);
				wait = 5;
				return true;
			case 2: {
				// Auf Anmeldung, Lookup und beide Symbol-Texturen warten (höchstens 25 s).
				TextureRef own = OnlineHooks.features().fanSymbol(mc.player.getUUID(), true);
				TextureRef other = OnlineHooks.features().fanSymbol(FAN, false);
				if ((own == null || other == null) && tries++ < 500) {
					phase = 2;
					return true;
				}
				FanInfo ownFan = OnlineHooks.features().online().info(mc.player.getUUID()).fan;
				CreatorTier ownTier = OnlineHooks.features().creatorStar(mc.player.getUUID(), true);
				CreatorTier fakeTier = OnlineHooks.features().creatorStar(FAN, false);
				check("eigenes Fan-Symbol geladen", own != null);
				check("Fan-Symbol des Testspielers geladen", other != null);
				check("eigener Creator-Stern partner", ownTier == CreatorTier.PARTNER);
				check("Stern des Testspielers top", fakeTier == CreatorTier.TOP);
				check("Meilenstein gold", ownFan != null && ownFan.milestone == dev.theredstonee.trsclient.core.creator.Milestone.GOLD);
				check("Rahmen eingezeichnet (16 + 2 px)", own != null && own.width == 18);
				check("Diamant-Rahmen (32 + 4 px)", other != null && other.width == 36);
				net.minecraft.network.chat.Component plain = OnlineHooks.text("Spieler");
				net.minecraft.network.chat.Component decorated = FanHooks.decorate(mc.player.getUUID(), plain, true);
				check("Name erweitert", decorated != plain && decorated.getString().contains(FanHooks.STAR)
						&& decorated.getString().contains(FanHooks.SLOT));
				check("gleiche Komponente bei gleichem Zustand", FanHooks.decorate(mc.player.getUUID(), plain, true) == decorated);
				TrsClient.LOGGER.info("[Autotest] Creator: Status {}, eigenes Symbol {}, Testspieler-Symbol {}, Name \"{}\"",
						OnlineHooks.features().online().status(), own, other, decorated.getString());
				FanHooks.worldDraws = 0;
				FanHooks.guiDraws = 0;
				wait = 30;
				return true;
			}
			case 3:
				actions.shot("trsclient-creator-nametag");
				check("Symbol über dem Kopf gezeichnet", FanHooks.worldDraws > 0);
				TrsClient.LOGGER.info("[Autotest] Creator: Namensschild-Symbol {}× gezeichnet", FanHooks.worldDraws);
				hold(mc.options.keyPlayerList, true);
				wait = 6;
				return true;
			case 4:
				actions.shot("trsclient-creator-tab");
				check("Symbol in der Tabliste gezeichnet", FanHooks.guiDraws > 0);
				TrsClient.LOGGER.info("[Autotest] Creator: Tabliste-Symbol {}× gezeichnet", FanHooks.guiDraws);
				hold(mc.options.keyPlayerList, false);
				// Animation: nach 150 ms ein anderes Bild des eigenen Symbols.
				TextureRef a = OnlineHooks.features().fanSymbol(mc.player.getUUID(), true);
				wait = 4;
				firstFrame = a == null ? null : a.id;
				return true;
			case 5: {
				TextureRef b = OnlineHooks.features().fanSymbol(mc.player.getUUID(), true);
				check("Symbol animiert", b != null && firstFrame != null && !firstFrame.equals(b.id));
				// Abschalten: Name ohne Zusätze bleibt nur mit TRS-Abzeichen.
				modules.fanSymbolTab.set(false);
				net.minecraft.network.chat.Component plain = OnlineHooks.text("Spieler");
				net.minecraft.network.chat.Component d = FanHooks.decorate(mc.player.getUUID(), plain, true);
				check("Einstellung aus → kein Symbol/Stern", !d.getString().contains(FanHooks.SLOT) && !d.getString().contains(FanHooks.STAR));
				modules.fanSymbolTab.set(true);
				removeFake(mc);
				actions.command("scoreboard objectives remove trstest");
				TrsClient.LOGGER.info("[Autotest] Creator: {}", ok ? "ALLES OK" : "FEHLER (siehe oben)");
				KeyMapping.releaseAll();
				return false;
			}
			default:
				return false;
		}
	}

	private Object firstFrame;

	private void check(String what, boolean pass) {
		if (!pass) ok = false;
		TrsClient.LOGGER.info("[Autotest] Creator {}: {}", pass ? "OK" : "FEHLER", what);
	}

	/** Testspieler als Figur 3 Blöcke vor der Kamera (nur auf dem Client, Blick zur Kamera). */
	private void spawnFake(Minecraft mc) {
		//? if >=1.21 {
		try {
			com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(FAN, "Fuchsfan");
			net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level, profile);
			double x = Mc.x(mc.player);
			double y = Mc.y(mc.player);
			double z = Mc.z(mc.player) + 3.5;
			//? if >=1.21.5 {
			/*p.snapTo(x, y, z, 180f, 0f);
			*///?} else
			p.moveTo(x, y, z, 180f, 0f);
			p.setYHeadRot(180f);
			p.setYBodyRot(180f);
			mc.level.addEntity(p);
			fake = p;
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("[Autotest] Creator: Testspieler nicht erzeugt", e);
		}
		//?} else
		/*TrsClient.LOGGER.info("[Autotest] Creator: Testspieler erst ab 1.21");*/
	}

	private void removeFake(Minecraft mc) {
		//? if >=1.21 {
		if (fake != null && mc.level != null) mc.level.removeEntity(fake.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		//?}
		fake = null;
	}

	private static void hold(KeyMapping key, boolean down) {
		//? if >=1.15 {
		key.setDown(down);
		//?} else
		/*((dev.theredstonee.trsclient.mixin.KeyMappingAccessor) key).trsclient$setDown(down);*/
	}
}
