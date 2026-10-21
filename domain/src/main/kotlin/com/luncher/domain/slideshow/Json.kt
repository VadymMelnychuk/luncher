package com.luncher.domain.slideshow

/**
 * A JSON text read into Kotlin values: an object as a `Map<String, Any?>`, an array as a
 * `List<Any?>`, a string, a number as a `Double`, `true`, `false` or null. Just what the sources'
 * listings need, as this module has no library to read them. Throws [IllegalArgumentException] on
 * a text that isn't JSON; a listing that isn't is no listing.
 */
internal fun readJson(text: String): Any? = JsonReader(text).run {
    val value = value()
    spaces()
    require(at == text.length) { "text after the value at $at" }
    value
}

/** [key]'s value in this object, if it's a string; null otherwise. */
internal fun Any?.string(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

/** [key]'s value in this object, if it's a number, as an Int; null otherwise. */
internal fun Any?.int(key: String): Int? = ((this as? Map<*, *>)?.get(key) as? Double)?.toInt()

/** [key]'s value in this object, if it's an array; empty otherwise. */
internal fun Any?.array(key: String): List<Any?> = ((this as? Map<*, *>)?.get(key) as? List<*>).orEmpty()

private class JsonReader(private val text: String) {

    var at = 0

    fun value(): Any? {
        spaces()
        require(at < text.length) { "no value at the end" }
        return when (val c = text[at]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("'$c' at $at")
        }
    }

    private fun obj(): Map<String, Any?> {
        at++
        val map = LinkedHashMap<String, Any?>()
        spaces()
        if (peek() == '}') return map.also { at++ }
        while (true) {
            spaces()
            require(peek() == '"') { "no key at $at" }
            val key = string()
            spaces()
            expect(':')
            map[key] = value()
            spaces()
            when (next()) {
                ',' -> continue
                '}' -> return map
                else -> throw IllegalArgumentException("no , or } at ${at - 1}")
            }
        }
    }

    private fun array(): List<Any?> {
        at++
        val list = ArrayList<Any?>()
        spaces()
        if (peek() == ']') return list.also { at++ }
        while (true) {
            list += value()
            spaces()
            when (next()) {
                ',' -> continue
                ']' -> return list
                else -> throw IllegalArgumentException("no , or ] at ${at - 1}")
            }
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return out.toString()
                '\\' -> when (val escaped = next()) {
                    '"', '\\', '/' -> out.append(escaped)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length) { "short \\u at $at" }
                        out.append(text.substring(at, at + 4).toInt(16).toChar())
                        at += 4
                    }
                    else -> throw IllegalArgumentException("\\$escaped at $at")
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Double {
        val start = at
        while (at < text.length && text[at] in NUMBER) at++
        return text.substring(start, at).toDoubleOrNull() ?: throw IllegalArgumentException("not a number at $start")
    }

    private fun word(word: String, value: Any?): Any? {
        require(text.startsWith(word, at)) { "not $word at $at" }
        at += word.length
        return value
    }

    fun spaces() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char? = text.getOrNull(at)

    private fun next(): Char {
        require(at < text.length) { "the text ends early" }
        return text[at++]
    }

    private fun expect(c: Char) = require(next() == c) { "no $c at ${at - 1}" }

    private companion object {
        const val NUMBER = "+-0123456789.eE"
    }
}
