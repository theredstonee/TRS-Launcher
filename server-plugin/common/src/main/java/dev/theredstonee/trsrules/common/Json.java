package dev.theredstonee.trsrules.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON support without external libraries (the platforms ship different Gson versions or none).
 *
 * <p>{@link #parse(String)} returns {@code Map<String,Object>} (insertion order), {@code List<Object>}, {@code String},
 * {@code Double}/{@code Long}, {@code Boolean} or {@code null}. Nesting depth is limited; payloads are small (≤ 32 KiB).</p>
 */
public final class Json {
	private static final int MAX_DEPTH = 32;

	private Json() {
	}

	// ------------------------------------------------------------------ writing

	/** Appends {@code s} as a JSON string literal (with quotes). */
	public static void quote(StringBuilder out, String s) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': out.append("\\\""); break;
				case '\\': out.append("\\\\"); break;
				case '\n': out.append("\\n"); break;
				case '\r': out.append("\\r"); break;
				case '\t': out.append("\\t"); break;
				case '\b': out.append("\\b"); break;
				case '\f': out.append("\\f"); break;
				default:
					if (c < 0x20 || c == 0x2028 || c == 0x2029) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
			}
		}
		out.append('"');
	}

	/** Appends a number; integral values keep a {@code .0} so they always read as decimals ({@code 4} → {@code 4.0}). */
	public static void number(StringBuilder out, double d) {
		if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("not a finite number: " + d);
		out.append(Double.toString(d));
	}

	/** Writes a scalar config value (Boolean, Number, String). */
	public static void scalar(StringBuilder out, Object value) {
		if (value instanceof Boolean) {
			out.append(((Boolean) value).booleanValue() ? "true" : "false");
		} else if (value instanceof Number) {
			number(out, ((Number) value).doubleValue());
		} else if (value == null) {
			out.append("null");
		} else {
			quote(out, String.valueOf(value));
		}
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Parses a JSON document.
	 *
	 * @throws IllegalArgumentException on malformed input
	 */
	public static Object parse(String text) {
		Parser p = new Parser(text);
		p.skipWs();
		Object v = p.value(0);
		p.skipWs();
		if (p.pos != text.length()) throw p.error("trailing data");
		return v;
	}

	/** Parses and requires a JSON object at the top level. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object v = parse(text);
		if (!(v instanceof Map)) throw new IllegalArgumentException("JSON object expected");
		return (Map<String, Object>) v;
	}

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		IllegalArgumentException error(String msg) {
			return new IllegalArgumentException("JSON: " + msg + " at " + pos);
		}

		void skipWs() {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
				else break;
			}
		}

		Object value(int depth) {
			if (depth > MAX_DEPTH) throw error("nesting too deep");
			if (pos >= s.length()) throw error("unexpected end");
			char c = s.charAt(pos);
			switch (c) {
				case '{': return object(depth);
				case '[': return array(depth);
				case '"': return string();
				case 't': return literal("true", Boolean.TRUE);
				case 'f': return literal("false", Boolean.FALSE);
				case 'n': return literal("null", null);
				default:
					if (c == '-' || (c >= '0' && c <= '9')) return number();
					throw error("unexpected character '" + c + "'");
			}
		}

		private Object literal(String word, Object v) {
			if (!s.startsWith(word, pos)) throw error("invalid literal");
			pos += word.length();
			return v;
		}

		private Map<String, Object> object(int depth) {
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			pos++; // {
			skipWs();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				if (peek() != '"') throw error("object key expected");
				String key = string();
				skipWs();
				if (peek() != ':') throw error("':' expected");
				pos++;
				skipWs();
				map.put(key, value(depth + 1));
				skipWs();
				char c = peek();
				pos++;
				if (c == ',') continue;
				if (c == '}') return map;
				pos--;
				throw error("',' or '}' expected");
			}
		}

		private List<Object> array(int depth) {
			List<Object> list = new ArrayList<Object>();
			pos++; // [
			skipWs();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				skipWs();
				list.add(value(depth + 1));
				skipWs();
				char c = peek();
				pos++;
				if (c == ',') continue;
				if (c == ']') return list;
				pos--;
				throw error("',' or ']' expected");
			}
		}

		private char peek() {
			if (pos >= s.length()) throw error("unexpected end");
			return s.charAt(pos);
		}

		private String string() {
			pos++; // "
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (pos >= s.length()) throw error("unterminated string");
				char c = s.charAt(pos++);
				if (c == '"') return sb.toString();
				if (c == '\\') {
					if (pos >= s.length()) throw error("unterminated escape");
					char e = s.charAt(pos++);
					switch (e) {
						case '"': sb.append('"'); break;
						case '\\': sb.append('\\'); break;
						case '/': sb.append('/'); break;
						case 'b': sb.append('\b'); break;
						case 'f': sb.append('\f'); break;
						case 'n': sb.append('\n'); break;
						case 'r': sb.append('\r'); break;
						case 't': sb.append('\t'); break;
						case 'u':
							if (pos + 4 > s.length()) throw error("bad unicode escape");
							try {
								sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
							} catch (NumberFormatException ex) {
								throw error("bad unicode escape");
							}
							pos += 4;
							break;
						default:
							throw error("bad escape");
					}
				} else if (c < 0x20) {
					throw error("control character in string");
				} else {
					sb.append(c);
				}
			}
		}

		private Object number() {
			int start = pos;
			if (s.charAt(pos) == '-') pos++;
			boolean decimal = false;
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c >= '0' && c <= '9') {
					pos++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					decimal = true;
					pos++;
				} else {
					break;
				}
			}
			String num = s.substring(start, pos);
			try {
				if (!decimal) {
					try {
						return Long.valueOf(num);
					} catch (NumberFormatException tooBig) {
						return Double.valueOf(num);
					}
				}
				return Double.valueOf(num);
			} catch (NumberFormatException ex) {
				throw error("bad number '" + num + "'");
			}
		}
	}
}
