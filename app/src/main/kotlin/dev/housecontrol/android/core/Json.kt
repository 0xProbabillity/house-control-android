package dev.housecontrol.android.core

import org.json.JSONArray
import org.json.JSONObject

// The bridge, the plan and every platform speak JSON. These helpers keep org.json's
// loose typing (an Int where a Double was meant, a String where a number was) in one place.

/** A JSON object from pairs; a Kotlin null becomes JSON null. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((key, value) in pairs) put(key, value.toJson())
}

/** A JSON array from values; a Kotlin null becomes JSON null. */
fun jsonArrayOf(vararg values: Any?): JSONArray = JSONArray().apply { values.forEach { put(it.toJson()) } }

/** Kotlin collections (and null) as the org.json values they stand for. */
fun Any?.toJson(): Any = when (this) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray -> this
    is Map<*, *> -> JSONObject().also { o -> for ((k, v) in this) o.put(k.toString(), v.toJson()) }
    is Collection<*> -> JSONArray().also { a -> forEach { a.put(it.toJson()) } }
    is Array<*> -> JSONArray().also { a -> forEach { a.put(it.toJson()) } }
    else -> this
}

/** A number, or a numeric string, as a Double; null for anything else (Swift's `.double`). */
fun Any?.asDouble(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

fun JSONObject.double(key: String): Double? = if (isNull(key)) null else opt(key).asDouble()

/** The value at `key` when it is a non-empty string. */
fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }

fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean

fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key).objects()

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

/** A deep copy. */
fun JSONObject.copy(): JSONObject = JSONObject(toString())

/** `other`'s keys over this object's, as Swift's `merging(_:) { $1 }`. Returns this object. */
fun JSONObject.mergeFrom(other: JSONObject?): JSONObject {
    if (other != null) for (key in other.keyList()) put(key, other.get(key))
    return this
}

/** Two JSON values with the same content (numbers compared by value). */
fun jsonEquals(a: Any?, b: Any?): Boolean {
    val x = if (a == null) JSONObject.NULL else a
    val y = if (b == null) JSONObject.NULL else b
    return when {
        x is Number && y is Number -> x.toDouble() == y.toDouble()
        x is JSONObject && y is JSONObject ->
            x.length() == y.length() && x.keyList().all { y.has(it) && jsonEquals(x.get(it), y.get(it)) }
        x is JSONArray && y is JSONArray ->
            x.length() == y.length() && (0 until x.length()).all { jsonEquals(x.get(it), y.get(it)) }
        else -> x == y
    }
}

/** JSON text without escaped slashes, as Apple's `.withoutEscapingSlashes` writes it. */
fun Any.toJsonText(): String = toString().replace("\\/", "/")
