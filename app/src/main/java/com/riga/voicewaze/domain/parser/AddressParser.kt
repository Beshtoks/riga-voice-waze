package com.riga.voicewaze.domain.parser

class AddressParser {

    private val knownCityAliases = linkedMapOf(
        "рига" to "Rīga",
        "riga" to "Rīga",
        "rīga" to "Rīga",

        "юрмала" to "Jūrmala",
        "jurmala" to "Jūrmala",
        "jūrmala" to "Jūrmala",

        "лиепая" to "Liepāja",
        "liepaja" to "Liepāja",
        "liepāja" to "Liepāja",

        "даугавпилс" to "Daugavpils",
        "daugavpils" to "Daugavpils",

        "елгава" to "Jelgava",
        "jelgava" to "Jelgava",

        "вентспилс" to "Ventspils",
        "ventspils" to "Ventspils",

        "резекне" to "Rēzekne",
        "rezekne" to "Rēzekne",
        "rēzekne" to "Rēzekne",

        "огре" to "Ogre",
        "ogre" to "Ogre",

        "валмиера" to "Valmiera",
        "valmiera" to "Valmiera",

        "марупе" to "Mārupe",
        "marupe" to "Mārupe",
        "mārupe" to "Mārupe"
    )

    fun parse(input: String): ParsedAddress {
        val normalized = input
            .lowercase()
            .trim()
            .replace(",", " ")
            .replace("корпус", "к")
            .replace("корп", "к")
            .replace("\\s+".toRegex(), " ")

        val initialTokens = normalized
            .split(" ")
            .filter { it.isNotBlank() }
            .toMutableList()

        val cityRaw = extractCityAnywhere(initialTokens)

        var houseNumber: String? = null
        var корпус: String? = null
        var streetEndIndex = initialTokens.size

        for (i in initialTokens.indices) {
            val token = initialTokens[i]

            if (houseNumber == null && token.matches(Regex("\\d+[/-]\\d+[a-zа-я]?"))) {
                val parts = token.split("/", "-")
                houseNumber = normalizeHouseToken(parts.getOrNull(0))
                корпус = normalizeCorpusToken(parts.getOrNull(1))
                streetEndIndex = i
                break
            }

            if (houseNumber == null && token.matches(Regex("\\d+[a-zа-я]"))) {
                houseNumber = normalizeHouseToken(token)
                streetEndIndex = i
                break
            }

            if (houseNumber == null && token.matches(Regex("\\d+"))) {
                houseNumber = normalizeHouseToken(token)
                streetEndIndex = i

                if (i + 1 < initialTokens.size) {
                    val next = initialTokens[i + 1]

                    if (next.matches(Regex("[a-zа-я]"))) {
                        houseNumber = normalizeHouseToken(token + next)
                        break
                    }

                    if (next == "к" && i + 2 < initialTokens.size) {
                        val corpusToken = initialTokens[i + 2]
                        if (corpusToken.matches(Regex("\\d+[a-zа-я]?"))) {
                            корпус = normalizeCorpusToken(corpusToken)
                            break
                        }
                    }

                    if (next.matches(Regex("к\\d+[a-zа-я]?"))) {
                        корпус = normalizeCorpusToken(next.removePrefix("к"))
                        break
                    }

                    if (next.matches(Regex("[/-]\\d+[a-zа-я]?"))) {
                        корпус = normalizeCorpusToken(next.removePrefix("/").removePrefix("-"))
                        break
                    }

                    if ((next == "-" || next == "/") && i + 2 < initialTokens.size) {
                        val afterSeparator = initialTokens[i + 2]
                        if (afterSeparator.matches(Regex("\\d+[a-zа-я]?"))) {
                            корпус = normalizeCorpusToken(afterSeparator)
                            break
                        }
                    }
                }

                break
            }
        }

        val streetRaw = if (streetEndIndex in 1..initialTokens.size) {
            initialTokens.subList(0, streetEndIndex).joinToString(" ")
        } else {
            initialTokens.joinToString(" ")
        }

        return ParsedAddress(
            streetRaw = streetRaw,
            houseNumber = houseNumber,
            корпус = корпус,
            cityRaw = cityRaw
        )
    }

    private fun extractCityAnywhere(tokens: MutableList<String>): String? {
        if (tokens.isEmpty()) {
            return null
        }

        for (i in tokens.indices) {
            val oneWord = tokens[i]
            knownCityAliases[oneWord]?.let { city ->
                tokens.removeAt(i)
                return city
            }
        }

        if (tokens.size >= 2) {
            for (i in 0 until tokens.size - 1) {
                val twoWords = "${tokens[i]} ${tokens[i + 1]}"
                knownCityAliases[twoWords]?.let { city ->
                    tokens.removeAt(i + 1)
                    tokens.removeAt(i)
                    return city
                }
            }
        }

        return null
    }

    private fun normalizeHouseToken(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }

        val cleaned = token.trim().replace(" ", "")
        val match = Regex("^(\\d+)([a-zа-я]?)$").find(cleaned) ?: return cleaned.uppercase()

        val number = match.groupValues[1]
        val suffix = match.groupValues[2]

        return if (suffix.isBlank()) {
            number
        } else {
            number + suffix.uppercase()
        }
    }

    private fun normalizeCorpusToken(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }

        val cleaned = token.trim().replace(" ", "")
        val match = Regex("^(\\d+)([a-zа-я]?)$").find(cleaned) ?: return cleaned.uppercase()

        val number = match.groupValues[1]
        val suffix = match.groupValues[2]

        return if (suffix.isBlank()) {
            number
        } else {
            number + suffix.uppercase()
        }
    }
}