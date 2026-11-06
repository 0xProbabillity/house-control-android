package dev.housecontrol.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A backup of what this phone holds: the floor plan, which plan is shown and the app
 * shortcuts, as one JSON document.
 *
 * It carries no credentials. The Home Assistant token, the Hue bridge key and the Smart Life
 * session stay in the encrypted store, and the key that pairs this phone with a Mac is never
 * written down at all — a backup can therefore be shared as it is.
 *
 * The format has room for them: a later version may add a "connections" field, encrypted with
 * a password the person sets.
 */
object Backup {
    const val FORMAT = "house-control-backup"
    const val VERSION = 1

    /** What a backup holds. */
    data class Contents(
        /** The floor plan, if the backup carries one. */
        val plan: JSONObject?,
        /** Which plan was shown: "custom", or the name of a bundled demo. */
        val selectedPlan: String,
        /** The ids of the app shortcuts, in the order the person put them in. */
        val shortcuts: List<String>,
    )

    /** The plan, the plan shown and the shortcut ids, as one document. */
    fun encode(plan: JSONObject?, selectedPlan: String, shortcuts: List<String>): ByteArray {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", Instant.now().toString())
        root.put("selectedPlan", selectedPlan)
        if (plan != null) root.put("plan", plan)
        root.put("shortcuts", JSONArray(shortcuts))
        // The same tidy form House Control writes its plans in: indented, keys sorted.
        return PlanStore.encode(root)
    }

    /** Whether `data` is a backup at all, rather than a floor plan. */
    fun isBackup(data: ByteArray): Boolean {
        val root = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return false
        return root.optString("format") == FORMAT
    }

    /** The backup's parts, or a failure saying why the file cannot be used. */
    fun decode(data: ByteArray): Contents {
        val root = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: throw ServiceFailure("This file is not a House Control backup.")
        if (root.optString("format") != FORMAT) throw ServiceFailure("This file is not a House Control backup.")
        val version = root.optInt("version", 0)
        if (version > VERSION) {
            throw ServiceFailure("This backup was made by a newer version of House Control (backup $version).")
        }
        val shortcuts = ArrayList<String>()
        root.optJSONArray("shortcuts")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let(shortcuts::add)
        }
        return Contents(
            plan = root.optJSONObject("plan"),
            selectedPlan = root.optString("selectedPlan").ifEmpty { "custom" },
            shortcuts = shortcuts,
        )
    }
}
