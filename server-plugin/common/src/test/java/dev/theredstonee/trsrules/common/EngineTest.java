package dev.theredstonee.trsrules.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EngineTest {
	static final byte[] HELLO = "{\"v\":1,\"client\":\"trsclient\",\"version\":\"0.20.0\",\"mc\":\"1.21.11\",\"loader\":\"fabric\",\"modules\":[\"fps\"]}"
			.getBytes(Protocol.UTF8);
	static final String EMPTY = "{\"v\":1,\"modules\":{}}";

	static final class FakePlayer {
		final UUID id = UUID.randomUUID();
		final String name;
		String world = "world";
		boolean canReceive = true;
		int op = -1;
		final Set<String> perms = new HashSet<String>();
		final List<String> received = new ArrayList<String>();
		final List<String> chat = new ArrayList<String>();
		String kicked;

		FakePlayer(String name) {
			this.name = name;
		}

		String last() {
			return received.isEmpty() ? null : received.get(received.size() - 1);
		}
	}

	/** Bridge with a manual clock and a list of scheduled tasks. */
	static final class FakeBridge implements RulesEngine.Bridge<FakePlayer> {
		long now = 1000;
		final List<long[]> dueAt = new ArrayList<long[]>();
		final List<Runnable> tasks = new ArrayList<Runnable>();
		final List<String> log = new ArrayList<String>();

		@Override public UUID id(FakePlayer p) { return p.id; }
		@Override public String name(FakePlayer p) { return p.name; }
		@Override public boolean hasPermission(FakePlayer p, String perm) { return p.perms.contains(perm); }
		@Override public String scope(FakePlayer p) { return p.world; }
		@Override public int opLevel(FakePlayer p) { return p.op; }

		@Override
		public boolean sendRules(FakePlayer p, byte[] payload) {
			if (!p.canReceive) return false;
			p.received.add(new String(payload, Protocol.UTF8));
			return true;
		}

		@Override public void sendMessage(FakePlayer p, String text) { p.chat.add(text); }
		@Override public void kick(FakePlayer p, String text) { p.kicked = text; }

		@Override
		public void runLater(FakePlayer p, long delayMillis, Runnable task) {
			dueAt.add(new long[] {now + delayMillis});
			tasks.add(task);
		}

		@Override public void run(FakePlayer p, Runnable task) { task.run(); }
		@Override public void info(String m) { log.add("INFO " + m); }
		@Override public void warn(String m) { log.add("WARN " + m); }

		/** Advances the clock and runs due tasks. */
		void advance(long millis) {
			now += millis;
			for (int i = 0; i < tasks.size(); i++) {
				if (dueAt.get(i)[0] <= now && tasks.get(i) != null) {
					Runnable r = tasks.get(i);
					tasks.set(i, null);
					r.run();
				}
			}
		}
	}

	static RulesConfig cfg(String yaml, ScopeKind kind) {
		ConfigLoader.Result r = ConfigLoader.parse(yaml, kind);
		assertTrue(r.ok(), () -> r.error);
		return r.config;
	}

	static RulesEngine<FakePlayer> engine(FakeBridge b, String yaml) {
		return new RulesEngine<FakePlayer>(b, cfg(yaml, ScopeKind.WORLDS), () -> b.now);
	}

	@Test
	void helloIsAlwaysAnsweredEvenWithoutRules() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "");
		FakePlayer p = new FakePlayer("Steve");
		e.onJoin(p);
		assertTrue(p.received.isEmpty(), "nothing before hello");
		assertTrue(e.onHello(p, HELLO, Protocol.HELLO));
		assertEquals(EMPTY, p.last());
		assertTrue(e.hasTrsClient(p));
		assertEquals("fabric", e.hello(p).loader());
	}

	@Test
	void invalidHelloIsIgnored() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {zoom: false}");
		FakePlayer p = new FakePlayer("Alex");
		e.onJoin(p);
		assertFalse(e.onHello(p, "{\"client\":\"other\"}".getBytes(Protocol.UTF8), Protocol.HELLO));
		assertTrue(p.received.isEmpty());
		assertFalse(e.hasTrsClient(p));
	}

	@Test
	void worldChangeResendsWorldRules() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}\nworlds:\n  arena:\n    modules: {zoom: false}\n");
		FakePlayer p = new FakePlayer("Steve");
		e.onJoin(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false}}}", p.last());
		p.world = "arena";
		e.onScopeChange(p);
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false},\"zoom\":{\"enabled\":false}}}", p.last());
		assertEquals(2, p.received.size());
	}

	@Test
	void worldChangeWithoutHelloSendsNothing() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}");
		FakePlayer p = new FakePlayer("Vanilla");
		e.onJoin(p);
		e.onScopeChange(p);
		assertTrue(p.received.isEmpty());
	}

	@Test
	void bypassPlayersGetEmptyRules() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}");
		FakePlayer p = new FakePlayer("Admin");
		p.perms.add("trsrules.bypass");
		e.onJoin(p);
		e.onHello(p, HELLO, Protocol.HELLO_LEGACY);
		assertEquals(EMPTY, p.last());
		assertTrue(e.isBypassed(p));
	}

	@Test
	void customBypassPermission() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}\nbypass-permission: my.bypass\n");
		FakePlayer p = new FakePlayer("Admin");
		p.perms.add("trsrules.bypass"); // default permission no longer counts
		e.onJoin(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false}}}", p.last());
		p.perms.add("my.bypass");
		e.sendRules(p);
		assertEquals(EMPTY, p.last());
	}

	@Test
	void bypassByPlayerListNameOrUuid() {
		FakeBridge b = new FakeBridge();
		FakePlayer byName = new FakePlayer("Notch");
		FakePlayer byId = new FakePlayer("Jeb_");
		FakePlayer other = new FakePlayer("Steve");
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}\nbypass-players: [notch, " + byId.id + "]\n");
		for (FakePlayer p : new FakePlayer[] {byName, byId, other}) {
			e.onJoin(p);
			e.onHello(p, HELLO, Protocol.HELLO);
		}
		assertEquals(EMPTY, byName.last());
		assertEquals(EMPTY, byId.last());
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false}}}", other.last());
		assertTrue(String.valueOf(e.status(byName)).contains("bypass-players"));
	}

	@Test
	void bypassByOpLevel() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {minimap: false}\nbypass-op-level: 3\n");
		FakePlayer op4 = new FakePlayer("Owner");
		op4.op = 4;
		FakePlayer op2 = new FakePlayer("Helper");
		op2.op = 2;
		FakePlayer unknown = new FakePlayer("PluginPlatform"); // opLevel -1: platform does not know OP levels
		assertTrue(e.isBypassed(op4));
		assertFalse(e.isBypassed(op2));
		assertFalse(e.isBypassed(unknown));
		RulesEngine<FakePlayer> off = engine(b, "modules: {minimap: false}\n");
		assertFalse(off.isBypassed(op4), "bypass-op-level defaults to 0 = off");
	}

	@Test
	void rulesWaitUntilChannelIsRegistered() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "modules: {zoom: false}");
		FakePlayer p = new FakePlayer("Steve");
		p.canReceive = false;
		e.onJoin(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		assertTrue(p.received.isEmpty());
		assertTrue(String.valueOf(e.status(p)).contains("waiting"));
		p.canReceive = true;
		e.onChannelRegistered(p);
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":false}}}", p.last());
		e.onChannelRegistered(p); // not pending any more → no duplicate
		assertEquals(1, p.received.size());
	}

	@Test
	void reloadResendsToTrsPlayersOnly() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "");
		FakePlayer trs = new FakePlayer("Trs");
		FakePlayer vanilla = new FakePlayer("Vanilla");
		e.onJoin(trs);
		e.onJoin(vanilla);
		e.onHello(trs, HELLO, Protocol.HELLO);
		e.reload(cfg("modules: {fullbright: false}", ScopeKind.WORLDS));
		assertEquals("{\"v\":1,\"modules\":{\"fullbright\":{\"enabled\":false}}}", trs.last());
		assertTrue(vanilla.received.isEmpty());
		assertEquals(2, e.counts()[0]);
		assertEquals(1, e.counts()[1]);
	}

	// ------------------------------------------------------------------ missing client / timeout

	@Test
	void timeoutKicksPlayersWithoutHello() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: kick, timeout-seconds: 10, kick-message: '&cNeed TRS'}");
		FakePlayer p = new FakePlayer("Vanilla");
		e.onJoin(p);
		b.advance(9_999);
		assertNull(p.kicked, "not before the timeout");
		b.advance(1);
		assertEquals("§cNeed TRS", p.kicked);
	}

	@Test
	void helloBeforeTimeoutPreventsAction() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: kick, timeout-seconds: 5}");
		FakePlayer p = new FakePlayer("Trs");
		e.onJoin(p);
		b.advance(4_000);
		e.onHello(p, HELLO, Protocol.HELLO);
		b.advance(10_000);
		assertNull(p.kicked);
	}

	@Test
	void notifyIsSentOnce() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: notify, timeout-seconds: 3, notify-message: 'Get &fTRS'}");
		FakePlayer p = new FakePlayer("Vanilla");
		e.onJoin(p);
		b.advance(3_000);
		assertEquals(1, p.chat.size());
		assertEquals("Get §fTRS", p.chat.get(0));
		assertNull(p.kicked);
		assertTrue(String.valueOf(e.status(p)).contains("no hello within 3 s"));
	}

	@Test
	void ignoreSchedulesNothing() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: ignore}");
		FakePlayer p = new FakePlayer("Vanilla");
		e.onJoin(p);
		assertTrue(b.tasks.isEmpty());
	}

	@Test
	void bypassAndLeftPlayersAreNotKicked() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: kick, timeout-seconds: 2}");
		FakePlayer admin = new FakePlayer("Admin");
		admin.perms.add("trsrules.bypass");
		FakePlayer gone = new FakePlayer("Gone");
		e.onJoin(admin);
		e.onJoin(gone);
		e.onQuit(gone);
		b.advance(2_000);
		assertNull(admin.kicked);
		assertNull(gone.kicked);
	}

	@Test
	void rejoinUsesFreshTimer() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "missing-client: {action: kick, timeout-seconds: 10}");
		FakePlayer p = new FakePlayer("Steve");
		e.onJoin(p);
		b.advance(8_000);
		e.onQuit(p);
		e.onJoin(p); // rejoined – the old timer must not kick after 2 more seconds
		b.advance(2_000);
		assertNull(p.kicked);
		b.advance(8_000);
		assertTrue(p.kicked != null);
	}

	// ------------------------------------------------------------------ proxy

	@Test
	void emptyProxyMessageKeepsBackendMessage() {
		// the default config has message: "" – it must not erase the message of a backend plugin
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = new RulesEngine<FakePlayer>(b,
				cfg("message: \"\"\nservers:\n  rules:\n    modules: {freelook: false}\n  quiet:\n    message: \"\"\n",
						ScopeKind.SERVERS), () -> b.now);
		FakePlayer p = new FakePlayer("Steve");
		p.world = "rules";
		e.onServerSwitch(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		e.onUpstreamRules(p, "rules", "{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false}},\"message\":\"Backend\"}"
				.getBytes(Protocol.UTF8));
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false},\"freelook\":{\"enabled\":false}},\"message\":\"Backend\"}",
				p.last());
		// an explicit "" in a server section still silences the backend message there
		p.world = "quiet";
		e.onServerSwitch(p);
		e.onUpstreamRules(p, "quiet", "{\"v\":1,\"modules\":{},\"message\":\"Backend\"}".getBytes(Protocol.UTF8));
		assertEquals("{\"v\":1,\"modules\":{}}", p.last());
	}

	@Test
	void proxyMergesBackendRulesWithProxyPrecedence() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = new RulesEngine<FakePlayer>(b,
				cfg("modules: {zoom: true}\nservers:\n  pvp:\n    modules: {minimap: false}\n", ScopeKind.SERVERS), () -> b.now);
		FakePlayer p = new FakePlayer("Steve");
		p.world = "lobby"; // = backend server name on proxies
		e.onServerSwitch(p); // first connect → session
		e.onHello(p, HELLO, Protocol.HELLO);
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":true}}}", p.last());

		// backend plugin sends its own rules → merged, proxy wins on zoom
		e.onUpstreamRules(p, "lobby", "{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":false},\"fps\":{\"enabled\":false}},\"message\":\"hi\"}"
				.getBytes(Protocol.UTF8));
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":true},\"fps\":{\"enabled\":false}},\"message\":\"hi\"}", p.last());

		// switch to another backend: old backend rules are dropped, proxy rules of the new server apply
		p.world = "pvp";
		e.onServerSwitch(p);
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":true},\"minimap\":{\"enabled\":false}}}", p.last());
		assertEquals(Protocol.HELLO, e.helloChannel(p));
		// backend rules that arrive before the switch event (race on connect) are kept for their server
		e.onUpstreamRules(p, "pvp", "{\"v\":1,\"modules\":{\"fps\":{\"enabled\":false}}}".getBytes(Protocol.UTF8));
		e.onServerSwitch(p);
		assertEquals("{\"v\":1,\"modules\":{\"fps\":{\"enabled\":false},\"zoom\":{\"enabled\":true},\"minimap\":{\"enabled\":false}}}", p.last());
		assertEquals(new String(HELLO, Protocol.UTF8), new String(e.helloPayload(p), Protocol.UTF8));
	}

	@Test
	void proxyBypassDropsBackendRulesToo() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = new RulesEngine<FakePlayer>(b, cfg("", ScopeKind.SERVERS), () -> b.now);
		FakePlayer p = new FakePlayer("Admin");
		p.perms.add("trsrules.bypass");
		e.onServerSwitch(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		e.onUpstreamRules(p, null, "{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":false}}}".getBytes(Protocol.UTF8));
		assertEquals(EMPTY, p.last());
	}

	@Test
	void statusDescribesPlayer() {
		FakeBridge b = new FakeBridge();
		RulesEngine<FakePlayer> e = engine(b, "worlds:\n  world:\n    modules: {zoom: false}\n");
		FakePlayer p = new FakePlayer("Steve");
		e.onJoin(p);
		e.onHello(p, HELLO, Protocol.HELLO);
		String s = String.valueOf(e.status(p));
		assertTrue(s.contains("TRS Client: yes (0.20.0, MC 1.21.11, fabric, 1 modules)"), s);
		assertTrue(s.contains("World: world (has its own section)"), s);
		assertTrue(s.contains("Bypass: no"), s);
		assertTrue(s.contains("Delivery: sent"), s);
	}

	@Test
	void serverRulesCommand(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		FakeBridge b = new FakeBridge();
		java.nio.file.Path file = dir.resolve("config.yml");
		ServerRules<FakePlayer> rules = new ServerRules<FakePlayer>(b, file, ScopeKind.WORLDS);
		final FakePlayer p = new FakePlayer("Steve");
		rules.engine().onJoin(p);
		rules.engine().onHello(p, HELLO, Protocol.HELLO);
		assertEquals(EMPTY, p.last());
		final Map<String, FakePlayer> online = new HashMap<String, FakePlayer>();
		online.put("steve", p);
		ServerRules.PlayerLookup<FakePlayer> lookup = new ServerRules.PlayerLookup<FakePlayer>() {
			@Override public FakePlayer find(String name) { return online.get(name.toLowerCase()); }
			@Override public java.util.Collection<String> names() { return java.util.Collections.singleton(p.name); }
		};

		java.nio.file.Files.write(file, "modules: {zoom: false}\n".getBytes(Protocol.UTF8));
		String out = String.valueOf(rules.command(new String[] {"reload"}, lookup));
		assertTrue(out.contains("Reloaded"), out);
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":false}}}", p.last());

		// a broken file keeps the old rules
		java.nio.file.Files.write(file, "modules: [".getBytes(Protocol.UTF8));
		out = String.valueOf(rules.command(new String[] {"reload"}, lookup));
		assertTrue(out.contains("Reload failed"), out);
		assertEquals(Boolean.FALSE, rules.engine().config().global().moduleState("zoom"));

		assertTrue(String.valueOf(rules.command(new String[] {"status", "STEVE"}, lookup)).contains("TRS Client: yes"));
		assertTrue(String.valueOf(rules.command(new String[] {"status", "nobody"}, lookup)).contains("not online"));
		assertTrue(String.valueOf(rules.command(new String[] {"status"}, lookup)).contains("1§7 with the TRS Client"));
		assertTrue(String.valueOf(rules.command(new String[0], lookup)).contains("Usage"));
		assertEquals(java.util.Arrays.asList("reload"), rules.complete(new String[] {"re"}, lookup));
		assertEquals(java.util.Arrays.asList("Steve"), rules.complete(new String[] {"status", "st"}, lookup));
	}
}
