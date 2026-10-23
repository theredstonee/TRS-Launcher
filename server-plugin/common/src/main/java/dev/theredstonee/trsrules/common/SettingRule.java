package dev.theredstonee.trsrules.common;

/**
 * Rule for one module setting: either a forced value ({@code {"value":x}}) or number limits ({@code {"min":a,"max":b}}).
 * Immutable.
 */
public final class SettingRule {
	/** Forced value: Boolean, Double or String (choice enum name); {@code null} for a limit rule. */
	private final Object value;
	private final Double min;
	private final Double max;

	private SettingRule(Object value, Double min, Double max) {
		this.value = value;
		this.min = min;
		this.max = max;
	}

	/** Forces a value. Numbers are stored as Double. */
	public static SettingRule value(Object value) {
		if (value instanceof Number) {
			double d = ((Number) value).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("value must be a finite number");
			return new SettingRule(Double.valueOf(d), null, null);
		}
		if (value instanceof Boolean || value instanceof String) return new SettingRule(value, null, null);
		throw new IllegalArgumentException("value must be true/false, a number or a text (choice name)");
	}

	/** Clamps a number setting; either bound may be {@code null} (open). */
	public static SettingRule range(Double min, Double max) {
		if (min == null && max == null) throw new IllegalArgumentException("min or max required");
		if (min != null && (min.isNaN() || min.isInfinite())) throw new IllegalArgumentException("min must be a finite number");
		if (max != null && (max.isNaN() || max.isInfinite())) throw new IllegalArgumentException("max must be a finite number");
		if (min != null && max != null && min.doubleValue() > max.doubleValue()) {
			throw new IllegalArgumentException("min (" + min + ") is greater than max (" + max + ")");
		}
		return new SettingRule(null, min, max);
	}

	public boolean isValue() {
		return value != null;
	}

	public Object value() {
		return value;
	}

	public Double min() {
		return min;
	}

	public Double max() {
		return max;
	}

	/** JSON object as in the protocol, e.g. {@code {"value":true}} or {@code {"min":2.0,"max":4.0}}. */
	public void writeJson(StringBuilder out) {
		out.append('{');
		if (value != null) {
			out.append("\"value\":");
			Json.scalar(out, value);
		} else {
			boolean first = true;
			if (min != null) {
				out.append("\"min\":");
				Json.number(out, min.doubleValue());
				first = false;
			}
			if (max != null) {
				if (!first) out.append(',');
				out.append("\"max\":");
				Json.number(out, max.doubleValue());
			}
		}
		out.append('}');
	}

	/** Short human-readable form for {@code /trsrules status}. */
	@Override
	public String toString() {
		if (value != null) return "= " + value;
		if (min != null && max != null) return min + ".." + max;
		if (min != null) return ">= " + min;
		return "<= " + max;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SettingRule)) return false;
		SettingRule r = (SettingRule) o;
		return eq(value, r.value) && eq(min, r.min) && eq(max, r.max);
	}

	@Override
	public int hashCode() {
		int h = value == null ? 0 : value.hashCode();
		h = h * 31 + (min == null ? 0 : min.hashCode());
		return h * 31 + (max == null ? 0 : max.hashCode());
	}

	private static boolean eq(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}
}
