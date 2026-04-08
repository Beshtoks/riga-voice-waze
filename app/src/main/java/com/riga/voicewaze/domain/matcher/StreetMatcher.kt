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
        val houseSuffix: String?,
        val cityTokens: List<String>,
        val normalizedCityInput: String?
    )

    private data class CityMatch(
        val percent: Int,
        val strongEnoughToFilter: Boolean,
        val mismatchPenalty: Int,
        val scoreBonus: Int
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

        val effectiveCityInput = parsedInput.normalizedCityInput ?: preferredNormalizedCity

        val searchPool = buildSearchPool(
            effectiveCityInput = effectiveCityInput,
            preferredNormalizedCity = preferredNormalizedCity
        )

        return searchPool
            .map { candidate ->
                calculateAddressCandidateScore(
                    parsedInput = parsedInput,
                    preferredNormalizedCity = preferredNormalizedCity,
                    item = candidate
                )
            }
            .sortedWith(
                compareByDescending<CandidateScore> { it.percent }
                    .thenBy { it.score }
                    .thenByDescending { it.indexedStreet.streetEntry.priority }
                    .thenBy { it.indexedStreet.streetEntry.official }
            )
            .take(limit.coerceAtLeast(1))
    }

    private fun buildSearchPool(
        effectiveCityInput: String?,
        preferredNormalizedCity: String?
    ): List<IndexedStreet> {
        if (!effectiveCityInput.isNullOrBlank()) {
            val exactMatches = indexedEntries.filter { it.normalizedCity == effectiveCityInput }
            if (exactMatches.isNotEmpty()) {
                return exactMatches
            }

            val prefixMatches = indexedEntries.filter {
                hasCityPrefixMatch(effectiveCityInput, it.normalizedCity)
            }
            if (prefixMatches.isNotEmpty()) {
                return prefixMatches
            }

            val fuzzyThreshold = cityFilterThreshold(effectiveCityInput)
            val fuzzyMatches = indexedEntries.filter {
                calculateCityPercent(effectiveCityInput, it.normalizedCity) >= fuzzyThreshold
            }
            if (fuzzyMatches.isNotEmpty()) {
                return fuzzyMatches
            }
        }

        if (!preferredNormalizedCity.isNullOrBlank()) {
            val preferredMatches = indexedEntries.filter { it.normalizedCity == preferredNormalizedCity }
            if (preferredMatches.isNotEmpty()) {
                return preferredMatches
            }
        }

        return indexedEntries
    }

    private fun calculateAddressCandidateScore(
        parsedInput: ParsedInput,
        preferredNormalizedCity: String?,
        item: IndexedStreet
    ): CandidateScore {
        val officialPercent = calculateStreetPercent(parsedInput, item.officialTokens)
        val aliasPercents = item.aliasTokenGroups.map { aliasTokens ->
            calculateStreetPercent(parsedInput, aliasTokens)
        }

        val bestStreetPercent = (aliasPercents + officialPercent).maxOrNull() ?: 0

        val phraseDistance = listOf(item.normalizedOfficial, *item.normalizedAliases.toTypedArray())
            .minOf { candidateText ->
                distance(parsedInput.streetTokens.joinToString(" "), candidateText)
            }

        val cityMatch = calculateCityMatch(
            parsedInput = parsedInput,
            preferredNormalizedCity = preferredNormalizedCity,
            candidateCity = item.normalizedCity
        )

        val combinedPercent = combineStreetAndCityPercent(
            streetPercent = bestStreetPercent,
            cityPercent = cityMatch.percent,
            hasExplicitCity = parsedInput.normalizedCityInput != null,
            preferredNormalizedCity = preferredNormalizedCity,
            candidateCity = item.normalizedCity
        )

        val priorityBonus = (item.indexedStreetPrioritySafe() / 20).coerceAtMost(15)

        return CandidateScore(
            indexedStreet = item,
            score = 100 - combinedPercent + phraseDistance + cityMatch.mismatchPenalty - cityMatch.scoreBonus - priorityBonus,
            percent = combinedPercent.coerceIn(0, 100)
        )
    }

    private fun IndexedStreet.indexedStreetPrioritySafe(): Int {
        return streetEntry.priority.coerceAtLeast(0)
    }

    private fun combineStreetAndCityPercent(
        streetPercent: Int,
        cityPercent: Int,
        hasExplicitCity: Boolean,
        preferredNormalizedCity: String?,
        candidateCity: String
    ): Int {
        return when {
            hasExplicitCity -> ((streetPercent * 72) + (cityPercent * 28)) / 100
            !preferredNormalizedCity.isNullOrBlank() && candidateCity == preferredNormalizedCity -> {
                (streetPercent + 4).coerceAtMost(100)
            }
            else -> streetPercent
        }
    }

    private fun calculateCityMatch(
        parsedInput: ParsedInput,
        preferredNormalizedCity: String?,
        candidateCity: String
    ): CityMatch {
        val explicitCity = parsedInput.normalizedCityInput

        if (!explicitCity.isNullOrBlank()) {
            val percent = calculateCityPercent(explicitCity, candidateCity)
            val strongEnoughToFilter = percent >= cityFilterThreshold(explicitCity)
            val mismatchPenalty = when {
                percent >= 90 -> 0
                percent >= 75 -> 2
                percent >= 55 -> 8
                percent >= 35 -> 20
                else -> 40
            }
            val scoreBonus = when {
                percent >= 95 -> 20
                percent >= 85 -> 14
                percent >= 70 -> 8
                percent >= 55 -> 4
                else -> 0
            }
            return CityMatch(
                percent = percent,
                strongEnoughToFilter = strongEnoughToFilter,
                mismatchPenalty = mismatchPenalty,
                scoreBonus = scoreBonus
            )
        }

        if (!preferredNormalizedCity.isNullOrBlank() && candidateCity == preferredNormalizedCity) {
            return CityMatch(
                percent = 100,
                strongEnoughToFilter = true,
                mismatchPenalty = 0,
                scoreBonus = 6
            )
        }

        return CityMatch(
            percent = 0,
            strongEnoughToFilter = false,
            mismatchPenalty = 0,
            scoreBonus = 0
        )
    }

    private fun calculateCityPercent(
        queryCity: String,
        candidateCity: String
    ): Int {
        if (queryCity.isBlank() || candidateCity.isBlank()) return 0
        if (queryCity == candidateCity) return 100

        val queryTokens = splitTokens(queryCity)
        val candidateTokens = splitTokens(candidateCity)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0

        val queryPhrase = queryTokens.joinToString(" ")
        val candidatePhrase = candidateTokens.joinToString(" ")

        var percent = 0

        if (candidatePhrase.startsWith(queryPhrase)) {
            percent += when (queryPhrase.length) {
                1 -> 34
                2 -> 50
                3 -> 66
                4 -> 80
                else -> 92
            }
        } else {
            val firstQuery = queryTokens.first()
            val firstCandidate = candidateTokens.first()
            if (firstCandidate.startsWith(firstQuery)) {
                percent += when (firstQuery.length) {
                    1 -> 28
                    2 -> 44
                    3 -> 58
                    4 -> 72
                    else -> 84
                }
            } else if (candidatePhrase.contains(queryPhrase)) {
                percent += 22
            }
        }

        val exactWordCount = countExactWordMatches(queryTokens, candidateTokens)
        val prefixWordCount = countPrefixWordMatches(queryTokens, candidateTokens)

        if (exactWordCount == queryTokens.size) {
            percent += 26
        } else if (exactWordCount >= 1) {
            percent += 14
        }

        if (prefixWordCount == queryTokens.size) {
            percent += 24
        } else if (prefixWordCount >= 1) {
            percent += 12
        }

        val phraseSimilarity = similarityPercent(queryPhrase, candidatePhrase)
        percent += (phraseSimilarity * 0.22).toInt()

        if (queryPhrase.length == 1 && candidatePhrase.startsWith(queryPhrase)) {
            percent = maxOf(percent, 40)
        }

        return percent.coerceIn(0, 100)
    }

    private fun cityFilterThreshold(queryCity: String): Int {
        return when (queryCity.length) {
            0 -> 101
            1 -> 40
            2 -> 52
            3 -> 62
            4 -> 72
            else -> 80
        }
    }

    private fun hasCityPrefixMatch(
        queryCity: String,
        candidateCity: String
    ): Boolean {
        if (queryCity.isBlank() || candidateCity.isBlank()) return false
        if (candidateCity.startsWith(queryCity)) return true

        val queryTokens = splitTokens(queryCity)
        val candidateTokens = splitTokens(candidateCity)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false

        return candidateTokens.first().startsWith(queryTokens.first())
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
        val rawParts = input.split(",", limit = 2)
        val rawStreetPart = rawParts.firstOrNull().orEmpty()
        val rawCityPart = rawParts.getOrNull(1).orEmpty()

        val normalizedStreetPart = normalizer.normalizeUserInput(rawStreetPart)
        val normalizedCityPart = normalizeCity(rawCityPart)

        val normalizedInput = listOf(normalizedStreetPart, normalizedCityPart)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (normalizedInput.isBlank()) {
            return ParsedInput(
                normalizedInput = "",
                streetTokens = emptyList(),
                houseNumber = null,
                houseSuffix = null,
                cityTokens = emptyList(),
                normalizedCityInput = null
            )
        }

        val streetPartTokens = normalizedStreetPart
            .split(" ")
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }

        val cityTokens = normalizedCityPart
            .split(" ")
            .map { normalizeFreeText(it) }
            .filter { it.isNotBlank() }
            .filterNot { it == "latvija" }

        val streetTokens = mutableListOf<String>()
        var houseNumber: String? = null
        var houseSuffix: String? = null

        for (token in streetPartTokens) {
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
            houseSuffix = houseSuffix,
            cityTokens = cityTokens,
            normalizedCityInput = cityTokens.joinToString(" ").ifBlank { null }
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
