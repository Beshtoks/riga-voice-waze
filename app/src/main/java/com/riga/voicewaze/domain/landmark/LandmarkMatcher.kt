package com.riga.voicewaze.domain.landmark

import com.riga.voicewaze.data.local.LandmarkRepository

class LandmarkMatcher(
    repository: LandmarkRepository
) {

    private val entries = repository.getAll()

    fun findBestMatch(input: String): LandmarkMatchResult {
        val normalizedInput = normalize(input)

        if (normalizedInput.isBlank()) {
            return LandmarkMatchResult(
                spokenPhrase = "",
                displayName = "",
                address = "",
                latitude = null,
                longitude = null,
                matchPercent = 0,
                isConfident = false
            )
        }

        var bestEntry: LandmarkEntry? = null
        var bestPercent = -1
        var bestScore = Int.MAX_VALUE

        for (entry in entries) {
            val normalizedPhrase = normalize(entry.spokenPhrase)
            val score = levenshtein(normalizedInput, normalizedPhrase)
            val percent = when {
                normalizedPhrase == normalizedInput -> 100
                normalizedPhrase.startsWith(normalizedInput) -> (80 + normalizedInput.length * 3).coerceAtMost(99)
                normalizedInput.startsWith(normalizedPhrase) -> 75
                else -> similarityPercent(normalizedInput, normalizedPhrase)
            }

            if (percent > bestPercent || (percent == bestPercent && score < bestScore)) {
                bestPercent = percent
                bestScore = score
                bestEntry = entry
            }
        }

        val winner = bestEntry
        if (winner == null) {
            return LandmarkMatchResult(
                spokenPhrase = "",
                displayName = "",
                address = "",
                latitude = null,
                longitude = null,
                matchPercent = 0,
                isConfident = false
            )
        }

        return LandmarkMatchResult(
            spokenPhrase = winner.spokenPhrase,
            displayName = winner.displayName,
            address = winner.address,
            latitude = winner.latitude.takeIf { it != 0.0 },
            longitude = winner.longitude.takeIf { it != 0.0 },
            matchPercent = bestPercent.coerceAtLeast(0),
            isConfident = bestPercent >= 60
        )
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

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace("ё", "е")
            .replace("й", "и")
            .replace("-", "")
            .replace(" ", "")
            .replace(".", "")
            .replace(",", "")
            .trim()
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
