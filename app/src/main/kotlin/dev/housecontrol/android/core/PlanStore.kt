package dev.housecontrol.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persists which floor plan is active and the user's own plan file, in the app's private
 * storage: `plan.json` (the user's home) and `settings.json` (which plan is shown).
 */
class PlanStore(val directory: File) {
    val customPlanFile = File(directory, "plan.json")
    private val settingsFile = File(directory, "settings.json")

    /** "home" / "studio" for the bundled demos, or "custom" for plan.json. */
    var activePlan = "home"
        private set

    init {
        directory.mkdirs()
        runCatching { JSONObject(settingsFile.readText()).string("plan") }.getOrNull()?.let { activePlan = it }
        if (activePlan !in BUNDLED && !(activePlan == "custom" && customPlan() != null)) activePlan = "home"
    }

    fun customPlan(): JSONObject? = runCatching { JSONObject(customPlanFile.readText()) }.getOrNull()

    val hasCustomPlan get() = customPlanFile.exists()

    /** The name the user gave their home, for menus. */
    val customPlanName: String? get() = customPlan()?.string("name")

    /** Fingerprint of the user's plan as stored; two devices holding the same revision have nothing to exchange. */
    val customPlanRevision: String? get() = if (customPlanFile.exists()) revision(customPlanFile.readBytes()) else null

    fun select(plan: String) {
        if (plan !in BUNDLED && !(plan == "custom" && customPlan() != null)) return
        activePlan = plan
        writeAtomically(settingsFile, jsonOf("plan" to plan).toString().toByteArray())
    }

    /** Stores `plan` as the user's plan and makes it active. */
    fun saveCustom(plan: JSONObject) = saveCustom(encode(plan))

    /** Stores an already serialised plan byte for byte, so its revision matches the sender's. */
    fun saveCustom(data: ByteArray, select: Boolean = true) {
        writeAtomically(customPlanFile, data)
        if (select) select("custom")
    }

    /** Forgets the user's plan and falls back to the bundled demo. */
    fun removeCustom() {
        customPlanFile.delete()
        if (activePlan == "custom") select("home")
    }

    /** The plan the UI should render: the custom JSON inline, or the name of a bundled demo. */
    fun bootPayload(): JSONObject {
        val boot = jsonOf("plan" to activePlan, "hasCustomPlan" to hasCustomPlan)
        if (activePlan == "custom") customPlan()?.let { boot.put("config", it) }
        return boot
    }

    companion object {
        val BUNDLED = listOf("home", "studio")

        fun revision(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).take(8).joinToString("") { "%02x".format(it) }

        /** Checks that `data` is a floor plan at all before it replaces the user's one. */
        fun validate(data: ByteArray): JSONObject {
            val plan = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            if (plan?.optJSONArray("rooms") == null) {
                throw ServiceFailure("This file is not a House Control floor plan (no \"rooms\" list).")
            }
            return plan
        }

        /** The plan as House Control writes it: indented, keys sorted, slashes unescaped. */
        fun encode(plan: JSONObject): ByteArray = buildString { write(plan, 0) }.toByteArray(Charsets.UTF_8)

        private fun StringBuilder.write(value: Any?, depth: Int) {
            val pad = "  ".repeat(depth + 1)
            when (value) {
                null, JSONObject.NULL -> append("null")
                is JSONObject -> {
                    val keys = value.keyList().sorted()
                    if (keys.isEmpty()) { append("{\n\n").append("  ".repeat(depth)).append("}"); return }
                    append("{\n")
                    keys.forEachIndexed { i, key ->
                        append(pad).append(quote(key)).append(" : ")
                        write(value.get(key), depth + 1)
                        if (i < keys.size - 1) append(",")
                        append("\n")
                    }
                    append("  ".repeat(depth)).append("}")
                }
                is JSONArray -> {
                    if (value.length() == 0) { append("[\n\n").append("  ".repeat(depth)).append("]"); return }
                    append("[\n")
                    for (i in 0 until value.length()) {
                        append(pad)
                        write(value.get(i), depth + 1)
                        if (i < value.length() - 1) append(",")
                        append("\n")
                    }
                    append("  ".repeat(depth)).append("]")
                }
                is String -> append(quote(value))
                is Boolean -> append(value.toString())
                is Number -> append(JSONObject.numberToString(value))
                else -> append(quote(value.toString()))
            }
        }

        private fun quote(text: String) = JSONObject.quote(text).replace("\\/", "/")

        fun writeAtomically(file: File, data: ByteArray) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(data)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
