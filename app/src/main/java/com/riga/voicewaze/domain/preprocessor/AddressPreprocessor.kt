package com.riga.voicewaze.domain.preprocessor

import java.util.Locale

data class ProcessedAddressQuery(
    val rawInput: String,
    val matcherInput: String,
    val displayText: String,
    val houseNumber: String?,
    val city: String
)

class AddressPreprocessor {

    fun process(rawText: String): ProcessedAddressQuery {
        val cleanedOriginal = rawText.trim().replace(Regex("\\s+"), " ")
        if (cleanedOriginal.isBlank()) return emptyQuery(rawText)

        val normalized = cleanedOriginal
            .replace(",", " ")
            .replace(".", " ")
            .replace("–", "-")
            .replace("—", "-")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) return emptyQuery(rawText)

        val tokens = normalized.split(" ").filter { it.isNotBlank() }.toMutableList()

        val city = extractCity(tokens) ?: "Rīga"

        if (tokens.isEmpty()) {
            return ProcessedAddressQuery(cleanedOriginal, "", "", null, city)
        }

        val parsed = if (startsWithHouseNumber(tokens)) {
            parseHouseFirst(tokens)
        } else {
            parseStreetFirst(tokens)
        }

        if (parsed.streetBase.isBlank()) {
            return ProcessedAddressQuery(cleanedOriginal, cleanedOriginal, cleanedOriginal, null, city)
        }

        val streetName = buildStreetName(parsed.streetBase)
        val housePart = buildHousePart(parsed.houseNumber, parsed.corpusNumber)

        val displayText = (streetName + " " + housePart).trim() + ", " + city
        val matcherInput = (streetName + " " + housePart).trim()

        val finalHouse = if (parsed.houseNumber == null) null
        else if (parsed.corpusNumber == null) parsed.houseNumber
        else "${parsed.houseNumber} k-${parsed.corpusNumber}"

        return ProcessedAddressQuery(
            rawInput = cleanedOriginal,
            matcherInput = matcherInput,
            displayText = displayText,
            houseNumber = finalHouse,
            city = city
        )
    }

    private fun emptyQuery(raw: String) = ProcessedAddressQuery(raw, "", "", null, "Rīga")

    private fun extractCity(tokens: MutableList<String>): String? {
        if (tokens.isEmpty()) return null

        val last = tokens.last()
        val norm = normalizeToken(last)

        if (last.firstOrNull()?.isUpperCase() == true && norm.isNotBlank()) {
            tokens.removeLast()
            return capitalize(norm)
        }

        if (!norm.any { it.isDigit() } &&
            !isStreetType(norm) &&
            !isCorpus(norm)) {
            tokens.removeLast()
            return capitalize(norm)
        }

        return null
    }

    private fun startsWithHouseNumber(tokens: List<String>): Boolean {
        return normalizeToken(tokens.firstOrNull() ?: "").matches(Regex("^\\d+"))
    }

    private fun parseStreetFirst(tokens: List<String>): Parsed {
        val street = mutableListOf<String>()
        var house: String? = null

        for (t in tokens) {
            val n = normalizeToken(t)
            if (house == null && n.matches(Regex("^\\d+"))) {
                house = n
                continue
            }
            if (!isStreetType(n) && !isCorpus(n)) {
                street.add(n)
            }
        }

        return Parsed(street.joinToString(" "), house, null)
    }

    private fun parseHouseFirst(tokens: List<String>): Parsed {
        val house = normalizeToken(tokens.first())
        val street = tokens.drop(1).map { normalizeToken(it) }
            .filter { !isStreetType(it) && !isCorpus(it) }

        return Parsed(street.joinToString(" "), house, null)
    }

    private fun buildStreetName(base: String): String {
        if (base.isBlank()) return ""
        val name = if (base.endsWith("iela", true)) base else "$base iela"
        return capitalize(name)
    }

    private fun buildHousePart(h: String?, c: String?): String {
        if (h == null) return ""
        return if (c == null) h else "$h k-$c"
    }

    private fun normalizeToken(s: String): String {
        return s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private fun isStreetType(s: String) =
        s.equals("iela", true) || s.equals("gatve", true)

    private fun isCorpus(s: String) =
        s.equals("korpuss", true) || s.equals("k", true)

    private fun capitalize(text: String): String {
        return text.split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }

    private data class Parsed(
        val streetBase: String,
        val houseNumber: String?,
        val corpusNumber: String?
    )
}
