package com.riga.voicewaze.data.local

import android.content.Context
import com.riga.voicewaze.domain.landmark.LandmarkDefaults
import com.riga.voicewaze.domain.landmark.LandmarkEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class LandmarkRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureSeeded()
    }

    fun getAll(): List<LandmarkEntry> {
        val raw = prefs.getString(KEY_ITEMS, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        LandmarkEntry(
                            id = item.optLong("id"),
                            spokenPhrase = item.optString("spokenPhrase"),
                            displayName = item.optString("displayName"),
                            address = item.optString("address"),
                            latitude = item.optDouble("latitude", 0.0),
                            longitude = item.optDouble("longitude", 0.0)
                        )
                    )
                }
            }.sortedBy { it.spokenPhrase.lowercase(Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(entry: LandmarkEntry) {
        val updated = getAll().toMutableList()
        val index = updated.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            updated[index] = entry
        } else {
            updated.add(entry)
        }
        writeAll(updated)
    }

    fun delete(id: Long) {
        writeAll(getAll().filterNot { it.id == id })
    }

    fun nextId(): Long {
        return (getAll().maxOfOrNull { it.id } ?: 0L) + 1L
    }

    fun hasDuplicateSpokenPhrase(spokenPhrase: String, excludeId: Long?): Boolean {
        val normalized = normalize(spokenPhrase)
        return getAll().any { entry ->
            entry.id != excludeId && normalize(entry.spokenPhrase) == normalized
        }
    }

    fun exportToJsonString(): String {
        return prefs.getString(KEY_ITEMS, null).orEmpty()
    }

    fun importFromJsonString(json: String): Boolean {
        val parsed = parseJson(json) ?: return false
        writeAll(parsed)
        return true
    }

    private fun parseJson(json: String): List<LandmarkEntry>? {
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        LandmarkEntry(
                            id = item.optLong("id"),
                            spokenPhrase = item.optString("spokenPhrase"),
                            displayName = item.optString("displayName"),
                            address = item.optString("address"),
                            latitude = item.optDouble("latitude", 0.0),
                            longitude = item.optDouble("longitude", 0.0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureSeeded() {
        if (prefs.contains(KEY_ITEMS)) return
        writeAll(LandmarkDefaults.entries)
    }

    private fun writeAll(items: List<LandmarkEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("spokenPhrase", entry.spokenPhrase)
                    .put("displayName", entry.displayName)
                    .put("address", entry.address)
                    .put("latitude", entry.latitude)
                    .put("longitude", entry.longitude)
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT).trim().replace("ё", "е")
    }

    companion object {
        private const val PREFS_NAME = "landmark_repository"
        private const val KEY_ITEMS = "items_json"
    }
}