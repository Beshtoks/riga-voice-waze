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
        val cleanedOriginal = rawText
            .trim()
            .replace(Regex("\\s+"), " ")

        if (cleanedOriginal.isBlank()) {
            return ProcessedAddressQuery(
                rawInput = rawText,
                matcherInput = "",
                displayText = "",
                houseNumber = null,
                city = "Rīga"
            )
        }

        val normalized = cleanedOriginal
            .replace(",", " ")
            .replace(".", " ")
            .replace("–", "-")
            .replace("—", "-")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) {
            return ProcessedAddressQuery(
                rawInput = rawText,
                matcherInput = "",
                displayText = "",
                houseNumber = null,
                city = "Rīga"
            )
        }

        val city = extractCity(normalized) ?: "Rīga"
        val tokens = normalized
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()

        removeCityTokens(tokens)

        if (tokens.isEmpty()) {
            return ProcessedAddressQuery(
                rawInput = rawText,
                matcherInput = "",
                displayText = "",
                houseNumber = null,
                city = city
            )
        }

        val parsed = if (startsWithHouseNumber(tokens)) {
            parseHouseFirst(tokens)
        } else {
            parseStreetFirst(tokens)
        }

        if (parsed.streetBase.isBlank()) {
            return ProcessedAddressQuery(
                rawInput = rawText,
                matcherInput = cleanedOriginal,
                displayText = cleanedOriginal,
                houseNumber = null,
                city = city
            )
        }

        val streetName = buildStreetName(parsed.streetBase)
        val housePart = buildHousePart(parsed.houseNumber, parsed.corpusNumber)

        val displayText = buildString {
            append(streetName)
            if (housePart.isNotBlank()) {
                append(" ")
                append(housePart)
            }
            append(", ")
            append(city)
        }.trim()

        val matcherInput = buildString {
            append(streetName)
            if (housePart.isNotBlank()) {
                append(" ")
                append(housePart)
            }
        }.trim()

        val finalHouseNumber = when {
            parsed.houseNumber.isNullOrBlank() -> null
            parsed.corpusNumber.isNullOrBlank() -> parsed.houseNumber
            else -> "${parsed.houseNumber} k-${parsed.corpusNumber}"
        }

        return ProcessedAddressQuery(
            rawInput = cleanedOriginal,
            matcherInput = matcherInput,
            displayText = displayText,
            houseNumber = finalHouseNumber,
            city = city
        )
    }

    private fun startsWithHouseNumber(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val first = normalizeToken(tokens.first())
        return isHouseNumberToken(first)
    }

    private fun parseStreetFirst(tokens: List<String>): ParsedAddress {
        val streetTokens = mutableListOf<String>()
        var houseNumber: String? = null
        var corpusNumber: String? = null

        var i = 0
        while (i < tokens.size) {
            val token = normalizeToken(tokens[i])

            if (token.isBlank()) {
                i++
                continue
            }

            if (houseNumber == null && isHouseNumberToken(token)) {
                houseNumber = token
                i++

                val corpus = tryParseCorpus(tokens, i)
                if (corpus != null) {
                    corpusNumber = corpus.number
                    i = corpus.nextIndex
                }
                continue
            }

            if (houseNumber != null) {
                val corpus = tryParseCorpus(tokens, i)
                if (corpus != null) {
                    corpusNumber = corpus.number
                    i = corpus.nextIndex
                    continue
                }
            }

            if (!shouldSkipToken(token) && !isStreetTypeToken(token)) {
                streetTokens.add(token)
            }

            i++
        }

        return ParsedAddress(
            streetBase = normalizeStreetBase(streetTokens),
            houseNumber = houseNumber,
            corpusNumber = corpusNumber
        )
    }

    private fun parseHouseFirst(tokens: List<String>): ParsedAddress {
        var i = 0

        val houseNumber = normalizeHouseNumber(tokens.getOrNull(i).orEmpty())
        i++

        var corpusNumber: String? = null
        val initialCorpus = tryParseCorpus(tokens, i)
        if (initialCorpus != null) {
            corpusNumber = initialCorpus.number
            i = initialCorpus.nextIndex
        }

        val streetTokens = mutableListOf<String>()
        while (i < tokens.size) {
            val token = normalizeToken(tokens[i])

            if (token.isBlank()) {
                i++
                continue
            }

            val laterCorpus = tryParseCorpus(tokens, i)
            if (laterCorpus != null) {
                corpusNumber = laterCorpus.number
                i = laterCorpus.nextIndex
                continue
            }

            if (!shouldSkipToken(token) && !isStreetTypeToken(token)) {
                streetTokens.add(token)
            }

            i++
        }

        return ParsedAddress(
            streetBase = normalizeStreetBase(streetTokens),
            houseNumber = houseNumber.ifBlank { null },
            corpusNumber = corpusNumber
        )
    }

    private fun buildStreetName(streetBase: String): String {
        val base = streetBase.trim()
        if (base.isBlank()) return ""

        val withType = if (base.endsWith(" iela", ignoreCase = true)) {
            base
        } else {
            "$base iela"
        }

        return capitalizeLatvianWords(withType)
    }

    private fun buildHousePart(houseNumber: String?, corpusNumber: String?): String {
        if (houseNumber.isNullOrBlank()) return ""
        return if (corpusNumber.isNullOrBlank()) {
            houseNumber
        } else {
            "$houseNumber k-$corpusNumber"
        }
    }

    private fun normalizeStreetBase(tokens: List<String>): String {
        return tokens
            .map { normalizeStreetWord(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun normalizeStreetWord(word: String): String {
        return word
            .replace(Regex("[^\\p{L}\\p{N}-]"), "")
            .trim()
    }

    private fun normalizeHouseNumber(token: String): String {
        return normalizeToken(token)
    }

    private fun normalizeToken(token: String): String {
        return token
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}/-]"), "")
    }

    private fun isHouseNumberToken(token: String): Boolean {
        if (token.isBlank()) return false

        return Regex("^\\d+[A-Za-zА-Яа-я]?$").matches(token) ||
            Regex("^\\d+[/-]\\d+[A-Za-zА-Яа-я]?$").matches(token)
    }

    private fun tryParseCorpus(tokens: List<String>, startIndex: Int): CorpusParseResult? {
        if (startIndex >= tokens.size) return null

        val token = normalizeToken(tokens[startIndex]).lowercase(Locale.ROOT)
        if (token.isBlank()) return null

        if (Regex("^k-?\\d+[A-Za-zА-Яа-я]?$").matches(token)) {
            val number = token
                .removePrefix("k")
                .removePrefix("-")
                .trim()

            if (number.isNotBlank()) {
                return CorpusParseResult(number = number, nextIndex = startIndex + 1)
            }
        }

        if (isCorpusKeyword(token) && startIndex + 1 < tokens.size) {
            val next = normalizeToken(tokens[startIndex + 1])
            if (isHouseNumberToken(next)) {
                return CorpusParseResult(number = next, nextIndex = startIndex + 2)
            }
        }

        return null
    }

    private fun isCorpusKeyword(token: String): Boolean {
        return token == "korpuss" || token == "korpus" || token == "k"
    }

    private fun isStreetTypeToken(token: String): Boolean {
        return token.equals("iela", ignoreCase = true)
    }

    private fun shouldSkipToken(token: String): Boolean {
        return token.equals("nr", ignoreCase = true) ||
            token.equals("nams", ignoreCase = true) ||
            token == "-"
    }

    private fun extractCity(text: String): String? {
        val lower = text.lowercase(Locale("lv", "LV"))

        return when {
            Regex("(^|\\s)rīga($|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "Rīga"
            Regex("(^|\\s)rigā($|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "Rīga"
            else -> null
        }
    }

    private fun removeCityTokens(tokens: MutableList<String>) {
        tokens.removeAll { token ->
            val t = normalizeToken(token).lowercase(Locale("lv", "LV"))
            t == "rīga" || t == "rigā"
        }
    }

    private fun capitalizeLatvianWords(text: String): String {
        return text
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) {
                        ch.titlecase(Locale("lv", "LV"))
                    } else {
                        ch.toString()
                    }
                }
            }
    }

    private data class ParsedAddress(
        val streetBase: String,
        val houseNumber: String?,
        val corpusNumber: String?
    )

    private data class CorpusParseResult(
        val number: String,
        val nextIndex: Int
    )
}
