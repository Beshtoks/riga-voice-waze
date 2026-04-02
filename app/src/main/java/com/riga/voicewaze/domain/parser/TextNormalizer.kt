package com.riga.voicewaze.domain.parser

object TextNormalizer {

    private val simpleNumbers = mapOf(
        "viens" to "1",
        "vienu" to "1",
        "divi" to "2",
        "divas" to "2",
        "trīs" to "3",
        "tris" to "3",
        "četri" to "4",
        "cetri" to "4",
        "pieci" to "5",
        "seši" to "6",
        "sesi" to "6",
        "septiņi" to "7",
        "septini" to "7",
        "astoņi" to "8",
        "astoni" to "8",
        "deviņi" to "9",
        "devini" to "9",
        "desmit" to "10"
    )

    fun normalize(input: String): String {
        var text = input.lowercase()

        text = text
            .replace(",", " ")
            .replace(".", " ")
            .replace(";", " ")
            .replace(":", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        if (text.isBlank()) {
            return ""
        }

        val tokens = text.split(" ")
            .filter { it.isNotBlank() }
            .map { token ->
                simpleNumbers[token] ?: token
            }

        return tokens.joinToString(" ").trim()
    }
}