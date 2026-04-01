package com.riga.voicewaze.domain.matcher

import com.riga.voicewaze.data.local.StreetRepository

class StreetMatcher(
    private val repository: StreetRepository
) {

    fun findBestMatch(input: String): String {
        val streets = repository.getKnownStreets()
        if (streets.isEmpty()) {
            return "Nezināma"
        }

        val normalizedInput = input.lowercase().trim()

        var bestMatch = "Nezināma"
        var bestScore = Int.MAX_VALUE

        for (street in streets) {
            val score = levenshtein(normalizedInput, street.lowercase())

            if (score < bestScore) {
                bestScore = score
                bestMatch = street
            }
        }

        return bestMatch
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