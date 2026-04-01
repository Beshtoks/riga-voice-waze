package com.riga.voicewaze.domain.parser

class AddressParser {

    fun parse(input: String): ParsedAddress {
        val text = TextNormalizer.normalize(input)
        val words = text.split(" ").filter { it.isNotBlank() }.toMutableList()

        var city = ""
        var houseNumber: String? = null
        var корпус: String? = null

        val numberIndex = words.indexOfFirst { it.matches(Regex("\\d+[a-zA-Z]?")) }
        if (numberIndex != -1) {
            houseNumber = words[numberIndex].uppercase()
            words.removeAt(numberIndex)
        }

        val cityIndex = words.indexOfFirst { isCityWord(it) }
        if (cityIndex != -1) {
            city = normalizeLatvianCity(words[cityIndex])
            words.removeAt(cityIndex)
        }

        val street = words.joinToString(" ").trim()

        return ParsedAddress(
            streetRaw = street,
            houseNumber = houseNumber,
            корпус = корпус,
            cityRaw = city
        )
    }

    private fun isCityWord(word: String): Boolean {
        val w = word.lowercase()

        return when {
            w.startsWith("rīg") -> true
            w.startsWith("jūrmal") -> true
            w.startsWith("daugavpil") -> true
            w.startsWith("liepāj") -> true
            w.startsWith("jelgav") -> true
            else -> false
        }
    }

    private fun normalizeLatvianCity(city: String): String {
        val c = city.lowercase()

        return when {
            c.startsWith("rīg") -> "Rīga"
            c.startsWith("jūrmal") -> "Jūrmala"
            c.startsWith("daugavpil") -> "Daugavpils"
            c.startsWith("liepāj") -> "Liepāja"
            c.startsWith("jelgav") -> "Jelgava"
            else -> city.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}