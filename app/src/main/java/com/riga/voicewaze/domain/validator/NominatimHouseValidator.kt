package com.riga.voicewaze.domain.validator

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.LinkedHashSet
import java.util.Locale

class NominatimHouseValidator {

    private val cache = LinkedHashMap<String, HouseValidationResult>()

    fun validateHouse(
        street: String,
        houseNumber: String,
        city: String
    ): HouseValidationResult {
        val normalizedStreet = normalizeStreet(street)
        val normalizedCity = normalizeCity(city)
        val houseInput = parseHouseInput(houseNumber)

        if (normalizedStreet.isBlank() || normalizedCity.isBlank() || houseInput == null) {
            return HouseValidationResult(
                status = HouseValidationStatus.CHECK_FAILED,
                message = "Недостаточно данных для проверки адреса"
            )
        }

        val cacheKey = "$normalizedStreet|${houseInput.cacheKey()}|$normalizedCity"
        synchronized(cache) {
            cache[cacheKey]?.let { return it }
        }

        val result = try {
            requestValidation(
                street = street,
                city = city,
                normalizedStreet = normalizedStreet,
                normalizedCity = normalizedCity,
                houseInput = houseInput
            )
        } catch (_: Exception) {
            HouseValidationResult(
                status = HouseValidationStatus.CHECK_FAILED,
                message = "Не удалось проверить дом через интернет"
            )
        }

        synchronized(cache) {
            cache[cacheKey] = result
            if (cache.size > 250) {
                val firstKey = cache.entries.firstOrNull()?.key
                if (firstKey != null) {
                    cache.remove(firstKey)
                }
            }
        }

        return result
    }

    private fun requestValidation(
        street: String,
        city: String,
        normalizedStreet: String,
        normalizedCity: String,
        houseInput: ParsedHouseInput
    ): HouseValidationResult {
        val allMatches = LinkedHashMap<String, NominatimMatch>()
        val queryVariants = buildQueryVariants(houseInput)

        for (variant in queryVariants) {
            performSearches(street, city, variant).forEach { match ->
                if (match.normalizedRoad.isBlank() || match.normalizedCity.isBlank()) return@forEach
                if (!streetsMatch(match.normalizedRoad, normalizedStreet)) return@forEach
                if (!citiesMatch(match.normalizedCity, normalizedCity)) return@forEach
                allMatches.putIfAbsent(match.uniqueKey(), match)
            }

            val exactMatch = allMatches.values.firstOrNull { match ->
                houseInput.matchesExactly(match.parsedHouse)
            }
            if (exactMatch != null) {
                return HouseValidationResult(
                    status = HouseValidationStatus.VALID,
                    canonicalHouseNumber = exactMatch.houseNumberRaw
                )
            }
        }

        val relatedMatches = allMatches.values
            .filter { match -> houseInput.isRelated(match.parsedHouse) }
            .sortedBy { it.displayHouseNumber }

        if (relatedMatches.isNotEmpty()) {
            val relatedNumbers = relatedMatches
                .map { it.displayHouseNumber }
                .distinct()
                .take(7)

            val message = if (houseInput.isBaseOnly || houseInput.isIncomplete) {
                "Найдены корпуса: ${relatedNumbers.joinToString(", ")}"
            } else {
                "Найдены похожие номера: ${relatedNumbers.joinToString(", ")}"
            }

            return HouseValidationResult(
                status = HouseValidationStatus.RELATED_FOUND,
                message = message,
                relatedHouseNumbers = relatedNumbers
            )
        }

        return HouseValidationResult(
            status = HouseValidationStatus.NOT_FOUND,
            message = "Точный номер дома не подтверждён"
        )
    }

    private fun performSearches(
        street: String,
        city: String,
        houseVariant: String
    ): List<NominatimMatch> {
        val collected = LinkedHashMap<String, NominatimMatch>()

        val structuredStreet = "$houseVariant $street".trim()
        requestArray(
            buildStructuredUrl(
                street = structuredStreet,
                city = city
            )
        ).forEach { match ->
            collected.putIfAbsent(match.uniqueKey(), match)
        }

        val freeTextQuery = "$street $houseVariant, $city, Latvija".trim()
        requestArray(
            buildFreeTextUrl(freeTextQuery)
        ).forEach { match ->
            collected.putIfAbsent(match.uniqueKey(), match)
        }

        return collected.values.toList()
    }

