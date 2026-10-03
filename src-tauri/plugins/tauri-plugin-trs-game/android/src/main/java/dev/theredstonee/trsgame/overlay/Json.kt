package dev.theredstonee.trsgame.overlay

/**
 * Kleiner JSON-Leser/-Schreiber für die Layout-Dateien. Bewusst ohne
 * org.json (fehlt in JVM-Tests) und ohne Fremdbibliothek. Ergebnis:
 * Map, List, String, Double, Boolean oder null.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    /** Begrenzung gegen absurd tiefe/große Eingaben. */
    private const val MAX_DEPTH = 32
    const val MAX_CHARS = 64 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_CHARS) throw ParseException("too large")
        val p = Parser(text)
        p.ws()
        val value = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("trailing data")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("too deep")
            if (i >= s.length) throw ParseException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("bad literal")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw ParseException("bad number")
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw ParseException("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("bad escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseException("bad escape"))
                                i += 4
                            }
                            else -> throw ParseException("bad escape")
                        }
                    }
                    c < ' ' -> throw ParseException("control char")
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                ws()
                out.add(value(depth + 1))
                ws()
                if (i >= s.length) throw ParseException("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw ParseException("expected , or ]")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseException("expected key")
                val key = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw ParseException("expected :")
                ws()
                out[key] = value(depth + 1)
                ws()
                if (i >= s.length) throw ParseException("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw ParseException("expected , or }")
                }
            }
        }
    }

    /** Schreibt Map/List/String/Zahl/Boolean/null als JSON (eingerückt). */
    fun write(value: Any?, indent: String = ""): String {
        val sb = StringBuilder()
        write(sb, value, indent)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value)
            is Number -> {
                val d = value.toDouble()
                if (!d.isFinite()) sb.append("0")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                val inner = "$indent  "
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(inner)
                    quote(sb, k.toString())
                    sb.append(": ")
                    write(sb, v, inner)
                }
                sb.append('\n').append(indent).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                val inner = "$indent  "
                sb.append("[\n")
                value.forEachIndexed { idx, v ->
                    if (idx > 0) sb.append(",\n")
                    sb.append(inner)
                    write(sb, v, inner)
                }
                sb.append('\n').append(indent).append(']')
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
