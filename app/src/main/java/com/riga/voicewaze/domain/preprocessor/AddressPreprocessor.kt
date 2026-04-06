package com.riga.voicewaze.domain.preprocessor

import java.util.Locale

data class ProcessedAddressQuery(
    val matcherInput: String,
    val displayText: String,
    val houseNumber: String?,
    val city: String
)

class AddressPreprocessor {

    fun processToQuery(rawText: String): ProcessedAddressQuery {
        val cleanedInput = rawText
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleanedInput.isBlank()) {
            return ProcessedAddressQuery(
                matcherInput = "",
                displayText = "",
                houseNumber = null,
                city = "Rīga"
            )
        }

        val lower = cleanedInput.lowercase(Locale.ROOT)
        val tokens = lower
            .replace(",", " ")
            .replace(".", " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()

        var city = "Rīga"
        if (tokens.contains("rīga") || tokens.contains("rigā")) {
            city = "Rīga"
            tokens.removeAll { it == "rīga" || it == "rigā" }
        }

        val streetTokens = mutableListOf<String>()
        var houseNumber: String? = null
        var corpusNumber: String? = null

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]

            if (houseNumber == null && isHouseNumberToken(token)) {
                houseNumber = normalizeHouseNumber(token)
                index++
                continue
            }

            if (houseNumber != null) {
                val corpus = parseCorpus(tokens, index)
                if (corpus != null) {
                    corpusNumber = corpus.first
                    index = corpus.second
                    continue
                }
            }

            if (isStreetTypeToken(token)) {
                index++
                continue
            }

            streetTokens.add(token)
            index++
        }

        val streetBase = streetTokens
            .map { normalizeStreetWord(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val normalizedStreet = when {
            streetBase.isBlank() -> ""
            streetBase.endsWith(" iela") -> streetBase
            else -> "$streetBase iela"
        }

        val housePart = buildHousePart(houseNumber, corpusNumber)
        val displayText = buildList {
            if (normalizedStreet.isNotBlank()) add(capitalizeLatvian(normalizedStreet))
            if (housePart.isNotBlank()) add(housePart)
        }.joinToString(" ").let {
            if (it.isBlank()) "" else "$it, $city"
        }

        val matcherInput = buildList {
            if (normalizedStreet.isNotBlank()) add(normalizedStreet)
            if (housePart.isNotBlank()) add(housePart)
            if (city.isNotBlank()) add(city.lowercase(Locale.ROOT))
        }.joinToString(" ").trim()

        return ProcessedAddressQuery(
            matcherInput = matcherInput,
            displayText = displayText,
            houseNumber = housePart.ifBlank { null },
            city = city
        )
    }

    private fun buildHousePart(houseNumber: String?, corpusNumber: String?): String {
        if (houseNumber.isNullOrBlank()) return ""
        return if (corpusNumber.isNullOrBlank()) {
            houseNumber
        } else {
            "$houseNumber k-$corpusNumber"
        }
    }

    private fun normalizeStreetWord(word: String): String {
        return word
            .replace(Regex("""[^\p{L}\p{N}-]"""), "")
            .trim()
    }

    private fun normalizeHouseNumber(token: String): String {
        return token
            .replace(Regex("""[^0-9A-Za-z/-]"""), "")
            .trim()
    }

    private fun isStreetTypeToken(token: String): Boolean {
        return token == "iela"
    }

    private fun isHouseNumberToken(token: String): Boolean {
        return token.matches(Regex("""^\d+[A-Za-z]?$""")) ||
            token.matches(Regex("""^\d+[/-]\d+[A-Za-z]?$"""))
    }

    private fun parseCorpus(tokens: List<String>, startIndex: Int): Pair<String, Int>? {
        if (startIndex >= tokens.size) return null

        val token = tokens[startIndex]

        if (token.matches(Regex("""^k-?\d+[A-Za-z]?$"""))) {
            val number = token.removePrefix("k").removePrefix("-").trim()
            return if (number.isBlank()) null else number to (startIndex + 1)
        }

        if ((token == "korpuss" || token == "k") && startIndex + 1 < tokens.size) {
            val next = tokens[startIndex + 1]
            if (next.matches(Regex("""^\d+[A-Za-z]?$"""))) {
                return next to (startIndex + 2)
            }
        }

        return null
    }

    private fun capitalizeLatvian(text: String): String {
        return text
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale("lv", "LV")) else ch.toString()
                }
            }
    }
}