    private fun requestArray(urlString: String): List<NominatimMatch> {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "RigaVoiceWaze/1.0")
            setRequestProperty("Accept-Language", "lv,en")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                emptyList()
            } else {
                val rawJson = connection.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        reader.readText()
                    }
                }
                parseMatches(rawJson)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMatches(rawJson: String): List<NominatimMatch> {
        val array = JSONArray(rawJson)
        val list = ArrayList<NominatimMatch>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val address = item.optJSONObject("address")
            val displayName = item.optString("display_name")
            val road = normalizeStreet(
                address?.optString("road").orEmpty().ifBlank {
                    extractRoadFromDisplayName(displayName)
                }
            )
            val city = normalizeCity(
                address?.optString("city").orEmpty().ifBlank {
                    address?.optString("town").orEmpty().ifBlank {
                        address?.optString("village").orEmpty().ifBlank {
                            address?.optString("municipality").orEmpty().ifBlank {
                                address?.optString("hamlet").orEmpty()
                            }
                        }
                    }
                }
            )
            val houseNumberRaw = address?.optString("house_number").orEmpty().ifBlank {
                extractHouseNumberFromDisplayName(displayName)
            }
            val parsedHouse = parseHouseInput(houseNumberRaw)

            list += NominatimMatch(
                houseNumberRaw = houseNumberRaw,
                parsedHouse = parsedHouse,
                normalizedRoad = road,
                normalizedCity = city,
                displayHouseNumber = prettifyHouseNumber(houseNumberRaw)
            )
        }

        return list
    }

    private fun buildQueryVariants(houseInput: ParsedHouseInput): List<String> {
        val variants = LinkedHashSet<String>()

        when {
            houseInput.isIncomplete || houseInput.isBaseOnly -> {
                variants += houseInput.baseNumber
            }

            houseInput.suffix != null -> {
                val base = houseInput.baseNumber
                val suffix = houseInput.suffix
                variants += "$base k-$suffix"
                variants += "$base k$suffix"
                variants += "$base/$suffix"
                variants += "$base-$suffix"
                variants += "$base $suffix"
            }

            houseInput.canonical != null -> {
                variants += houseInput.canonical
            }
        }

        variants += houseInput.rawCompact
        return variants.filter { it.isNotBlank() }
    }

    private fun buildStructuredUrl(street: String, city: String): String {
        return buildString {
            append("https://nominatim.openstreetmap.org/search?")
            append("street=")
            append(encode(street))
            append("&city=")
            append(encode(city))
            append("&country=")
            append(encode("Latvija"))
            append("&countrycodes=lv")
            append("&format=jsonv2")
            append("&addressdetails=1")
            append("&limit=10")
        }
    }

    private fun buildFreeTextUrl(query: String): String {
        return buildString {
            append("https://nominatim.openstreetmap.org/search?")
            append("q=")
            append(encode(query))
            append("&countrycodes=lv")
            append("&format=jsonv2")
            append("&addressdetails=1")
            append("&limit=10")
        }
    }

    private fun extractRoadFromDisplayName(displayName: String): String {
        val parts = displayName.split(",").map { it.trim() }
        return if (parts.size >= 2) parts[1] else ""
    }

    private fun extractHouseNumberFromDisplayName(displayName: String): String {
        val firstPart = displayName.substringBefore(",").trim()
        val regexes = listOf(
            Regex("""\b\d+\s*[-/]?\s*k\s*[-/]?\s*\d+[a-zA-Z]?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d+k\d+[a-zA-Z]?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d+\s*[-/]\s*\d+[a-zA-Z]?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d+[a-zA-Z]?\b""", RegexOption.IGNORE_CASE)
        )

        for (regex in regexes) {
            val match = regex.find(firstPart)
            if (match != null) {
                return match.value.trim()
            }
        }

        return ""
    }

    private fun streetsMatch(foundStreet: String, expectedStreet: String): Boolean {
        if (foundStreet == expectedStreet) return true
        if (foundStreet.contains(expectedStreet)) return true
        if (expectedStreet.contains(foundStreet)) return true
        return false
    }

    private fun citiesMatch(foundCity: String, expectedCity: String): Boolean {
        if (foundCity == expectedCity) return true
        if (foundCity.isBlank() || expectedCity.isBlank()) return false
        return foundCity.contains(expectedCity) || expectedCity.contains(foundCity)
    }

    private fun normalizeStreet(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace('ī', 'i')
            .replace('ā', 'a')
            .replace('ē', 'e')
            .replace('ū', 'u')
            .replace('ķ', 'k')
            .replace('ģ', 'g')
            .replace('ļ', 'l')
            .replace('ņ', 'n')
            .replace('š', 's')
            .replace('ž', 'z')
            .replace('č', 'c')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeCity(value: String): String {
        return normalizeStreet(value)
    }

    private fun parseHouseInput(value: String): ParsedHouseInput? {
        val raw = value.trim()
        if (raw.isBlank()) return null

        val compact = raw
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        Regex("""^(\d+)\s*[-/]?\s*k\s*[-/]?\s*(\d+[a-z]?)$""", RegexOption.IGNORE_CASE).matchEntire(compact)?.let {
            return ParsedHouseInput(
                rawCompact = compact,
                baseNumber = it.groupValues[1],
                suffix = it.groupValues[2],
                canonical = "${it.groupValues[1]}-${it.groupValues[2]}",
                isComplete = true,
                isBaseOnly = false,
                isIncomplete = false
            )
        }

        Regex("""^(\d+)k(\d+[a-z]?)$""", RegexOption.IGNORE_CASE).matchEntire(compact.replace(" ", ""))?.let {
            return ParsedHouseInput(
                rawCompact = compact,
                baseNumber = it.groupValues[1],
                suffix = it.groupValues[2],
                canonical = "${it.groupValues[1]}-${it.groupValues[2]}",
                isComplete = true,
                isBaseOnly = false,
                isIncomplete = false
            )
        }

        Regex("""^(\d+)\s*[-/]\s*(\d+[a-z]?)$""", RegexOption.IGNORE_CASE).matchEntire(compact)?.let {
            return ParsedHouseInput(
                rawCompact = compact,
                baseNumber = it.groupValues[1],
                suffix = it.groupValues[2],
                canonical = "${it.groupValues[1]}-${it.groupValues[2]}",
                isComplete = true,
                isBaseOnly = false,
                isIncomplete = false
            )
        }

        Regex("""^(\d+)\s*k$""", RegexOption.IGNORE_CASE).matchEntire(compact)?.let {
            return ParsedHouseInput(
                rawCompact = compact,
                baseNumber = it.groupValues[1],
                suffix = null,
                canonical = null,
                isComplete = false,
                isBaseOnly = false,
                isIncomplete = true
            )
        }

        Regex("""^(\d+[a-z]?)$""", RegexOption.IGNORE_CASE).matchEntire(compact.replace(" ", ""))?.let {
            val baseValue = it.groupValues[1]
            return ParsedHouseInput(
                rawCompact = compact,
                baseNumber = baseValue,
                suffix = null,
                canonical = baseValue,
                isComplete = true,
                isBaseOnly = true,
                isIncomplete = false
            )
        }

        return null
    }

    private fun prettifyHouseNumber(value: String): String {
        val parsed = parseHouseInput(value) ?: return value.trim()
        return when {
            parsed.suffix != null -> "${parsed.baseNumber} k-${parsed.suffix}"
            else -> parsed.baseNumber
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

enum class HouseValidationStatus {
    VALID,
    RELATED_FOUND,
    NOT_FOUND,
    CHECK_FAILED
}

data class HouseValidationResult(
    val status: HouseValidationStatus,
    val message: String? = null,
    val relatedHouseNumbers: List<String> = emptyList(),
    val canonicalHouseNumber: String? = null
) {
    val isValid: Boolean
        get() = status == HouseValidationStatus.VALID
}

private data class ParsedHouseInput(
    val rawCompact: String,
    val baseNumber: String,
    val suffix: String?,
    val canonical: String?,
    val isComplete: Boolean,
    val isBaseOnly: Boolean,
    val isIncomplete: Boolean
) {
    fun matchesExactly(other: ParsedHouseInput?): Boolean {
        if (other == null) return false
        if (canonical.isNullOrBlank() || other.canonical.isNullOrBlank()) return false
        return canonical == other.canonical
    }

    fun isRelated(other: ParsedHouseInput?): Boolean {
        if (other == null) return false
        if (baseNumber.isBlank() || other.baseNumber.isBlank()) return false
        if (baseNumber != other.baseNumber) return false
        if (matchesExactly(other)) return false
        return true
    }

    fun cacheKey(): String {
        return listOf(rawCompact, baseNumber, suffix.orEmpty(), canonical.orEmpty()).joinToString("|")
    }
}

private data class NominatimMatch(
    val houseNumberRaw: String,
    val parsedHouse: ParsedHouseInput?,
    val normalizedRoad: String,
    val normalizedCity: String,
    val displayHouseNumber: String
) {
    fun uniqueKey(): String {
        return "$displayHouseNumber|$normalizedRoad|$normalizedCity"
    }
}