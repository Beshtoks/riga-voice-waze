package com.riga.voicewaze.data.local

import android.content.Context
import org.json.JSONArray

class StreetRepository(
    private val context: Context
) {

    fun getKnownStreets(): List<String> {
        val json = loadJsonFromAssets() ?: return emptyList()
        val jsonArray = JSONArray(json)
        val streets = mutableListOf<String>()

        for (i in 0 until jsonArray.length()) {
            streets.add(jsonArray.getString(i))
        }

        return streets
    }

    private fun loadJsonFromAssets(): String? {
        return try {
            val inputStream = context.assets.open("streets.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            String(buffer, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}