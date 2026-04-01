package com.riga.voicewaze.domain.matcher

import com.riga.voicewaze.data.local.StreetRepository

class StreetMatcher(
    private val repository: StreetRepository
) {

    private val normalizer = StreetNormalizer()

    fun findBestMatch(input: String): String {
        return findBestMatchDetailed(input).street
    }

    fun findBestMatchDetailed(input: String): StreetMatchResult {
        val streets = repository.getKnownStreets()
        if (streets.isEmpty()) {
            return StreetMatchResult(
                street = "Nezināma",
                score = Int.MAX_VALUE,
                isConfident = false
            )
        }

        val normalizedInput = normalizer.normalizeUserInput(input)

        val synonymMatch = StreetSynonyms.find(normalizedInput)
        if (synonymMatch != null) {
            return StreetMatchResult(
                street = synonymMatch,
                score = 0,
                isConfident = true
            )
        }

        var bestStreet = "Nezināma"
        var bestScore = Int.MAX_VALUE

        for (street in streets) {
            val normalizedStreet = normalizer.normalizeStreetName(street)
            val score = calculateScore(normalizedInput, normalizedStreet)

            if (score < bestScore) {
                bestScore = score
                bestStreet = street
            }
        }

        return StreetMatchResult(
            street = bestStreet,
            score = bestScore,
            isConfident = isConfidentMatch(normalizedInput, bestStreet, bestScore)
        )
    }

    private fun isConfidentMatch(
        normalizedInput: String,
        bestStreet: String,
        bestScore: Int
    ): Boolean {
        if (bestStreet == "Nezināma") {
            return false
        }

        val normalizedStreet = normalizer.normalizeStreetName(bestStreet)

        if (bestScore <= 1) {
            return true
        }

        if (normalizedStreet.contains(normalizedInput) || normalizedInput.contains(normalizedStreet)) {
            return true
        }

        val maxLen = maxOf(normalizedInput.length, normalizedStreet.length)
        if (maxLen == 0) {
            return false
        }

        val ratio = bestScore.toDouble() / maxLen.toDouble()

        return ratio <= 0.25
    }

    private fun calculateScore(input: String, street: String): Int {
        if (input == street) {
            return 0
        }

        if (street.contains(input) || input.contains(street)) {
            return 1
        }

        return levenshtein(input, street)
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
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1

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