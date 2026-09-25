package dev.theredstonee.trsclient.core.wardrobe;

import dev.theredstonee.trsclient.core.account.AccountManager;
import dev.theredstonee.trsclient.core.account.SessionData;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.skin.LocalSkin;

import java.nio.file.Path;

/**
 * {@link WardrobeService.Platform} aus dem, was jede Minecraft-Version schon hat: Sitzung über den
 * {@link AccountManager} (alle Bäume), TRS-Token und eigener Skin über {@link OnlineFeatures} (wo vorhanden – in
 * 1.7.10/1.13.2 fehlt es, dann bleibt die Garderobe lokal).
 */
public final class WardrobeContext implements WardrobeService.Platform {
	private final Path configDir;
	private final String userAgent;
	private final OnlineFeatures<?> features;

	public WardrobeContext(Path configDir, String userAgent, OnlineFeatures<?> features) {
		this.configDir = configDir;
		this.userAgent = userAgent == null ? "TRS-Client" : userAgent;
		this.features = features;
	}

	/** Gemeinsamer Dienst für diese Umgebung (legt ihn beim ersten Mal an). */
	public static WardrobeService service(Path configDir, String userAgent, OnlineFeatures<?> features) {
		return WardrobeService.shared(new WardrobeContext(configDir, userAgent, features));
	}

	@Override
	public SessionData session() {
		AccountManager m = AccountManager.get();
		return m == null ? null : m.currentSession();
	}

	private TrsOnline online() {
		return features == null ? null : features.online();
	}

	@Override
	public String trsToken() {
		TrsOnline o = online();
		return o == null ? null : o.token();
	}

	@Override
	public String apiBase() {
		TrsOnline o = online();
		return o == null ? OnlineConfig.DEFAULT_API : o.apiBase();
	}

	@Override
	public void trsTokenRejected(String token) {
		TrsOnline o = online();
		if (o != null) o.tokenRejected(token);
	}

	@Override
	public void lookChanged(byte[] skinPng, boolean slim, byte[] capePng, boolean capeChanged) {
		LocalSkin skin = features == null ? null : features.localSkin();
		if (skin != null) skin.update(skinPng, slim, capePng, capeChanged);
	}

	@Override
	public void trsCapeChanged() {
		TrsOnline o = online();
		if (o != null) o.refreshSelf();
	}

	@Override
	public Path configDir() {
		return configDir;
	}

	@Override
	public String userAgent() {
		return userAgent;
	}

	@Override
	public void log(String message) {
		System.out.println("[TRS Client] " + message);
	}
}
