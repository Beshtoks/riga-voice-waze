package com.riga.voicewaze.domain.matcher

class StreetNormalizer {

    fun normalizeUserInput(input: String): String {
        return simplify(input)
    }

    fun normalizeStreetName(input: String): String {
        return simplify(input)
    }

    private fun simplify(source: String): String {
        var s = source.lowercase().trim()

        s = s
            .replace(",", " ")
            .replace(".", " ")
            .replace("\\s+".toRegex(), " ")

        s = s
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

        s = s
            .replace("iela", " ")
            .replace("prospekts", " ")
            .replace("bulvāris", " ")
            .replace("bulvaris", " ")
            .replace("gatve", " ")
            .replace("laukums", " ")
            .replace("krastmala", " ")

        s = s.replace("[^a-z0-9 ]".toRegex(), " ")
        s = s.replace("\\s+".toRegex(), " ").trim()

        return s
    }
}