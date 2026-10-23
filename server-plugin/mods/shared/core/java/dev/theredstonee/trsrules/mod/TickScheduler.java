package dev.theredstonee.trsrules.mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Delayed tasks for the server mods, executed on the server tick thread (vanilla servers have no scheduler API).
 * {@link #schedule} may be called from any thread; {@link #tick} runs on the server thread once per tick.
 */
public final class TickScheduler {
	private static final class Entry {
		final long dueAt;
		final Runnable task;

		Entry(long dueAt, Runnable task) {
			this.dueAt = dueAt;
			this.task = task;
		}
	}

	private final ConcurrentLinkedQueue<Entry> incoming = new ConcurrentLinkedQueue<Entry>();
	/** Only touched by the server thread. */
	private final List<Entry> waiting = new ArrayList<Entry>();

	/** Runs {@code task} on the server thread once {@code delayMillis} have passed (at the next tick at the earliest). */
	public void schedule(long delayMillis, Runnable task) {
		incoming.add(new Entry(System.currentTimeMillis() + Math.max(0L, delayMillis), task));
	}

	/** Server thread, every tick: runs all due tasks. Exceptions are passed to {@code onError}, the rest still runs. */
	public void tick(long now, ErrorHandler onError) {
		Entry e;
		while ((e = incoming.poll()) != null) waiting.add(e);
		if (waiting.isEmpty()) return;
		List<Runnable> due = null;
		for (Iterator<Entry> it = waiting.iterator(); it.hasNext();) {
			Entry next = it.next();
			if (next.dueAt <= now) {
				it.remove();
				if (due == null) due = new ArrayList<Runnable>();
				due.add(next.task);
			}
		}
		if (due == null) return;
		for (Runnable r : due) {
			try {
				r.run();
			} catch (RuntimeException ex) {
				onError.onError(ex);
			}
		}
	}

	/** Drops all pending tasks (server stopped). */
	public void clear() {
		incoming.clear();
		waiting.clear();
	}

	/** Receives exceptions thrown by tasks. */
	public interface ErrorHandler {
		void onError(RuntimeException e);
	}
}
