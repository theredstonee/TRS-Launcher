package dev.theredstonee.trsclient.core.minigames;

/** Rechnet eine Partie aus Startwert und Eingaben nach, wie {@code replay} in {@code app/utils/minigames/replay.ts}. */
public final class Replay {
	public static final String STAPLER = "stapler";
	public static final String LOREN = "loren";
	/** Obergrenze für die Eingabeliste: Eine Stunde mit rund fünf Tastenwechseln je Sekunde, je Wechsel zwei Zahlen. */
	public static final int MAX_INPUTS = 36_000;

	/** Ergebnis des Nachrechnens. */
	public static final class Result {
		public final long score;
		public final int ticks;
		public final long hash;
		public final boolean finished;
		public final int[] inputs;

		Result(long score, int ticks, long hash, boolean finished, int[] inputs) {
			this.score = score;
			this.ticks = ticks;
			this.hash = hash;
			this.finished = finished;
			this.inputs = inputs;
		}
	}

	private Replay() {
	}

	/** Eine Eingabeliste ist gültig, wenn sie abwechselnd aus Takt-Abstand und Tastenmaske besteht. */
	public static boolean validInputs(String game, int[] inputs) {
		if (inputs.length % 2 != 0 || inputs.length > MAX_INPUTS) return false;
		int maxMask = STAPLER.equals(game) ? Stapler.MAX_MASK : Loren.MAX_MASK;
		for (int i = 0; i < inputs.length; i += 2) {
			int mask = inputs[i + 1];
			if (inputs[i] < 0 || mask < 0 || mask > maxMask) return false;
		}
		return true;
	}

	public static Result replay(String game, long seed, int[] inputs) {
		if (STAPLER.equals(game)) {
			Stapler run = new Stapler(seed);
			Inputs in = new Inputs(inputs);
			while (!run.finished()) run.step(in.next());
			return new Result(run.score, run.tick, run.hash(), run.finished(), run.inputs.toArray());
		}
		Loren run = new Loren(seed);
		Inputs in = new Inputs(inputs);
		while (!run.finished()) run.step(in.next());
		return new Result(run.score, run.tick, run.hash(), run.finished(), run.inputs.toArray());
	}

	/** Liefert die Tastenmaske je Takt. Nach der letzten Eingabe bleibt sie stehen. */
	private static final class Inputs {
		private final int[] list;
		private int index;
		private int mask;
		private long wait;

		Inputs(int[] list) {
			this.list = list;
			this.wait = list.length > 0 ? list[0] : Long.MAX_VALUE;
		}

		int next() {
			while (index < list.length && wait == 0) {
				mask = list[index + 1];
				index += 2;
				wait = index < list.length ? list[index] : Long.MAX_VALUE;
			}
			wait--;
			return mask;
		}
	}
}
