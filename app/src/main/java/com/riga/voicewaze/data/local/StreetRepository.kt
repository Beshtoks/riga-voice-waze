package com.riga.voicewaze.data.local

import android.content.Context
import com.riga.voicewaze.data.model.StreetEntry
import org.json.JSONArray

class StreetRepository(
    private val context: Context
) {

    @Volatile
    private var cachedEntries: List<StreetEntry>? = null

    fun getAllStreetEntries(): List<StreetEntry> {
        val ready = cachedEntries
        if (ready != null) {
            return ready
        }

        synchronized(this) {
            val secondCheck = cachedEntries
            if (secondCheck != null) {
                return secondCheck
            }

            val parsed = loadAndMergeEntries()
            cachedEntries = parsed
            return parsed
        }
    }

    private fun loadAndMergeEntries(): List<StreetEntry> {
        val base = loadBaseEntries()
        if (base.isEmpty()) {
            return emptyList()
        }

        val overrides = loadAliasOverrides()

        if (overrides.isEmpty()) {
            return base
        }

        val merged = base.map { entry ->
            val key = buildKey(entry.official, entry.city)
            val extraAliases = overrides[key].orEmpty()

            if (extraAliases.isEmpty()) {
                entry
            } else {
                entry.copy(
                    aliases = (entry.aliases + extraAliases)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                )
            }
        }

        return merged
    }

    private fun loadBaseEntries(): List<StreetEntry> {
        val json = loadTextFromAssets("streets_latvia.json") ?: return emptyList()
        val jsonArray = JSONArray(json)
        val result = ArrayList<StreetEntry>(jsonArray.length())

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            val aliasesArray = obj.optJSONArray("aliases") ?: JSONArray()
            val aliases = ArrayList<String>(aliasesArray.length())
            for (j in 0 until aliasesArray.length()) {
                aliases.add(aliasesArray.getString(j))
            }

            result.add(
                StreetEntry(
                    official = obj.getString("official"),
                    city = obj.getString("city"),
                    aliases = aliases,
                    priority = obj.optInt("priority", 0)
                )
            )
        }

        return result
    }

    private fun loadAliasOverrides(): Map<String, List<String>> {
        val json = loadTextFromAssets("street_alias_overrides.json") ?: return emptyMap()
        val jsonArray = JSONArray(json)

        val result = linkedMapOf<String, List<String>>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            val official = obj.getString("official")
            val city = obj.getString("city")

            val aliasesArray = obj.optJSONArray("aliases") ?: JSONArray()
            val aliases = ArrayList<String>(aliasesArray.length())
            for (j in 0 until aliasesArray.length()) {
                aliases.add(aliasesArray.getString(j))
            }

            result[buildKey(official, city)] = aliases
        }

        return result
    }

    private fun buildKey(official: String, city: String): String {
        return "${official.trim().lowercase()}||${city.trim().lowercase()}"
    }

    private fun loadTextFromAssets(fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            String(buffer, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}