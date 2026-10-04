package dev.theredstonee.trsclient.core.connect;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Nachschlagen wie Vanilla: SRV über JNDI-DNS (gleiche Einstellungen, eine Wiederholung, 1 s Startzeit), A/AAAA über
 * Javas Resolver (also das Betriebssystem). Austauschbar für Tests ({@link DnsLookup}).
 */
public final class SystemResolver implements DnsLookup {
	public static final SystemResolver INSTANCE = new SystemResolver();

	private SystemResolver() {
	}

	@Override
	public DnsLookup.SrvAnswer srv(String host) {
		try {
			Hashtable<String, String> env = new Hashtable<String, String>();
			env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
			env.put("java.naming.provider.url", "dns:");
			env.put("com.sun.jndi.dns.timeout.initial", "1000");
			env.put("com.sun.jndi.dns.timeout.retries", "1");
			DirContext ctx = new InitialDirContext(env);
			try {
				Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
				Attribute a = attrs.get("srv");
				if (a == null) return DnsLookup.SrvAnswer.NONE;
				List<String> lines = new ArrayList<String>();
				NamingEnumeration<?> all = a.getAll();
				while (all.hasMore() && lines.size() < 32) lines.add(String.valueOf(all.next()));
				return DnsLookup.SrvAnswer.of(SrvRecord.pick(lines));
			} finally {
				ctx.close();
			}
		} catch (NameNotFoundException e) {
			return DnsLookup.SrvAnswer.NONE;
		} catch (NamingException | RuntimeException | LinkageError e) {
			return DnsLookup.SrvAnswer.ERROR;
		}
	}

	@Override
	public List<byte[]> addresses(String host) {
		List<byte[]> out = new ArrayList<byte[]>();
		try {
			for (InetAddress a : InetAddress.getAllByName(host)) out.add(a.getAddress());
		} catch (UnknownHostException | RuntimeException e) {
			// unbekannt
		}
		return out;
	}
}
