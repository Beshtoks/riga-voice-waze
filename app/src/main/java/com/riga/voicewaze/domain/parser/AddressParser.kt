package com.riga.voicewaze.domain.parser

import com.riga.voicewaze.data.local.StreetRepository
import java.util.Locale

class AddressParser(
    streetRepository: StreetRepository
) {

    private val houseNumberRegex = Regex("""^\d+[a-zA-ZА-Яа-я]?(?:/\d+[a-zA-ZА-Яа-я]?)?$""")
    private val minStreetsPerCity = 50

    private val allowedCities: Map<String, String>

    init {
        val entries = streetRepository.getAllStreetEntries()

        val cities = entries
            .groupBy { normalizeCityKey(it.city) }
            .filter { (_, streets) -> streets.size >= minStreetsPerCity }
            .mapValues { (_, streets) -> streets.first().city }

        allowedCities = cities
    }

    fun parse(input: String): ParsedAddress {
        val normalized = TextNormalizer.normalize(input)
        val tokens = normalized.split(" ").filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            return ParsedAddress(
                streetRaw = "",
                streetMerged = "",
                houseNumber = null,
                cityRaw = "Rīga"
            )
        }

        val houseIndex = tokens.indexOfFirst { isHouseNumber(it) }
        val houseNumber = if (houseIndex >= 0) tokens[houseIndex].uppercase(Locale.ROOT) else null

        val cityIndex = tokens.indexOfFirst { isKnownCity(it) }
        val city = if (cityIndex >= 0) {
            normalizeCity(tokens[cityIndex]) ?: "Rīga"
        } else {
            "Rīga"
        }

        val streetTokens = when {
            houseIndex > 0 -> tokens.subList(0, houseIndex)
            houseIndex == -1 -> tokens.filterIndexed { index, _ -> index != cityIndex }
            else -> emptyList()
        }.filter { !isKnownCity(it) }

        val streetRaw = streetTokens.joinToString(" ").trim()
        val streetMerged = streetTokens.joinToString("").trim()

        return ParsedAddress(
            streetRaw = streetRaw,
            streetMerged = streetMerged,
            houseNumber = houseNumber,
            cityRaw = city
        )
    }

    private fun isHouseNumber(token: String): Boolean {
        return houseNumberRegex.matches(token)
    }

    private fun isKnownCity(token: String): Boolean {
        return allowedCities.containsKey(normalizeCityKey(token))
    }

    private fun normalizeCity(token: String): String? {
        return allowedCities[normalizeCityKey(token)]
    }

    private fun normalizeCityKey(city: String): String {
        return city
            .lowercase(Locale.ROOT)
            .trim()
            .replace("ā", "a")
            .replace("č", "c")
            .replace("ē", "e")
            .replace("ģ", "g")
            .replace("ī", "i")
            .replace("ķ", "k")
            .replace("ļ", "l")
            .replace("ņ", "n")
            .replace("š", "s")
            .replace("ū", "u")
            .replace("ž", "z")
            .replace("\\s+".toRegex(), " ")
    }
}