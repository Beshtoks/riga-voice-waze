package com.riga.voicewaze.domain.preprocessor

import java.util.Locale

data class ProcessedAddressQuery(
    val rawInput: String,
    val matcherInput: String,
    val displayText: String,
    val houseNumber: String?,
    val city: String,
    val isValid: Boolean,
    val errorMessage: String?
)

class AddressPreprocessor {

    fun process(rawText: String): ProcessedAddressQuery {
        val cleanedOriginal = rawText
            .trim()
            .replace(Regex("\\s+"), " ")

        if (cleanedOriginal.isBlank()) {
            return emptyQuery(rawText)
        }

        val normalized = cleanedOriginal
            .replace(",", " ")
            .replace(".", " ")
            .replace("–", "-")
            .replace("—", "-")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) {
            return emptyQuery(rawText)
        }

        val tokens = normalized
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()

        val explicitCity = extractExplicitCityFromEnd(tokens)

        if (tokens.isEmpty()) {
            return invalidQuery(
                rawInput = cleanedOriginal,
                city = explicitCity ?: "Rīga",
                message = "Укажи номер дома"
            )
        }

        val parsed = if (startsWithHouseNumber(tokens)) {
            parseHouseFirst(tokens)
        } else {
            parseStreetFirst(tokens)
        }

        val city = parsed.cityOverride ?: explicitCity ?: "Rīga"

        if (!parsed.isValid) {
            return invalidQuery(
                rawInput = cleanedOriginal,
                city = city,
                message = parsed.errorMessage ?: "Адрес введён некорректно"
            )
        }

        if (parsed.houseNumber.isNullOrBlank()) {
            return invalidQuery(
                rawInput = cleanedOriginal,
                city = city,
                message = "Укажи номер дома"
            )
        }

        if (parsed.streetBase.isBlank()) {
            return invalidQuery(
                rawInput = cleanedOriginal,
                city = city,
                message = "Укажи улицу"
            )
        }

        val streetName = buildStreetName(parsed.streetBase)
        if (streetName.isBlank()) {
            return invalidQuery(
                rawInput = cleanedOriginal,
                city = city,
                message = "Укажи улицу"
            )
        }

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

        val finalHouseNumber = if (parsed.corpusNumber.isNullOrBlank()) {
            parsed.houseNumber
        } else {
            "${parsed.houseNumber} k-${parsed.corpusNumber}"
        }

