package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object JsonCanonical {
    fun encode(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                quote(key) + ":" + encode(value.get(key))
            }
        }
        value is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(value.get(it))
        }
        value is String -> quote(value)
        value is Boolean -> value.toString()
        value is Number -> number(value)
        value is Map<*, *> -> {
            val keys = value.keys.map { it as? String ?: error("JSON object keys must be strings") }.sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                quote(key) + ":" + encode(value[key])
            }
        }
        value is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    private fun number(value: Number): String {
        val double = value.toDouble()
        require(double.isFinite()) { "JSON numbers must be finite" }
        return when (value) {
            is Byte, is Short, is Int, is Long -> value.toString()
            else -> value.toString()
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
