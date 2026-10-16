package dev.theredstonee.trsclient.dev;

import com.mojang.authlib.GameProfile;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.creator.CreatorTier;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.online.LegacyFans;
import dev.theredstonee.trsclient.online.LegacyOnline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;

import java.util.UUID;

/**
 * Selbsttest Creator-Programm in Legacy-Forge ({@code -PtrsAutotestOnly=creator}, mit {@code scripts/mock-creator-api.mjs}
 * über {@code -PtrsApi}): wie der Fabric-Test – eigener Spieler Creator + Fan, Testspieler als Figur vor der Kamera.
 * Geprüft: Symbol-Texturen, Zusätze im Namen (Stern, Platzhalter) und dass Namensschild und Tabliste das Bild zeichnen.
 */
public final class CreatorTest {
	static final UUID FAN = Uuids.toUuid("c7ea7e00f00d4000a000000000000001");

	private int phase;
	private int wait;
	private int tries;
	private EntityOtherPlayerMP fake;
	private boolean ok = true;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayerSP player = Mc.player();
		if (player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0: {
				modules.trsOnline.setEnabled(true);
				modules.badgeTab.set(true);
				modules.badgeNametag.set(true);
				modules.fanSymbolTab.set(true);
				modules.fanSymbolNametag.set(true);
				modules.minimap.setEnabled(false);
				String name = player.getName();
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("tp " + name + " ~ ~ ~ 0 -12");
				actions.command("scoreboard objectives add trstest dummy");
				actions.command("scoreboard objectives setdisplay list trstest");
				wait = 30;
				return true;
			}
			case 1:
				spawnFake(player);
				wait = 5;
				return true;
			case 2: {
				TextureRef own = LegacyOnline.features().fanSymbol(player.getUniqueID(), true);
				TextureRef other = LegacyOnline.features().fanSymbol(FAN, false);
				if ((own == null || other == null) && tries++ < 500) {
					phase = 2;
					return true;
				}
				check("eigenes Fan-Symbol geladen", own != null);
				check("Fan-Symbol des Testspielers geladen", other != null);
				check("eigener Creator-Stern partner", LegacyOnline.features().creatorStar(player.getUniqueID(), true) == CreatorTier.PARTNER);
				check("Stern des Testspielers top", LegacyOnline.features().creatorStar(FAN, false) == CreatorTier.TOP);
				check("Rahmen eingezeichnet (16 + 2 px)", own != null && own.width == 18);
				String prefix = LegacyFans.prefix(player.getUniqueID(), true);
				check("Name erweitert (Stern + Platzhalter)", prefix.contains("\u2605") && prefix.contains(LegacyFans.MARK));
				TrsClient.LOGGER.info("[Autotest] Creator: Status {}, eigenes Symbol {}, Testspieler-Symbol {}, Präfix \"{}\"",
						LegacyOnline.features().online().status(), own, other, prefix);
				LegacyFans.worldDraws = 0;
				LegacyFans.tabDraws = 0;
				wait = 40;
				return true;
			}
			case 3:
				actions.shot("creator-nametag");
				check("Symbol über dem Kopf gezeichnet", LegacyFans.worldDraws > 0);
				TrsClient.LOGGER.info("[Autotest] Creator: Namensschild-Symbol {}× gezeichnet, Name \"{}\"", LegacyFans.worldDraws,
						fake == null ? "-" : fake.getDisplayName().getFormattedText());
				KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), true);
				wait = 6;
				return true;
			case 4:
				actions.shot("creator-tab");
				check("Symbol in der Tabliste gezeichnet", LegacyFans.tabDraws > 0);
				TrsClient.LOGGER.info("[Autotest] Creator: Tabliste-Symbol {}× gezeichnet", LegacyFans.tabDraws);
				KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
				if (fake != null && Mc.world() != null) Mc.world().removeEntityFromWorld(fake.getEntityId());
				fake = null;
				actions.command("scoreboard objectives remove trstest");
				TrsClient.LOGGER.info("[Autotest] Creator: {}", ok ? "ALLES OK" : "FEHLER (siehe oben)");
				KeyBinding.unPressAllKeys();
				return false;
			default:
				return false;
		}
	}

	private void check(String what, boolean pass) {
		if (!pass) ok = false;
		TrsClient.LOGGER.info("[Autotest] Creator {}: {}", pass ? "OK" : "FEHLER", what);
	}

	/** Testspieler als Figur 3,5 Blöcke vor der Kamera (nur auf dem Client). */
	private void spawnFake(EntityPlayerSP player) {
		try {
			EntityOtherPlayerMP p = new EntityOtherPlayerMP(Mc.world(), new GameProfile(FAN, "Fuchsfan"));
			p.setLocationAndAngles(player.posX, player.posY, player.posZ + 3.5, 180f, 0f);
			p.rotationYawHead = 180f;
			p.renderYawOffset = 180f;
			Mc.world().addEntityToWorld(-4242, p);
			fake = p;
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("[Autotest] Creator: Testspieler nicht erzeugt", e);
		}
	}
}
