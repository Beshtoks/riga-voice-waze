package com.riga.voicewaze.domain.matcher

import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.data.model.StreetEntry
import kotlin.math.abs

class StreetMatcher(
    private val repository: StreetRepository
) {

    private val normalizer = StreetNormalizer()

    private data class IndexedStreet(
        val entry: StreetEntry,
        val normalizedOfficial: String,
        val normalizedAliases: List<String>,
        val normalizedCity: String
    )

    private val indexedEntries: List<IndexedStreet> by lazy {
        repository.getAllStreetEntries().map { entry ->
            IndexedStreet(
                entry = entry,
                normalizedOfficial = normalizer.normalizeStreetName(entry.official),
                normalizedAliases = entry.aliases.map { alias ->
                    normalizer.normalizeUserInput(alias)
                },
                normalizedCity = normalizeCity(entry.city)
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
        val normalizedInput = normalizer.normalizeUserInput(input)
        val targetCity = normalizePreferredCity(preferredCity)

        if (normalizedInput.isBlank()) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(targetCity),
                score = Int.MAX_VALUE,
                isConfident = false
            )
        }

        val cityEntries = indexedEntries.filter { it.normalizedCity == targetCity }

        if (cityEntries.isEmpty()) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(targetCity),
                score = Int.MAX_VALUE,
                isConfident = false
            )
        }

        val exactAliasHit = cityEntries.firstOrNull { item ->
            item.normalizedAliases.any { it == normalizedInput }
        }
        if (exactAliasHit != null) {
            return StreetMatchResult(
                street = exactAliasHit.entry.official,
                city = exactAliasHit.entry.city,
                score = 0,
                isConfident = true
            )
        }

        val exactOfficialHit = cityEntries.firstOrNull { item ->
            item.normalizedOfficial == normalizedInput
        }
        if (exactOfficialHit != null) {
            return StreetMatchResult(
                street = exactOfficialHit.entry.official,
                city = exactOfficialHit.entry.city,
                score = 0,
                isConfident = true
            )
        }

        val candidates = buildCandidateList(normalizedInput, cityEntries)
        if (candidates.isEmpty()) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(targetCity),
                score = Int.MAX_VALUE,
                isConfident = false
            )
        }

        var best: IndexedStreet? = null
        var bestScore = Int.MAX_VALUE

        for (candidate in candidates) {
            val score = calculateEntryScore(
                normalizedInput = normalizedInput,
                item = candidate
            )

            if (score < bestScore) {
                bestScore = score
                best = candidate
            } else if (score == bestScore && best != null && candidate.entry.priority > best.entry.priority) {
                best = candidate
            }
        }

        val winner = best
        if (winner == null) {
            return StreetMatchResult(
                street = "Nezināma",
                city = denormalizeCity(targetCity),
                score = Int.MAX_VALUE,
                isConfident = false
            )
        }

        return StreetMatchResult(
            street = winner.entry.official,
            city = winner.entry.city,
            score = bestScore,
            isConfident = isConfidentMatch(
                normalizedInput = normalizedInput,
                item = winner,
                bestScore = bestScore
            )
        )
    }

    private fun buildCandidateList(
        normalizedInput: String,
        cityEntries: List<IndexedStreet>
    ): List<IndexedStreet> {
        val p2 = normalizedInput.take(2)
        val p1 = normalizedInput.take(1)

        var base = cityEntries.filter { it.normalizedOfficial.startsWith(p2) }

        if (base.isEmpty() && p1.isNotBlank()) {
            base = cityEntries.filter { it.normalizedOfficial.startsWith(p1) }
        }

        if (base.isEmpty()) {
            base = cityEntries
        }

        val filtered = base.filter { item ->
            isFastCompatible(normalizedInput, item)
        }

        return if (filtered.isNotEmpty()) filtered else base
    }

    private fun isFastCompatible(
        normalizedInput: String,
        item: IndexedStreet
    ): Boolean {
        val inputLen = normalizedInput.length
        val official = item.normalizedOfficial

        if (official.contains(normalizedInput) || normalizedInput.contains(official)) {
            return true
        }

        if (abs(official.length - inputLen) <= maxOf(3, inputLen / 3)) {
            return true
        }

        for (alias in item.normalizedAliases) {
            if (alias.contains(normalizedInput) || normalizedInput.contains(alias)) {
                return true
            }
            if (abs(alias.length - inputLen) <= maxOf(3, inputLen / 3)) {
                return true
            }
        }

        return false
    }

    private fun calculateEntryScore(
        normalizedInput: String,
        item: IndexedStreet
    ): Int {
        var best = scorePair(normalizedInput, item.normalizedOfficial)

        for (alias in item.normalizedAliases) {
            val aliasScore = scorePair(normalizedInput, alias)
            if (aliasScore < best) {
                best = aliasScore
            }
            if (best == 0) {
                break
            }
        }

        val priorityBonus = when {
            item.entry.priority >= 100 -> 2
            item.entry.priority >= 80 -> 1
            else -> 0
        }

        return (best - priorityBonus).coerceAtLeast(0)
    }

    private fun scorePair(input: String, candidate: String): Int {
        if (input == candidate) {
            return 0
        }

        if (candidate.contains(input) || input.contains(candidate)) {
            return 1
        }

        val lenDiffPenalty = abs(input.length - candidate.length)
        return levenshtein(input, candidate) + lenDiffPenalty
    }

    private fun isConfidentMatch(
        normalizedInput: String,
        item: IndexedStreet,
        bestScore: Int
    ): Boolean {
        if (item.entry.official == "Nezināma") {
            return false
        }

        if (bestScore <= 1) {
            return true
        }

        if (item.normalizedOfficial.contains(normalizedInput) || normalizedInput.contains(item.normalizedOfficial)) {
            return true
        }

        for (alias in item.normalizedAliases) {
            if (alias == normalizedInput) {
                return true
            }
            if (alias.contains(normalizedInput) || normalizedInput.contains(alias)) {
                return true
            }
        }

        val maxLen = maxOf(normalizedInput.length, item.normalizedOfficial.length)
        if (maxLen == 0) {
            return false
        }

        val ratio = bestScore.toDouble() / maxLen.toDouble()
        return ratio <= 0.20
    }

    private fun normalizePreferredCity(city: String?): String {
        if (city.isNullOrBlank()) {
            return "riga"
        }
        return normalizeCity(city)
    }

    private fun normalizeCity(city: String): String {
        return city
            .lowercase()
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

    private fun denormalizeCity(city: String): String {
        return when (city) {
            "riga" -> "Rīga"
            "jurmala" -> "Jūrmala"
            "liepaja" -> "Liepāja"
            "daugavpils" -> "Daugavpils"
            else -> city
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) {
            dp[i][0] = i
        }

        for (j in 0..b.length) {
            dp[0][j] = j
        }

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