package com.riga.voicewaze.domain.preprocessor

import java.util.Locale

data class ProcessedAddressQuery(
    val displayText: String,
    val matcherInput: String,
    val streetPart: String,
    val houseNumber: String?,
    val city: String
)

class AddressPreprocessor {

    fun process(rawText: String): ProcessedAddressQuery {
        val cleaned = rawText
            .trim()
            .replace(Regex("\\s+"), " ")

        if (cleaned.isBlank()) {
            return emptyResult()
        }

        val normalized = cleaned
            .replace('–', '-')
            .replace('—', '-')
            .replace(',', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val city = if (containsRiga(normalized)) "Rīga" else "Rīga"
        val tokens = tokenize(normalized)
        val parsed = parseTokens(tokens)

        val streetPart = buildStreetPart(parsed.streetTokens)
        val houseNumber = buildHouseNumber(parsed.houseBase, parsed.corpus)

        val displayText = buildDisplayText(
            streetPart = streetPart,
            houseNumber = houseNumber,
            city = city
        )

        return ProcessedAddressQuery(
            displayText = displayText,
            matcherInput = buildMatcherInput(streetPart, city),
            streetPart = streetPart,
            houseNumber = houseNumber,
            city = city
        )
    }

    private fun emptyResult(): ProcessedAddressQuery {
        return ProcessedAddressQuery(
            displayText = "",
            matcherInput = "",
            streetPart = "",
            houseNumber = null,
            city = "Rīga"
        )
    }

    private fun containsRiga(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains("riga") ||
            lower.contains("rīga") ||
            lower.contains("rigā") ||
            lower.contains("rīgā")
    }

    private fun tokenize(input: String): List<String> {
        return input
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
    }

    private fun parseTokens(tokens: List<String>): ParsedTokens {
        if (tokens.isEmpty()) return ParsedTokens(emptyList(), null, null)

        val filtered = tokens.filterNot { isRigaToken(it) }
        val streetTokens = mutableListOf<String>()
        var houseBase: String? = null
        var corpus: String? = null
        var i = 0

        while (i < filtered.size) {
            val token = filtered[i]
            val compact = sanitizeToken(token)

            if (houseBase == null && isHouseNumberToken(compact)) {
                houseBase = compact.uppercase(Locale.ROOT)
                i++
                continue
            }

            if (compact.equals("korpuss", ignoreCase = true) || compact.equals("k", ignoreCase = true)) {
                if (i + 1 < filtered.size) {
                    val next = sanitizeToken(filtered[i + 1])
                    if (isSimpleHousePart(next)) {
                        corpus = next.uppercase(Locale.ROOT)
                        i += 2
                        continue
                    }
                }
                i++
                continue
            }

            if (houseBase != null && corpus == null && compact.startsWith("k-", ignoreCase = true)) {
                val corpusValue = compact.removePrefix("k-")
                if (isSimpleHousePart(corpusValue)) {
                    corpus = corpusValue.uppercase(Locale.ROOT)
                    i++
                    continue
                }
            }

            if (houseBase == null) {
                streetTokens.add(compact)
            }
            i++
        }

        return ParsedTokens(
            streetTokens = streetTokens,
            houseBase = houseBase,
            corpus = corpus
        )
    }

    private fun buildStreetPart(streetTokens: List<String>): String {
        if (streetTokens.isEmpty()) return ""

        val filtered = streetTokens
            .filter { it.isNotBlank() }
            .filterNot { it.equals("iela", ignoreCase = true) }
            .map { capitalizeLatvianToken(it) }

        if (filtered.isEmpty()) return ""
        return filtered.joinToString(" ") + " iela"
    }

    private fun buildHouseNumber(houseBase: String?, corpus: String?): String? {
        if (houseBase.isNullOrBlank()) return null
        return if (corpus.isNullOrBlank()) {
            houseBase
        } else {
            "$houseBase k-$corpus"
        }
    }

    private fun buildDisplayText(
        streetPart: String,
        houseNumber: String?,
        city: String
    ): String {
        val parts = mutableListOf<String>()
        if (streetPart.isNotBlank()) parts.add(streetPart)
        if (!houseNumber.isNullOrBlank()) parts.add(houseNumber)
        if (city.isNotBlank()) parts.add(city)
        return parts.joinToString(", ")
            .replaceFirst(", ", " ")
            .trim()
    }

    private fun buildMatcherInput(streetPart: String, city: String): String {
        return listOf(streetPart, city)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    private fun sanitizeToken(token: String): String {
        return token
            .trim()
            .replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}-]+$"), "")
    }

    private fun isRigaToken(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT)
        return lower == "riga" || lower == "rīga" || lower == "rigā" || lower == "rīgā"
    }

    private fun isHouseNumberToken(token: String): Boolean {
        return Regex("^\\d+[a-zA-Z]?$", RegexOption.IGNORE_CASE).matches(token)
    }

    private fun isSimpleHousePart(token: String): Boolean {
        return Regex("^\\d+[a-zA-Z]?$", RegexOption.IGNORE_CASE).matches(token)
    }

    private fun capitalizeLatvianToken(token: String): String {
        return token.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale("lv", "LV")) else ch.toString()
        }
    }

    private data class ParsedTokens(
        val streetTokens: List<String>,
        val houseBase: String?,
        val corpus: String?
    )
}
