package com.riga.voicewaze.domain.matcher

import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.data.model.StreetEntry
import java.util.Locale

class StreetMatcher(
    private val repository: StreetRepository
) {

    private val normalizer = StreetNormalizer()
    private val minStreetsPerCity = 50
    private val defaultCity = "Rīga"

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

    private val knownNormalizedCities: Set<String> by lazy {
        indexedEntries.map { it.normalizedCity }.toSet()
    }

    fun findBestMatch(input: String): String {
        return findBestMatchDetailed(input, null).street
    }

    fun findBestMatchDetailed(
        input: String,
        preferredCity: String?
    ): StreetMatchResult {
        val normalizedInput = normalizer.normalizeUserInput(input)

        val effectiveCity = resolveEffectiveCity(normalizedInput, preferredCity)

        if (normalizedInput.isBlank()) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(effectiveCity),
                score = Int.MAX_VALUE,
                matchPercent = 0,
                isConfident = false
            )
        }

        val searchPool = buildSearchPool(normalizedInput, preferredCity)

        var best: CandidateScore? = null

        for (candidate in searchPool) {
            val candidateScore = calculateCandidateScore(normalizedInput, candidate)

            if (best == null) {
                best = candidateScore
                continue
            }

            if (candidateScore.percent > best.percent) {
                best = candidateScore
            } else if (candidateScore.percent == best.percent) {
                if (candidateScore.score < best.score) {
                    best = candidateScore
                } else if (
                    candidateScore.score == best.score &&
                    candidateScore.indexedStreet.streetEntry.priority >
                    best.indexedStreet.streetEntry.priority
                ) {
                    best = candidateScore
                }
            }
        }

        val winner = best
        if (winner == null) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(effectiveCity),
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
        val normalizedInput = normalizer.normalizeUserInput(input)

        if (normalizedInput.isBlank()) {
            return emptyList()
        }

        val searchPool = buildSearchPool(normalizedInput, preferredCity)

        val candidates = searchPool.map { candidate ->
            calculateCandidateScore(normalizedInput, candidate)
        }

        return candidates
            .sortedWith(
                compareByDescending<CandidateScore> { it.percent }
                    .thenBy { it.score }
                    .thenByDescending { it.indexedStreet.streetEntry.priority }
                    .thenBy { it.indexedStreet.streetEntry.official }
            )
            .take(limit.coerceAtLeast(1))
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
        val normalizedRaw = normalizeFreeText(input)
        if (normalizedRaw.length < 5) {
            return emptyList()
        }

        val queryTokens = tokenizeTypedInput(normalizedRaw)
        if (queryTokens.isEmpty()) {
            return emptyList()
        }

        val searchPool = buildSearchPool(normalizedRaw, null)

        val candidates = searchPool.map { candidate ->
            val percent = calculateTypedInputPercent(queryTokens, candidate)
            val score = 100 - percent

            CandidateScore(
                indexedStreet = candidate,
                score = score,
                percent = percent
            )
        }

        return candidates
            .sortedWith(
                compareByDescending<CandidateScore> { it.percent }
                    .thenBy { it.score }
                    .thenByDescending { it.indexedStreet.streetEntry.priority }
                    .thenBy { it.indexedStreet.streetEntry.official }
            )
            .take(limit.coerceAtLeast(1))
            .map { candidate ->
                AddressSuggestion(
                    street = candidate.indexedStreet.streetEntry.official,
                    city = candidate.indexedStreet.streetEntry.city,
                    matchPercent = candidate.percent,
                    score = candidate.score
                )
            }
    }

    private fun buildSearchPool(
        normalizedInput: String,
        preferredCity: String?
    ): List<IndexedStreet> {
        val effectiveCity = resolveEffectiveCity(normalizedInput, preferredCity)
        val cityFiltered = indexedEntries.filter { it.normalizedCity == effectiveCity }
        return if (cityFiltered.isNotEmpty()) cityFiltered else indexedEntries
    }

    private fun resolveEffectiveCity(
        normalizedInput: String,
        preferredCity: String?
    ): String {
        detectExplicitCityFromInput(normalizedInput)?.let { return it }

        preferredCity
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeCity(it) }
            ?.let { return it }

        return normalizeCity(defaultCity)
    }

    private fun detectExplicitCityFromInput(normalizedInput: String): String? {
        if (normalizedInput.isBlank()) return null

        val paddedInput = " ${normalizeFreeText(normalizedInput)} "

        return knownNormalizedCities
            .sortedByDescending { it.length }
            .firstOrNull { city ->
                city.isNotBlank() && paddedInput.contains(" $city ")
            }
    }

    private fun denormalizeCity(normalizedCity: String): String {
        return indexedEntries.firstOrNull { it.normalizedCity == normalizedCity }
            ?.streetEntry
            ?.city
            ?: defaultCity
    }

    private fun calculateCandidateScore(
        normalizedInput: String,
        item: IndexedStreet
    ): CandidateScore {
        var bestScore = distance(normalizedInput, item.normalizedOfficial)
        var bestPercent = similarityPercent(normalizedInput, item.normalizedOfficial)

        for (alias in item.normalizedAliases) {
            val aliasScore = distance(normalizedInput, alias)
            val aliasPercent = similarityPercent(normalizedInput, alias)

            if (aliasPercent > bestPercent) {
                bestPercent = aliasPercent
                bestScore = aliasScore
            } else if (aliasPercent == bestPercent && aliasScore < bestScore) {
                bestScore = aliasScore
            }
        }

        return CandidateScore(
            indexedStreet = item,
            score = bestScore,
            percent = bestPercent
        )
    }

    private fun calculateTypedInputPercent(
        queryTokens: List<String>,
        candidate: IndexedStreet
    ): Int {
        if (queryTokens.isEmpty()) return 0

        var tokenAverage = 0.0
        var cityMatched = false
        var exactStreetTokenMatches = 0
        var strongStreetTokenMatches = 0

        for (queryToken in queryTokens) {
            var bestForToken = 0

            for (candidateToken in candidate.candidateTokens) {
                val percent = similarityPercent(queryToken, candidateToken)
                if (percent > bestForToken) {
                    bestForToken = percent
                }
            }

            tokenAverage += bestForToken.toDouble()

            if (similarityPercent(queryToken, candidate.normalizedCity) >= 90) {
                cityMatched = true
            }

            val bestOfficialTokenPercent = candidate.officialTokens.maxOfOrNull {
                similarityPercent(queryToken, it)
            } ?: 0

            val bestAliasTokenPercent = candidate.aliasTokenGroups
                .flatten()
                .maxOfOrNull { similarityPercent(queryToken, it) } ?: 0

            val bestStreetTokenPercent = maxOf(bestOfficialTokenPercent, bestAliasTokenPercent)

            if (bestStreetTokenPercent >= 95) {
                exactStreetTokenMatches++
            }
            if (bestStreetTokenPercent >= 85) {
                strongStreetTokenMatches++
            }
        }

        var finalPercent = (tokenAverage / queryTokens.size.toDouble()).toInt()

        val firstQueryToken = queryTokens.firstOrNull().orEmpty()
        val firstStreetTokenPercent = bestStreetTokenPercent(firstQueryToken, candidate)

        when {
            firstStreetTokenPercent >= 98 -> finalPercent += 18
            firstStreetTokenPercent >= 95 -> finalPercent += 15
            firstStreetTokenPercent >= 90 -> finalPercent += 12
            firstStreetTokenPercent >= 85 -> finalPercent += 8
            firstStreetTokenPercent >= 75 -> finalPercent += 2
            else -> finalPercent -= 18
        }

        if (strongStreetTokenMatches == queryTokens.size) {
            finalPercent += 10
        } else if (strongStreetTokenMatches >= 2) {
            finalPercent += 6
        }

        if (exactStreetTokenMatches >= 2) {
            finalPercent += 6
        } else if (exactStreetTokenMatches == 1) {
            finalPercent += 3
        }

        if (queryTokens.size >= 2 && cityMatched && strongStreetTokenMatches >= 1) {
            finalPercent += 6
        }

        if (queryTokens.size == 1 && firstStreetTokenPercent < 80) {
            finalPercent -= 10
        }

        return finalPercent.coerceIn(0, 100)
    }

    private fun bestStreetTokenPercent(
        queryToken: String,
        candidate: IndexedStreet
    ): Int {
        if (queryToken.isBlank()) return 0

        val officialBest = candidate.officialTokens.maxOfOrNull {
            similarityPercent(queryToken, it)
        } ?: 0

        val aliasBest = candidate.aliasTokenGroups
            .flatten()
            .maxOfOrNull { similarityPercent(queryToken, it) } ?: 0

        return maxOf(officialBest, aliasBest)
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

    private fun tokenizeTypedInput(input: String): List<String> {
        return input
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }
            .filterNot { isHouseNumberToken(it) }
            .filterNot { isHouseSuffixToken(it) }
    }

    private fun splitTokens(value: String): List<String> {
        return value
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }
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
