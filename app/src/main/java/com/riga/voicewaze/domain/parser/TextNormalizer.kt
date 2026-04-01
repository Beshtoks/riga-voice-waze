package com.riga.voicewaze.domain.parser

object TextNormalizer {

    fun normalize(input: String): String {
        var text = input.lowercase()

        text = text
            .replace(",", " ")
            .replace(".", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        text = text
            .replace("jurmala", "jūrmala")
            .replace("riga", "rīga")
            .replace("liepaja", "liepāja")

        text = text
            .replace("viens", "1")
            .replace("divi", "2")
            .replace("trīs", "3")
            .replace("tris", "3")
            .replace("četri", "4")
            .replace("cetri", "4")
            .replace("pieci", "5")
            .replace("seši", "6")
            .replace("sesi", "6")
            .replace("septiņi", "7")
            .replace("septini", "7")
            .replace("astoņi", "8")
            .replace("astoni", "8")
            .replace("deviņi", "9")
            .replace("devini", "9")
            .replace("desmit", "10")

        return text.trim()
    }
}