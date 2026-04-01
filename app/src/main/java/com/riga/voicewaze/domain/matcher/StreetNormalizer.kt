package com.riga.voicewaze.domain.matcher

class StreetNormalizer {

    fun normalizeUserInput(input: String): String {
        var s = input.lowercase().trim()

        s = s.replace(",", " ")
        s = s.replace("\\s+".toRegex(), " ")

        // Сначала устойчивые сочетания, чтобы не ломать порядок замен
        s = s
            .replace("йе", "ie")
            .replace("ье", "ie")
            .replace("ие", "ie")
            .replace("йо", "io")
            .replace("ио", "io")
            .replace("дж", "dzh")
            .replace("ж", "zh")
            .replace("ш", "sh")
            .replace("щ", "sh")
            .replace("ч", "c")
            .replace("ц", "c")
            .replace("ю", "iu")
            .replace("я", "ia")

        s = s
            .replace("а", "a")
            .replace("б", "b")
            .replace("в", "v")
            .replace("г", "g")
            .replace("д", "d")
            .replace("е", "e")
            .replace("ё", "e")
            .replace("з", "z")
            .replace("и", "i")
            .replace("й", "i")
            .replace("к", "k")
            .replace("л", "l")
            .replace("м", "m")
            .replace("н", "n")
            .replace("о", "o")
            .replace("п", "p")
            .replace("р", "r")
            .replace("с", "s")
            .replace("т", "t")
            .replace("у", "u")
            .replace("ф", "f")
            .replace("х", "h")
            .replace("ы", "i")
            .replace("э", "e")
            .replace("ъ", "")
            .replace("ь", "")

        return simplifyPhonetics(s)
    }

    fun normalizeStreetName(input: String): String {
        var s = input.lowercase().trim()

        s = s.replace(",", " ")
        s = s.replace("\\s+".toRegex(), " ")

        s = s
            .replace("ā", "a")
            .replace("č", "c")
            .replace("ē", "e")
            .replace("ģ", "g")
            .replace("ī", "i")
            .replace("ķ", "k")
            .replace("ļ", "l")
            .replace("ņ", "n")
            .replace("š", "sh")
            .replace("ū", "u")
            .replace("ž", "zh")

        return simplifyPhonetics(s)
    }

    private fun simplifyPhonetics(source: String): String {
        var s = source

        // Убираем служебные слова адреса
        s = s
            .replace("iela", " ")
            .replace("iela.", " ")
            .replace("prospekts", " ")
            .replace("bulvaris", " ")
            .replace("bulvāris", " ")
            .replace("gatve", " ")
            .replace("laukums", " ")

        // Ключевые фонетические выравнивания
        s = s
            .replace("ie", "i")
            .replace("iu", "u")
            .replace("ia", "a")
            .replace("io", "o")
            .replace("ye", "i")
            .replace("je", "i")
            .replace("ya", "a")
            .replace("yu", "u")

        // Чтобы "йеритю" и "ieriķu" сходились к одной форме
        s = s
            .replace("tiu", "ku")
            .replace("tiu", "ku")
            .replace("tyu", "ku")
            .replace("tu", "ku")
            .replace("tju", "ku")

        // Упрощение трудных сочетаний
        s = s
            .replace("rksh", "rksk")
            .replace("rkšķ", "rksk")
            .replace("ksh", "ksk")
            .replace("sh", "s")
            .replace("zh", "z")
            .replace("dzh", "z")
            .replace("kh", "h")

        // Схлопывание повторов
        s = s
            .replace("aa", "a")
            .replace("ee", "e")
            .replace("ii", "i")
            .replace("oo", "o")
            .replace("uu", "u")
            .replace("kk", "k")
            .replace("ll", "l")
            .replace("rr", "r")
            .replace("ss", "s")
            .replace("nn", "n")

        s = s.replace("[^a-z0-9 ]".toRegex(), " ")
        s = s.replace("\\s+".toRegex(), " ").trim()

        return s
    }
}