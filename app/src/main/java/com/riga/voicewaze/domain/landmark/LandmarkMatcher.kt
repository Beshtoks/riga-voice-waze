package com.riga.voicewaze.domain.landmark

import com.riga.voicewaze.data.local.LandmarkRepository

class LandmarkMatcher(
    private val repository: LandmarkRepository
) {

    fun findBestMatch(input: String): LandmarkMatchResult {
        val normalizedInput = normalize(input)

        if (normalizedInput.isBlank()) {
            return LandmarkMatchResult(
                name = "",
                address = "",
                matchPercent = 0,
                isConfident = false
            )
        }

        val entries = repository.getAll()

        var bestEntry: LandmarkEntry? = null
        var bestPercent = -1
        var bestScore = Int.MAX_VALUE

        for (entry in entries) {
            val normalizedName = normalize(entry.name)
            val score = levenshtein(normalizedInput, normalizedName)
            val percent = similarityPercent(normalizedInput, normalizedName)

            if (percent > bestPercent) {
                bestPercent = percent
                bestScore = score
                bestEntry = entry
            } else if (percent == bestPercent && score < bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        if (bestEntry == null) {
            return LandmarkMatchResult(
                name = "",
                address = "",
                matchPercent = 0,
                isConfident = false
            )
        }

        return LandmarkMatchResult(
            name = bestEntry.name,
            address = bestEntry.address,
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
