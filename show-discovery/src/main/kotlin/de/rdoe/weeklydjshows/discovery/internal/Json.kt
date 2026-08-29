package de.rdoe.weeklydjshows.discovery.internal

/** Small dependency-free JSON parser used to keep the module drop-in friendly. */
sealed class JsonValue {
    data class Obj(val values: Map<String, JsonValue>) : JsonValue()
    data class Arr(val values: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object Json {
    fun parse(text: String): JsonValue = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) error("Unexpected trailing data")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (index >= text.length) error("Unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected character '$c'")
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            skipWhitespace()
            val map = linkedMapOf<String, JsonValue>()
            if (peek('}')) {
                index++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) error("Expected object key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return JsonValue.Obj(map)
                    }
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            skipWhitespace()
            val list = mutableListOf<JsonValue>()
            if (peek(']')) {
                index++
                return JsonValue.Arr(list)
            }
            while (true) {
                list += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return JsonValue.Arr(list)
                    }
                    else -> error("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) error("Incomplete escape")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) error("Incomplete unicode escape")
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: error("Invalid unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid escape '$escaped'")
                        }
                    }
                    else -> out.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek('-')) index++
            consumeDigits()
            if (peek('.')) {
                index++
                consumeDigits()
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                consumeDigits()
            }
            val raw = text.substring(start, index)
            return JsonValue.Num(raw.toDoubleOrNull() ?: error("Invalid number '$raw'"))
        }

        private fun consumeDigits() {
            val start = index
            while (index < text.length && text[index].isDigit()) index++
            if (start == index) error("Expected digit")
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            if (!text.regionMatches(index, literal, 0, literal.length)) error("Expected $literal")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(c: Char) {
            if (index >= text.length || text[index] != c) error("Expected '$c'")
            index++
        }

        private fun peek(c: Char): Boolean = index < text.length && text[index] == c

        private fun error(message: String): Nothing =
            throw JsonParseException("$message at position $index")
    }
}

fun JsonValue.asObject(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.values
fun JsonValue.asArray(): List<JsonValue>? = (this as? JsonValue.Arr)?.values
fun JsonValue.asString(): String? = when (this) {
    is JsonValue.Str -> value
    is JsonValue.Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is JsonValue.Bool -> value.toString()
    else -> null
}
fun JsonValue.asDouble(): Double? = when (this) {
    is JsonValue.Num -> value
    is JsonValue.Str -> value.toDoubleOrNull()
    else -> null
}
fun JsonValue.asLong(): Long? = asDouble()?.toLong()
fun JsonValue.asBoolean(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    is JsonValue.Str -> when (value.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    is JsonValue.Num -> value != 0.0
    else -> null
}

fun Map<String, JsonValue>.string(key: String): String? = this[key]?.asString()?.takeIf { it.isNotBlank() }
fun Map<String, JsonValue>.double(key: String): Double? = this[key]?.asDouble()
fun Map<String, JsonValue>.long(key: String): Long? = this[key]?.asLong()
fun Map<String, JsonValue>.bool(key: String): Boolean? = this[key]?.asBoolean()
fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue>? = this[key]?.asObject()
fun Map<String, JsonValue>.array(key: String): List<JsonValue> = this[key]?.asArray().orEmpty()
fun Map<String, JsonValue>.stringList(key: String): List<String> = array(key).mapNotNull { it.asString() }
