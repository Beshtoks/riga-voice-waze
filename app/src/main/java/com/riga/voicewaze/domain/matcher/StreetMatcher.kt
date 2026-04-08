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
        val officialTokens: List<String>,
        val aliasTokenGroups: List<List<String>>,
        val officialType: String,
        val officialOriginalTokens: List<String>
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

    private data class TokenScore(
        val rawScore: Int,
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

                IndexedStreet(
                    streetEntry = entry,
                    normalizedOfficial = normalizedOfficial,
                    normalizedAliases = normalizedAliases,
                    normalizedCity = normalizedCity,
                    officialTokens = splitTokens(normalizedOfficial),
                    aliasTokenGroups = normalizedAliases.map { splitTokens(it) },
                    officialType = extractStreetType(entry.official),
                    officialOriginalTokens = splitOriginalTokens(entry.official)
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
        val officialScore = calculateTokenScore(
            queryTokens = parsedInput.streetTokens,
            candidateStreetTokens = item.officialTokens,
            officialOriginalTokens = item.officialOriginalTokens,
            streetType = item.officialType,
            officialPriority = item.streetEntry.priority,
            isOfficialName = true,
            hasHouseNumber = parsedInput.houseNumber != null,
            hasHouseSuffix = parsedInput.houseSuffix != null
        )

        val aliasScores = item.aliasTokenGroups.map { aliasTokens ->
            calculateTokenScore(
                queryTokens = parsedInput.streetTokens,
                candidateStreetTokens = aliasTokens,
                officialOriginalTokens = item.officialOriginalTokens,
                streetType = item.officialType,
                officialPriority = item.streetEntry.priority,
                isOfficialName = false,
                hasHouseNumber = parsedInput.houseNumber != null,
                hasHouseSuffix = parsedInput.houseSuffix != null
            )
        }

        val best = (aliasScores + officialScore)
            .maxWithOrNull(compareBy<TokenScore> { it.rawScore }.thenBy { it.percent })
            ?: TokenScore(rawScore = Int.MIN_VALUE / 2, percent = 0)

        return CandidateScore(
            indexedStreet = item,
            score = 10_000 - best.rawScore,
            percent = best.percent.coerceIn(0, 100)
        )
    }

    private fun calculateTokenScore(
        queryTokens: List<String>,
        candidateStreetTokens: List<String>,
        officialOriginalTokens: List<String>,
        streetType: String,
        officialPriority: Int,
        isOfficialName: Boolean,
        hasHouseNumber: Boolean,
        hasHouseSuffix: Boolean
    ): TokenScore {
        if (queryTokens.isEmpty() || candidateStreetTokens.isEmpty()) {
            return TokenScore(rawScore = Int.MIN_VALUE / 2, percent = 0)
        }

        val queryPhrase = queryTokens.joinToString(" ")
        val candidatePhrase = candidateStreetTokens.joinToString(" ")
        val originalBasePhrase = officialOriginalTokens.joinToString(" ")

        var rawScore = 0
        var percent = 0

        val firstQueryToken = queryTokens.first()
        val firstCandidateToken = candidateStreetTokens.first()
        val firstOriginalToken = officialOriginalTokens.firstOrNull().orEmpty()

        val firstTokenSimilarity = tokenSimilarity(firstQueryToken, firstCandidateToken)
        rawScore += firstTokenSimilarity * 6
        percent += (firstTokenSimilarity * 0.34).toInt()

        if (firstCandidateToken == firstQueryToken) {
            rawScore += 220
            percent += 14
        } else if (firstCandidateToken.startsWith(firstQueryToken)) {
            rawScore += if (firstQueryToken.length >= 4) 180 else 120
            percent += if (firstQueryToken.length >= 4) 12 else 8
        }

        if (firstOriginalToken == firstQueryToken) {
            rawScore += 70
            percent += 4
        } else if (firstOriginalToken.startsWith(firstQueryToken)) {
            rawScore += if (firstQueryToken.length >= 4) 55 else 30
            percent += if (firstQueryToken.length >= 4) 4 else 2
        }

        var sequentialStrongMatches = 0
        queryTokens.forEachIndexed { index, queryToken ->
            val bestIndexed = candidateStreetTokens
                .mapIndexed { candidateIndex, candidateToken ->
                    candidateIndex to tokenSimilarity(queryToken, candidateToken)
                }
                .maxByOrNull { it.second }

            val bestIndex = bestIndexed?.first ?: 0
            val bestSimilarity = bestIndexed?.second ?: 0

            rawScore += bestSimilarity * 2
            percent += (bestSimilarity * 0.12).toInt()

            if (index == bestIndex) {
                rawScore += 50
                percent += 3
            } else {
                rawScore -= bestIndex * 45
            }

            if (bestSimilarity >= 90) {
                sequentialStrongMatches++
            }
        }

        if (sequentialStrongMatches == queryTokens.size) {
            rawScore += 90
            percent += 6
        } else if (sequentialStrongMatches >= 1) {
            rawScore += 30
            percent += 2
        }

        val phraseSimilarity = similarityPercent(queryPhrase, candidatePhrase)
        rawScore += phraseSimilarity * 3
        percent += (phraseSimilarity * 0.12).toInt()

        val originalPhraseSimilarity = if (originalBasePhrase.isBlank()) 0 else similarityPercent(queryPhrase, originalBasePhrase)
        rawScore += originalPhraseSimilarity

        rawScore -= distance(queryPhrase, candidatePhrase) * 8
        rawScore -= (candidateStreetTokens.size - queryTokens.size).coerceAtLeast(0) * 18

        if (isOfficialName) {
            rawScore += 28
            percent += 2
        }

        rawScore += streetTypeWeight(streetType)
        percent += streetTypePercentBonus(streetType)

        rawScore += officialPriority.coerceIn(0, 300) / 2
        percent += (officialPriority.coerceIn(0, 200) / 50)

        if (hasHouseNumber) {
            rawScore += 18
            percent += 2
        }
        if (hasHouseSuffix) {
            rawScore += 10
            percent += 1
        }

        return TokenScore(
            rawScore = rawScore,
            percent = percent.coerceIn(0, 100)
        )
    }

    private fun tokenSimilarity(queryToken: String, candidateToken: String): Int {
        return when {
            queryToken == candidateToken -> 100
            candidateToken.startsWith(queryToken) -> when {
                queryToken.length >= candidateToken.length -> 97
                queryToken.length >= 4 -> 95
                queryToken.length == 3 -> 88
                else -> 82
            }
            queryToken.startsWith(candidateToken) -> 72
            candidateToken.contains(queryToken) -> 58
            else -> similarityPercent(queryToken, candidateToken)
        }
    }

    private fun streetTypeWeight(streetType: String): Int {
        return when (streetType) {
            "iela" -> 120
            "gatve" -> 72
            "bulvaris", "bulvāris" -> 54
            "prospekts" -> 50
            "laukums" -> 36
            "aleja" -> 28
            "krastmala" -> 24
            else -> 16
        }
    }

    private fun streetTypePercentBonus(streetType: String): Int {
        return when (streetType) {
            "iela" -> 10
            "gatve" -> 6
            "bulvaris", "bulvāris" -> 4
            "prospekts" -> 4
            "laukums" -> 3
            "aleja" -> 2
            else -> 1
        }
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

    private fun splitOriginalTokens(value: String): List<String> {
        val normalized = normalizeFreeText(value)
        val tokens = normalized
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return emptyList()

        val streetType = extractStreetType(value)
        return if (streetType.isNotBlank() && tokens.lastOrNull() == streetType) {
            tokens.dropLast(1)
        } else {
            tokens
        }
    }

    private fun extractStreetType(value: String): String {
        val tokens = normalizeFreeText(value)
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val last = tokens.lastOrNull().orEmpty()
        return when (last) {
            "iela", "gatve", "prospekts", "bulvaris", "laukums", "krastmala", "aleja" -> last
            else -> ""
        }
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