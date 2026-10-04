package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.DnsCache;
import dev.theredstonee.trsclient.core.connect.FastConnect;
import dev.theredstonee.trsclient.core.connect.IpLiteral;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.17 {
/*import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
*///?}

/**
 * Schnell verbinden – DNS-Speicher: Ist ein Server schon aufgelöst (vorab beim Auswählen, vom Launcher oder vom
 * letzten Mal), nimmt Minecraft SRV-Ziel und Adresse aus {@link FastConnect} statt erneut zu fragen. Ab 1.17 ist das
 * dieselbe Logik wie {@code ServerNameResolver#resolveAddress} (inklusive Mojangs Sperrliste), nur mit gemerkten
 * Teilen – und nur, wenn etwas gemerkt ist; sonst läuft Vanilla. Bei IP-Adressen entfällt die Rückwärts-Auflösung
 * (der Handshake trägt die IP wie eingegeben). Davor: das SRV-Nachschlagen in {@code ServerAddress}.
 */
//? if >=1.17 {
/*@Mixin(ServerNameResolver.class)
*///?} else
@Mixin(net.minecraft.client.multiplayer.ServerAddress.class)
public abstract class FastResolveMixin {
	//? if >=1.17 {
	/*@Shadow
	@Final
	private ServerAddressResolver resolver;
	@Shadow
	@Final
	private ServerRedirectHandler redirectHandler;
	@Shadow
	@Final
	private AddressCheck addressCheck;

	@Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cached(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
		try {
			if (!FastConnect.active()) return;
			String host = address.getHost();
			boolean literal = IpLiteral.is(host);
			DnsCache.Srv srv = literal || address.getPort() != 25565 ? null : FastConnect.cachedSrv(host);
			InetAddress direct = FastConnect.cachedAddress(host);
			if (!literal && srv == null && direct == null) return;
			Optional<ResolvedServerAddress> resolved = trsclient$lookup(address);
			if ((!resolved.isPresent() || addressCheck.isAllowed(resolved.get())) && addressCheck.isAllowed(address)) {
				Optional<ServerAddress> redirect;
				if (literal || address.getPort() != 25565) redirect = Optional.empty();
				else if (srv != null) redirect = srv.record == null ? Optional.<ServerAddress>empty()
						: Optional.of(new ServerAddress(srv.record.target, srv.record.port));
				else redirect = trsclient$learned(address, redirectHandler.lookupRedirect(address));
				if (redirect.isPresent()) resolved = trsclient$lookup(redirect.get()).filter(addressCheck::isAllowed);
				cir.setReturnValue(resolved);
			} else {
				cir.setReturnValue(Optional.empty());
			}
		} catch (RuntimeException e) {
			// Vanilla macht weiter
		}
	}

	// Ohne Treffer im Speicher: Vanilla hat aufgelöst – ein SRV-Umweg (anderer Name) wird gemerkt.
	@Inject(method = "resolveAddress", at = @At("RETURN"), require = 0)
	private void trsclient$learn(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
		try {
			Optional<ResolvedServerAddress> r = cir.getReturnValue();
			if (r == null || !r.isPresent() || address.getPort() != 25565) return;
			InetSocketAddress isa = r.get().asInetSocketAddress();
			String name = isa.getHostString();
			if (name != null && !DnsCache.key(name).equals(DnsCache.key(address.getHost()))) {
				FastConnect.learnSrv(address.getHost(), name, isa.getPort());
			}
		} catch (RuntimeException ignored) {
			// nur Lernen
		}
	}

	private Optional<ResolvedServerAddress> trsclient$lookup(ServerAddress a) {
		InetAddress cached = FastConnect.cachedAddress(a.getHost());
		if (cached != null) return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(cached, a.getPort())));
		return resolver.resolve(a);
	}

	private static Optional<ServerAddress> trsclient$learned(ServerAddress a, Optional<ServerAddress> r) {
		if (r.isPresent()) FastConnect.learnSrv(a.getHost(), r.get().getHost(), r.get().getPort());
		return r;
	}
	*///?} elif >=1.16 {
	@Inject(method = "lookupSrv", at = @At("HEAD"), cancellable = true, require = 0)
	private static void trsclient$cachedSrv(String host, CallbackInfoReturnable<com.mojang.datafixers.util.Pair<String, Integer>> cir) {
		try {
			if (!FastConnect.active()) return;
			if (IpLiteral.is(host)) {
				cir.setReturnValue(com.mojang.datafixers.util.Pair.of(host, 25565));
				return;
			}
			DnsCache.Srv s = FastConnect.cachedSrv(host);
			if (s == null) return;
			cir.setReturnValue(s.record == null ? com.mojang.datafixers.util.Pair.of(host, 25565)
					: com.mojang.datafixers.util.Pair.of(s.record.target, s.record.port));
		} catch (RuntimeException ignored) {
			// Vanilla fragt selbst
		}
	}

	@Inject(method = "lookupSrv", at = @At("RETURN"), require = 0)
	private static void trsclient$learnSrv(String host, CallbackInfoReturnable<com.mojang.datafixers.util.Pair<String, Integer>> cir) {
		com.mojang.datafixers.util.Pair<String, Integer> r = cir.getReturnValue();
		if (r != null && r.getFirst() != null && !r.getFirst().equals(host)) FastConnect.learnSrv(host, r.getFirst(), r.getSecond());
	}
	//?} else {
	/*@Inject(method = "lookupSrv", at = @At("HEAD"), cancellable = true, require = 0)
	private static void trsclient$cachedSrv(String host, CallbackInfoReturnable<String[]> cir) {
		try {
			if (!FastConnect.active()) return;
			if (IpLiteral.is(host)) {
				cir.setReturnValue(new String[]{host, "25565"});
				return;
			}
			DnsCache.Srv s = FastConnect.cachedSrv(host);
			if (s == null) return;
			cir.setReturnValue(s.record == null ? new String[]{host, "25565"} : new String[]{s.record.target, String.valueOf(s.record.port)});
		} catch (RuntimeException ignored) {
			// Vanilla fragt selbst
		}
	}

	@Inject(method = "lookupSrv", at = @At("RETURN"), require = 0)
	private static void trsclient$learnSrv(String host, CallbackInfoReturnable<String[]> cir) {
		String[] r = cir.getReturnValue();
		if (r == null || r.length < 2 || r[0] == null || r[0].equals(host)) return;
		try {
			FastConnect.learnSrv(host, r[0], Integer.parseInt(r[1].trim()));
		} catch (NumberFormatException ignored) {
			// kein Port
		}
	}
	*///?}
}