        return ProcessedAddressQuery(
            rawInput = cleanedOriginal,
            matcherInput = streetName,
            displayText = displayText,
            houseNumber = finalHouseNumber,
            city = city,
            isValid = true,
            errorMessage = null
        )
    }

    private fun emptyQuery(rawInput: String): ProcessedAddressQuery {
        return ProcessedAddressQuery(
            rawInput = rawInput,
            matcherInput = "",
            displayText = "",
            houseNumber = null,
            city = "Rīga",
            isValid = false,
            errorMessage = null
        )
    }

    private fun invalidQuery(
        rawInput: String,
        city: String,
        message: String
    ): ProcessedAddressQuery {
        return ProcessedAddressQuery(
            rawInput = rawInput,
            matcherInput = "",
            displayText = "",
            houseNumber = null,
            city = city,
            isValid = false,
            errorMessage = message
        )
    }

    private fun extractExplicitCityFromEnd(tokens: MutableList<String>): String? {
        if (tokens.isEmpty()) return null

        val lastOriginal = tokens.last()
        val normalizedLast = normalizeToken(lastOriginal)
        if (normalizedLast.isBlank()) return null

        return if (looksLikeExplicitCity(lastOriginal, normalizedLast)) {
            tokens.removeAt(tokens.lastIndex)
            capitalizeLatvianWords(normalizedLast)
        } else {
            null
        }
    }

    private fun looksLikeExplicitCity(original: String, normalized: String): Boolean {
        if (normalized.isBlank()) return false
        if (normalized.any { it.isDigit() }) return false
        if (containsCorpusRoot(normalized.lowercase(Locale.ROOT))) return false

        val first = original.trim().firstOrNull() ?: return false
        return first.isUpperCase()
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
        var cityOverride: String? = null

        var i = 0
        while (i < tokens.size) {
            val token = normalizeToken(tokens[i])
            val lowered = token.lowercase(Locale.ROOT)

            if (token.isBlank()) {
                i++
                continue
            }

            if (houseNumber == null && isHouseNumberToken(token)) {
                houseNumber = token
                i++

                val tail = parseTailAfterHouse(tokens, i)
                if (tail.invalid) {
                    return ParsedAddress.invalid("После слова корпус должна идти цифра")
                }
                corpusNumber = tail.corpusNumber
                cityOverride = tail.cityOverride
                i = tail.nextIndex
                continue
            }

            if (houseNumber == null) {
                if (containsCorpusRoot(lowered)) {
                    return ParsedAddress.invalid("После слова корпус должна идти цифра")
                }
                if (!shouldSkipToken(token)) {
                    streetTokens.add(token)
                }
                i++
                continue
            }

            i++
        }

        return ParsedAddress.valid(
            streetBase = normalizeStreetBase(streetTokens),
            houseNumber = houseNumber,
            corpusNumber = corpusNumber,
            cityOverride = cityOverride
        )
    }

    private fun parseHouseFirst(tokens: List<String>): ParsedAddress {
        var i = 0

        val houseNumber = normalizeHouseNumber(tokens.getOrNull(i).orEmpty())
        i++

        val initialCorpus = tryParseCorpus(tokens, i)
        if (initialCorpus.invalid) {
            return ParsedAddress.invalid("После слова корпус должна идти цифра")
        }

        var corpusNumber: String? = null
        if (initialCorpus.number != null) {
            corpusNumber = initialCorpus.number
            i = initialCorpus.nextIndex
        }

        val streetTokens = mutableListOf<String>()
        while (i < tokens.size) {
            val token = normalizeToken(tokens[i])
            val lowered = token.lowercase(Locale.ROOT)

            if (token.isBlank()) {
                i++
                continue
            }

            val laterCorpus = tryParseCorpus(tokens, i)
            if (laterCorpus.invalid) {
                return ParsedAddress.invalid("После слова корпус должна идти цифра")
            }
            if (laterCorpus.number != null) {
                corpusNumber = laterCorpus.number
                i = laterCorpus.nextIndex
                continue
            }

            if (containsCorpusRoot(lowered)) {
                return ParsedAddress.invalid("После слова корпус должна идти цифра")
            }

            if (!shouldSkipToken(token)) {
                streetTokens.add(token)
            }

            i++
        }

        return ParsedAddress.valid(
            streetBase = normalizeStreetBase(streetTokens),
            houseNumber = houseNumber.ifBlank { null },
            corpusNumber = corpusNumber,
            cityOverride = null
        )
    }

    private fun parseTailAfterHouse(tokens: List<String>, startIndex: Int): TailParseResult {
        if (startIndex >= tokens.size) {
            return TailParseResult.none(startIndex)
        }

        val normalizedTail = tokens
            .subList(startIndex, tokens.size)
            .map { normalizeToken(it) }
            .filter { it.isNotBlank() }

        if (normalizedTail.isEmpty()) {
            return TailParseResult.none(tokens.size)
        }

        if (containsCorpusRoot(normalizedTail.first().lowercase(Locale.ROOT))) {
            if (normalizedTail.size < 2) {
                return TailParseResult.invalid(tokens.size)
            }

            val corpusNumber = extractEmbeddedNumber(normalizedTail[1])
            if (corpusNumber != null) {
                return TailParseResult.valid(
                    corpusNumber = corpusNumber,
                    cityOverride = null,
                    nextIndex = tokens.size
                )
            }

            return TailParseResult.invalid(tokens.size)
        }

        for (index in normalizedTail.indices) {
            val extractedNumber = extractEmbeddedNumber(normalizedTail[index])
            if (extractedNumber != null) {
                return TailParseResult.valid(
                    corpusNumber = extractedNumber,
                    cityOverride = null,
                    nextIndex = tokens.size
                )
            }
        }

        return TailParseResult.valid(
            corpusNumber = null,
            cityOverride = capitalizeLatvianWords(normalizedTail.joinToString(" ")),
            nextIndex = tokens.size
        )
    }

    private fun buildStreetName(streetBase: String): String {
        val base = streetBase.trim()
        if (base.isBlank()) return ""

        val lowered = base.lowercase(Locale.ROOT)
        val hasKnownStreetType = lowered.contains(" iela") ||
            lowered.endsWith("iela") ||
            lowered.contains(" gatve") ||
            lowered.endsWith("gatve") ||
            lowered.contains(" prospekts") ||
            lowered.endsWith("prospekts") ||
            lowered.contains(" aleja") ||
            lowered.endsWith("aleja") ||
            lowered.contains(" bulvāris") ||
            lowered.endsWith("bulvāris") ||
            lowered.contains(" laukums") ||
            lowered.endsWith("laukums") ||
            lowered.contains(" krastmala") ||
            lowered.endsWith("krastmala") ||
            lowered.contains(" dambis") ||
            lowered.endsWith("dambis") ||
            lowered.contains(" ceļš") ||
            lowered.endsWith("ceļš") ||
            lowered.contains(" līnija") ||
            lowered.endsWith("līnija") ||
            lowered.contains(" šķērslīnija") ||
            lowered.endsWith("šķērslīnija") ||
            lowered.contains(" gāte") ||
            lowered.endsWith("gāte") ||
            lowered.contains(" sēta") ||
            lowered.endsWith("sēta") ||
            lowered.contains(" skvērs") ||
            lowered.endsWith("skvērs") ||
            lowered.contains(" taka") ||
            lowered.endsWith("taka")

        val withType = if (hasKnownStreetType) {
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

    private fun extractEmbeddedNumber(token: String): String? {
        if (token.isBlank()) return null

        val match = Regex("(\\d+[A-Za-zА-Яа-я]?)").find(token) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    private fun tryParseCorpus(tokens: List<String>, startIndex: Int): CorpusParseResult {
        if (startIndex >= tokens.size) return CorpusParseResult.none(startIndex)

        val token = normalizeToken(tokens[startIndex]).lowercase(Locale.ROOT)
        if (token.isBlank()) return CorpusParseResult.none(startIndex)

        if (Regex("^k-?\\d+[A-Za-zА-Яа-я]?$").matches(token)) {
            val number = token
                .removePrefix("k")
                .removePrefix("-")
                .trim()

            if (number.isNotBlank()) {
                return CorpusParseResult.valid(number = number, nextIndex = startIndex + 1)
            }
        }

        if (containsCorpusRoot(token)) {
            if (startIndex + 1 >= tokens.size) {
                return CorpusParseResult.invalid(startIndex)
            }

            val next = normalizeToken(tokens[startIndex + 1])
            if (isHouseNumberToken(next)) {
                return CorpusParseResult.valid(number = next, nextIndex = startIndex + 2)
            }

            return CorpusParseResult.invalid(startIndex)
        }

        return CorpusParseResult.none(startIndex)
    }

    private fun containsCorpusRoot(token: String): Boolean {
        return token == "k" || token.contains("korp") || token.contains("корп")
    }

    private fun shouldSkipToken(token: String): Boolean {
        return token.equals("nr", ignoreCase = true) ||
            token.equals("nams", ignoreCase = true) ||
            token == "-"
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
        val corpusNumber: String?,
        val cityOverride: String?,
        val isValid: Boolean,
        val errorMessage: String?
    ) {
        companion object {
            fun valid(streetBase: String, houseNumber: String?, corpusNumber: String?, cityOverride: String?) = ParsedAddress(
                streetBase = streetBase,
                houseNumber = houseNumber,
                corpusNumber = corpusNumber,
                cityOverride = cityOverride,
                isValid = true,
                errorMessage = null
            )

            fun invalid(message: String) = ParsedAddress(
                streetBase = "",
                houseNumber = null,
                corpusNumber = null,
                cityOverride = null,
                isValid = false,
                errorMessage = message
            )
        }
    }

    private data class TailParseResult(
        val corpusNumber: String?,
        val cityOverride: String?,
        val nextIndex: Int,
        val invalid: Boolean
    ) {
        companion object {
            fun none(nextIndex: Int) = TailParseResult(
                corpusNumber = null,
                cityOverride = null,
                nextIndex = nextIndex,
                invalid = false
            )

            fun valid(corpusNumber: String?, cityOverride: String?, nextIndex: Int) = TailParseResult(
                corpusNumber = corpusNumber,
                cityOverride = cityOverride,
                nextIndex = nextIndex,
                invalid = false
            )

            fun invalid(nextIndex: Int) = TailParseResult(
                corpusNumber = null,
                cityOverride = null,
                nextIndex = nextIndex,
                invalid = true
            )
        }
    }

    private data class CorpusParseResult(
        val number: String?,
        val nextIndex: Int,
        val invalid: Boolean
    ) {
        companion object {
            fun none(nextIndex: Int) = CorpusParseResult(number = null, nextIndex = nextIndex, invalid = false)
            fun valid(number: String, nextIndex: Int) = CorpusParseResult(number = number, nextIndex = nextIndex, invalid = false)
            fun invalid(nextIndex: Int) = CorpusParseResult(number = null, nextIndex = nextIndex, invalid = true)
        }
    }
}
