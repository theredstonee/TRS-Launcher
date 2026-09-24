package dev.theredstonee.trsclient.core.redstone;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;

import java.util.ArrayList;
import java.util.List;

/**
 * Redstone-Werkzeuge (versionsunabhängig): Signalstärke des angeschauten Blocks, Takt-Messer und
 * der Staub-Cache fürs Welt-Overlay. Der Loader ruft einmal je Client-Tick {@link #tick} mit seinem
 * {@link RedstoneWorld}-Adapter auf und zeichnet mit {@link RedstonePanels}/{@link SignalOverlay}.
 * Alles liest nur Blockzustände, die der Client ohnehin kennt.
 */
public final class RedstoneTools {
	/** Takt-Messer: weiter weg als das (Blöcke) → Messung beenden. */
	private static final double KEEP_DISTANCE = 32;

	private final TrsModules modules;
	private final BlockProbe probe = new BlockProbe();
	private final BlockProbe lastProbe = new BlockProbe();
	private final BlockProbe meterProbe = new BlockProbe();
	private final RedstoneReadout readout = new RedstoneReadout();
	private final FrequencyMeter meter = new FrequencyMeter();
	private final SignalCache cache = new SignalCache();
	private final ContainerMemory containers = new ContainerMemory();
	private final List<String> clockLines = new ArrayList<String>();

	private long tick;
	private Object lastWorld;
	private boolean looking;
	private int lookX, lookY, lookZ;
	private int lastX = Integer.MIN_VALUE, lastY, lastZ;
	private int lastGeneration = -1;
	private int lastOutput = Integer.MIN_VALUE;
	private int lastContainer = Integer.MIN_VALUE;
	private boolean lastContainerCurrent;
	private boolean describedValid;

	private boolean metering;
	private int meterX, meterY, meterZ;
	private String meterName = "";
	private boolean clockVisible;

	public RedstoneTools(TrsModules modules) {
		this.modules = modules;
	}

	/**
	 * Ein Client-Tick.
	 *
	 * @param world    Adapter der aktuellen Welt oder null (keine Welt)
	 * @param identity die Welt selbst – ein Wechsel verwirft alles Gemerkte
	 * @param hit      der Spieler schaut auf einen Block (hx, hy, hz)
	 */
	public void tick(RedstoneWorld world, Object identity, boolean hit, int hx, int hy, int hz,
			double eyeX, double eyeY, double eyeZ) {
		tick++;
		if (world == null || identity == null) {
			reset();
			return;
		}
		if (identity != lastWorld) {
			reset();
			lastWorld = identity;
		}
		boolean signalOn = modules.redstoneSignal.isEnabled();
		boolean clockOn = modules.redstoneClock.isEnabled();
		boolean overlayOn = modules.redstoneOverlay.isEnabled();

		// 1. Angeschauter Block
		looking = false;
		if ((signalOn || clockOn) && hit) {
			probe.clear();
			if (world.probe(hx, hy, hz, probe) && probe.kind != RedstoneKind.NONE) {
				looking = true;
				lookX = hx;
				lookY = hy;
				lookZ = hz;
			}
		}
		if (signalOn && looking) updateReadout(world);
		else readout.valid = false;

		// 2. Takt-Messer
		if (clockOn) updateMeter(world, eyeX, eyeY, eyeZ);
		else stopMeter();

		// 3. Staub-Cache fürs Overlay
		if (overlayOn) {
			cache.tick(world, floor(eyeX), floor(eyeY), floor(eyeZ), modules.redstoneOverlayRadius.getInt(),
					eyeX, eyeY, eyeZ, modules.redstoneOverlayVisibleOnly.get());
		} else if (cache.size() > 0) {
			cache.clear();
		}
	}

	private void updateReadout(RedstoneWorld world) {
		int output = probe.kind == RedstoneKind.COMPARATOR
				? ComparatorMath.output(world, lookX, lookY, lookZ, probe, containers) : -1;
		if (probe.kind == RedstoneKind.COMPARATOR && output < 0 && !(probe.hasPowered && probe.powered)) output = 0;
		int container = probe.container ? containers.get(lookX, lookY, lookZ) : -1;
		boolean current = probe.container && containers.current(lookX, lookY, lookZ, tick);
		boolean samePos = lookX == lastX && lookY == lastY && lookZ == lastZ;
		if (samePos && probe.sameAs(lastProbe) && output == lastOutput && container == lastContainer
				&& current == lastContainerCurrent && I18n.generation() == lastGeneration && readout.kind != RedstoneKind.NONE) {
			readout.valid = describedValid; // unverändert – nur wieder einblenden
			return;
		}
		String name = samePos && readout.kind == probe.kind ? readout.name : world.name(lookX, lookY, lookZ);
		readout.describe(name, probe, output, container, current);
		describedValid = readout.valid;
		lastProbe.copyFrom(probe);
		lastX = lookX;
		lastY = lookY;
		lastZ = lookZ;
		lastOutput = output;
		lastContainer = container;
		lastContainerCurrent = current;
		lastGeneration = I18n.generation();
	}

