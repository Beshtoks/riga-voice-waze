package com.riga.voicewaze.domain.matcher

import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.data.model.StreetEntry
import java.util.Locale

class StreetMatcher(
    private val repository: StreetRepository
) {

    private val normalizer = StreetNormalizer()
    private val minStreetsPerCity = 50

    private data class IndexedStreet(
        val streetEntry: StreetEntry,
        val normalizedOfficial: String,
        val normalizedAliases: List<String>,
        val normalizedCity: String,
        val candidateTokens: List<String>,
        val officialTokens: List<String>,
        val aliasTokenGroups: List<List<String>>
    )

    private data class CandidateScore(
        val indexedStreet: IndexedStreet,
        val score: Int,
        val percent: Int
    )

    private data class ParsedInput(
        val normalizedInput: String,
        val streetTokens: List<String>,
        val houseNumber: String?,
        val houseSuffix: String?
    )

    private val indexedEntries: List<IndexedStreet> by lazy {
        val allEntries = repository.getAllStreetEntries()

        val allowedCities = allEntries
            .groupBy { normalizeCity(it.city) }
            .filter { (_, streets) -> streets.size >= minStreetsPerCity }
            .keys

        allEntries
            .filter { normalizeCity(it.city) in allowedCities }
            .map { entry ->
                val normalizedOfficial = normalizer.normalizeStreetName(entry.official)
                val normalizedAliases = entry.aliases.map { alias ->
                    normalizer.normalizeUserInput(alias)
                }
                val normalizedCity = normalizeCity(entry.city)

                val tokens = buildCandidateTokens(
                    official = normalizedOfficial,
                    aliases = normalizedAliases,
                    city = normalizedCity
                )

                val officialTokens = splitTokens(normalizedOfficial)
                val aliasTokenGroups = normalizedAliases.map { splitTokens(it) }

                IndexedStreet(
                    streetEntry = entry,
                    normalizedOfficial = normalizedOfficial,
                    normalizedAliases = normalizedAliases,
                    normalizedCity = normalizedCity,
                    candidateTokens = tokens,
                    officialTokens = officialTokens,
                    aliasTokenGroups = aliasTokenGroups
                )
            }
    }

    fun findBestMatch(input: String): String {
        return findBestMatchDetailed(input, null).street
    }

    fun findBestMatchDetailed(
        input: String,
        preferredCity: String?
    ): StreetMatchResult {
        val candidates = rankAddressCandidates(
            input = input,
            preferredCity = preferredCity,
            limit = 1
        )

        val winner = candidates.firstOrNull()
        if (winner == null) {
            return StreetMatchResult(
                street = "Nezināma",
                city = if (preferredCity.isNullOrBlank()) "Rīga" else preferredCity,
                score = Int.MAX_VALUE,
                matchPercent = 0,
                isConfident = false
            )
        }

        return StreetMatchResult(
            street = winner.indexedStreet.streetEntry.official,
            city = winner.indexedStreet.streetEntry.city,
            score = winner.score,
            matchPercent = winner.percent,
            isConfident = winner.percent >= 85
        )
    }

    fun findTopMatchesDetailed(
        input: String,
        preferredCity: String?,
        limit: Int = 10
    ): List<AddressSuggestion> {
        return rankAddressCandidates(input, preferredCity, limit)
            .map { candidate ->
                AddressSuggestion(
                    street = candidate.indexedStreet.streetEntry.official,
                    city = candidate.indexedStreet.streetEntry.city,
                    matchPercent = candidate.percent,
                    score = candidate.score
                )
            }
    }

    fun findTopMatchesForTypedInput(
        input: String,
        limit: Int = 10
    ): List<AddressSuggestion> {
        return rankAddressCandidates(
            input = input,
            preferredCity = "Rīga",
            limit = limit
        ).map { candidate ->
            AddressSuggestion(
                street = candidate.indexedStreet.streetEntry.official,
                city = candidate.indexedStreet.streetEntry.city,
                matchPercent = candidate.percent,
                score = candidate.score
            )
        }
    }

    private fun rankAddressCandidates(
        input: String,
        preferredCity: String?,
        limit: Int
    ): List<CandidateScore> {
        val parsedInput = parseInput(input)
        if (parsedInput.normalizedInput.isBlank()) return emptyList()

        val preferredNormalizedCity = preferredCity
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeCity(it) }

        val cityFiltered = if (preferredNormalizedCity != null) {
            indexedEntries.filter { it.normalizedCity == preferredNormalizedCity }
        } else {
            emptyList()
        }

        val searchPool = if (cityFiltered.isNotEmpty()) cityFiltered else indexedEntries

        return searchPool
            .map { candidate -> calculateAddressCandidateScore(parsedInput, candidate) }
            .sortedWith(
                compareByDescending<CandidateScore> { it.percent }
                    .thenBy { it.score }
                    .thenByDescending { it.indexedStreet.streetEntry.priority }
                    .thenBy { it.indexedStreet.streetEntry.official }
            )
            .take(limit.coerceAtLeast(1))
    }

    private fun calculateAddressCandidateScore(
        parsedInput: ParsedInput,
        item: IndexedStreet
    ): CandidateScore {
        val officialPercent = calculateStreetPercent(parsedInput, item.officialTokens)
        val aliasPercents = item.aliasTokenGroups.map { aliasTokens ->
            calculateStreetPercent(parsedInput, aliasTokens)
        }

        val bestPercent = (aliasPercents + officialPercent).maxOrNull() ?: 0

        val phraseDistance = listOf(item.normalizedOfficial, *item.normalizedAliases.toTypedArray())
            .minOf { candidateText ->
                distance(parsedInput.streetTokens.joinToString(" "), candidateText)
            }

        return CandidateScore(
            indexedStreet = item,
            score = 100 - bestPercent + phraseDistance,
            percent = bestPercent
        )
    }

    private fun calculateStreetPercent(
        parsedInput: ParsedInput,
        candidateStreetTokens: List<String>
    ): Int {
        val queryStreetTokens = parsedInput.streetTokens
        if (queryStreetTokens.isEmpty() || candidateStreetTokens.isEmpty()) return 0

        var percent = 0

        val queryPhrase = queryStreetTokens.joinToString(" ")
        val candidatePhrase = candidateStreetTokens.joinToString(" ")

        val exactWordCount = countExactWordMatches(queryStreetTokens, candidateStreetTokens)
        val prefixWordCount = countPrefixWordMatches(queryStreetTokens, candidateStreetTokens)
        val containsOnlyCount = countContainsOnlyMatches(queryStreetTokens, candidateStreetTokens)

        when {
            exactWordCount == queryStreetTokens.size -> percent += 65
            exactWordCount >= 1 -> percent += 45
        }

        when {
            prefixWordCount == queryStreetTokens.size -> percent += 45
            prefixWordCount >= 1 -> percent += 28
        }

        when {
            containsOnlyCount == queryStreetTokens.size -> percent += 8
            containsOnlyCount >= 1 -> percent += 3
        }

        val phraseSimilarity = similarityPercent(queryPhrase, candidatePhrase)
        percent += (phraseSimilarity * 0.10).toInt()

        var tokenAverage = 0.0
        var strongMatches = 0
        for (queryToken in queryStreetTokens) {
            val bestTokenPercent = candidateStreetTokens.maxOfOrNull { candidateToken ->
                when {
                    candidateToken == queryToken -> 100
                    candidateToken.startsWith(queryToken) -> 90
                    candidateToken.contains(queryToken) -> 65
                    else -> similarityPercent(queryToken, candidateToken)
                }
            } ?: 0

            tokenAverage += bestTokenPercent.toDouble()
            if (bestTokenPercent >= 85) strongMatches++
        }

        percent += ((tokenAverage / queryStreetTokens.size) * 0.10).toInt()

        if (strongMatches == queryStreetTokens.size) {
            percent += 8
        } else if (strongMatches >= 1) {
            percent += 4
        }

        val streetType = candidateStreetTokens.lastOrNull() ?: ""
        when (streetType) {
            "iela" -> percent += 20
            "gatve" -> percent += 15
            "prospekts" -> percent += 12
            "bulvaris", "bulvāris" -> percent += 10
            "laukums" -> percent += 8
        }

        if (parsedInput.houseNumber != null) {
            percent += 6
        }
        if (parsedInput.houseSuffix != null) {
            percent += 4
        }

        return percent.coerceIn(0, 100)
    }

    private fun countExactWordMatches(
        queryTokens: List<String>,
        candidateTokens: List<String>
    ): Int {
        var count = 0
        for (queryToken in queryTokens) {
            val matched = candidateTokens.any { candidateToken ->
                candidateToken == queryToken
            }
            if (matched) {
                count++
            }
        }
        return count
    }

    private fun countPrefixWordMatches(
        queryTokens: List<String>,
        candidateTokens: List<String>
    ): Int {
        var count = 0
        for (queryToken in queryTokens) {
            val matched = candidateTokens.any { candidateToken ->
                candidateToken.startsWith(queryToken)
            }
            if (matched) {
                count++
            }
        }
        return count
    }

    private fun countContainsOnlyMatches(
        queryTokens: List<String>,
        candidateTokens: List<String>
    ): Int {
        var count = 0
        for (queryToken in queryTokens) {
            val matched = candidateTokens.any { candidateToken ->
                !candidateToken.startsWith(queryToken) && candidateToken.contains(queryToken)
            }
            if (matched) {
                count++
            }
        }
        return count
    }

    private fun parseInput(input: String): ParsedInput {
        val normalizedInput = normalizer.normalizeUserInput(input)
        if (normalizedInput.isBlank()) {
            return ParsedInput(
                normalizedInput = "",
                streetTokens = emptyList(),
                houseNumber = null,
                houseSuffix = null
            )
        }

        val tokens = normalizedInput
            .split(" ")
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }

        val streetTokens = mutableListOf<String>()
        var houseNumber: String? = null
        var houseSuffix: String? = null

        for (token in tokens) {
            when {
                houseNumber == null && isHouseNumberToken(token) -> houseNumber = token
                houseNumber != null && houseSuffix == null && isHouseSuffixToken(token) -> houseSuffix = token
                token != "riga" && token != "latvija" -> streetTokens.add(token)
            }
        }

        return ParsedInput(
            normalizedInput = normalizedInput,
            streetTokens = streetTokens,
            houseNumber = houseNumber,
            houseSuffix = houseSuffix
        )
    }

    private fun buildCandidateTokens(
        official: String,
        aliases: List<String>,
        city: String
    ): List<String> {
        val result = linkedSetOf<String>()

        result.addAll(splitTokens(official))
        result.add(official)

        for (alias in aliases) {
            result.addAll(splitTokens(alias))
            result.add(alias)
        }

        result.addAll(splitTokens(city))
        result.add(city)

        return result.filter { it.isNotBlank() }
    }

    private fun splitTokens(value: String): List<String> {
        return value
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }
            .filterNot { isHouseNumberToken(it) }
            .filterNot { isHouseSuffixToken(it) }
    }

    private fun isHouseNumberToken(token: String): Boolean {
        return token.matches(Regex("""^\d+[a-zA-ZА-Яа-я]?(?:/\d+[a-zA-ZА-Яа-я]?)?$"""))
    }

    private fun isHouseSuffixToken(token: String): Boolean {
        return token == "k" ||
            token.matches(Regex("""^k\d+[a-zA-ZА-Яа-я]?$""")) ||
            token.matches(Regex("""^\d+[a-zA-ZА-Яа-я]?$"""))
    }

    private fun normalizeFreeText(value: String): String {
        return value
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
            .replace("-", " ")
            .replace(",", " ")
            .replace(".", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        return levenshtein(a, b)
    }

    private fun similarityPercent(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100

        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0

        val distance = levenshtein(a, b)
        val percent = ((maxLen - distance).toDouble() / maxLen.toDouble()) * 100.0
        return percent.toInt().coerceIn(0, 100)
    }

    private fun normalizeCity(city: String): String {
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

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[a.length][b.length]
    }
}
