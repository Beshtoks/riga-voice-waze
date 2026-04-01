package com.riga.voicewaze.domain.matcher

class StreetNormalizer {

    fun normalizeUserInput(input: String): String {
        var s = input.lowercase().trim()

        s = s.replace("ё", "е")
        s = s.replace("й", "и")
        s = s.replace("ъ", "")
        s = s.replace("ь", "")

        s = s.replace("щ", "ш")
        s = s.replace("ш", "sh")
        s = s.replace("ч", "ch")
        s = s.replace("ж", "zh")
        s = s.replace("ц", "c")
        s = s.replace("х", "h")
        s = s.replace("ю", "ju")
        s = s.replace("я", "ja")

        s = s.replace("а", "a")
        s = s.replace("б", "b")
        s = s.replace("в", "v")
        s = s.replace("г", "g")
        s = s.replace("д", "d")
        s = s.replace("е", "e")
        s = s.replace("з", "z")
        s = s.replace("и", "i")
        s = s.replace("к", "k")
        s = s.replace("л", "l")
        s = s.replace("м", "m")
        s = s.replace("н", "n")
        s = s.replace("о", "o")
        s = s.replace("п", "p")
        s = s.replace("р", "r")
        s = s.replace("с", "s")
        s = s.replace("т", "t")
        s = s.replace("у", "u")
        s = s.replace("ф", "f")
        s = s.replace("ы", "i")
        s = s.replace("э", "e")

        s = cleanup(s)

        return s
    }

    fun normalizeStreetName(input: String): String {
        var s = input.lowercase().trim()

        s = s.replace("iela", "")
        s = s.replace("ā", "a")
        s = s.replace("č", "ch")
        s = s.replace("ē", "e")
        s = s.replace("ģ", "g")
        s = s.replace("ī", "i")
        s = s.replace("ķ", "k")
        s = s.replace("ļ", "l")
        s = s.replace("ņ", "n")
        s = s.replace("š", "sh")
        s = s.replace("ū", "u")
        s = s.replace("ž", "zh")

        s = cleanup(s)

        return s
    }

    private fun cleanup(input: String): String {
        return input
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}