	private void updateMeter(RedstoneWorld world, double eyeX, double eyeY, double eyeZ) {
		if (looking && (!metering || lookX != meterX || lookY != meterY || lookZ != meterZ)) {
			meter.reset();
			metering = true;
			meterX = lookX;
			meterY = lookY;
			meterZ = lookZ;
			meterName = world.name(lookX, lookY, lookZ);
		}
		if (!metering) {
			clockVisible = false;
			return;
		}
		boolean keep = modules.redstoneClockKeep.get();
		double dx = meterX + 0.5 - eyeX, dy = meterY + 0.5 - eyeY, dz = meterZ + 0.5 - eyeZ;
		boolean lookingAtMeter = looking && lookX == meterX && lookY == meterY && lookZ == meterZ;
		if ((!keep && !lookingAtMeter) || dx * dx + dy * dy + dz * dz > KEEP_DISTANCE * KEEP_DISTANCE) {
			stopMeter();
			return;
		}
		meterProbe.clear();
		if (!world.probe(meterX, meterY, meterZ, meterProbe) || meterProbe.kind == RedstoneKind.NONE) {
			stopMeter();
			return;
		}
		int output = meterProbe.kind == RedstoneKind.COMPARATOR
				? ComparatorMath.output(world, meterX, meterY, meterZ, meterProbe, containers) : -1;
		meter.sample(tick, RedstoneReadout.activity(meterProbe, output));
		buildClock();
	}

	private void buildClock() {
		int window = windowTicks();
		double period = snap(meter.periodTicks(tick, window));
		int rises = meter.risesWithin(tick, window);
		// Nur zeigen, wenn das Bauteil schaltet – ein ruhiger Block braucht keinen Takt-Messer.
		clockVisible = rises > 0;
		double pulse = clockVisible && period > 0 ? snap(meter.pulseTicks()) : 0;
		int state = !clockVisible ? 0 : period > 0 ? 1 : (meter.samples() < window && rises < 2 ? 2 : 3);
		// Texte nur neu bauen, wenn sich etwas geändert hat (sonst je Tick String.format).
		int gen = I18n.generation();
		if (state == clockState && period == clockPeriod && pulse == clockPulse && gen == clockGeneration) return;
		clockState = state;
		clockPeriod = period;
		clockPulse = pulse;
		clockGeneration = gen;
		clockLines.clear();
		if (!clockVisible) return;
		if (period > 0) {
			clockLines.add(I18n.tr("hud.redstone.hz", String.format(I18n.locale(), "%.2f", 20.0 / period)));
			clockLines.add(I18n.tr("hud.redstone.period", ticks(period / 2.0), ticks(period)));
			if (pulse > 0) clockLines.add(I18n.tr("hud.redstone.pulse", ticks(pulse / 2.0)));
		} else if (meter.samples() < window && rises < 2) {
			clockLines.add(I18n.tr("hud.redstone.measuring"));
		} else {
			clockLines.add(I18n.tr("hud.redstone.noClock"));
		}
	}

	/**
	 * Echte Takte haben ganze Spiel-Ticks; kleine Abweichungen kommen nur vom Eintreffen der
	 * Pakete beim Client (5,9 → 6).
	 */
	static double snap(double ticks) {
		double whole = Math.rint(ticks);
		return Math.abs(ticks - whole) < 0.25 ? whole : ticks;
	}

	/** "4" oder "1.5" (halbe Redstone-Ticks bei ungeraden Spiel-Ticks). */
	static String ticks(double value) {
		double rounded = Math.round(value * 10) / 10.0;
		if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) return Long.toString(Math.round(rounded));
		return String.format(I18n.locale(), "%.1f", rounded);
	}

	private int windowTicks() {
		return (int) Math.round(modules.redstoneClockWindow.get() * FrequencyMeter.TICKS_PER_SECOND);
	}

	private void stopMeter() {
		if (metering) meter.reset();
		metering = false;
		clockVisible = false;
		clockLines.clear();
		clockState = -1;
	}

	/** Stand der zuletzt gebauten Takt-Texte. */
	private int clockState = -1;
	private double clockPeriod = Double.NaN;
	private double clockPulse = Double.NaN;
	private int clockGeneration = -1;

	/**
	 * Inhalt eines gerade geöffneten Behälters (x, y, z): Anzahl und Stapelgröße je Platz.
	 * Aus dem Loader, solange der Behälter-Bildschirm offen ist.
	 */
	public void rememberContainer(int x, int y, int z, int[] counts, int[] maxStack, int slots) {
		containers.put(x, y, z, ComparatorMath.containerSignal(counts, maxStack, slots), tick);
	}

	/** Weltwechsel: alles vergessen. */
	public void reset() {
		readout.valid = false;
		readout.kind = RedstoneKind.NONE;
		lastX = Integer.MIN_VALUE;
		looking = false;
		stopMeter();
		cache.clear();
		containers.clear();
		lastWorld = null;
	}

	// --- für die Anzeige ---

	public RedstoneReadout readout() {
		return readout;
	}

	/** Takt-Anzeige sichtbar (Bauteil angeschaut oder es schaltet)? */
	public boolean clockVisible() {
		return clockVisible && metering;
	}

	public String clockName() {
		return meterName;
	}

	/** Zeilen der Takt-Anzeige (Hz, Periode, Pulslänge bzw. "kein Takt"). */
	public List<String> clockLines() {
		return clockLines;
	}

	public FrequencyMeter meter() {
		return meter;
	}

	public SignalCache cache() {
		return cache;
	}

	public long tickCount() {
		return tick;
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}
