package com.riga.voicewaze.domain.parser

class AddressParser {

    fun parse(input: String): ParsedAddress {
        val normalized = input
            .lowercase()
            .trim()
            .replace(",", " ")
            .replace("\\s+".toRegex(), " ")

        val compact = normalized
            .replace("корпус", "к")
            .replace("корп", "к")
            .replace(" k ", " к ")

        val tokens = compact.split(" ").filter { it.isNotBlank() }

        var houseNumber: String? = null
        var корпус: String? = null
        var streetEndIndex = tokens.size

        for (i in tokens.indices) {
            val token = tokens[i]

            if (houseNumber == null && token.matches(Regex("\\d+[/-]\\d+"))) {
                val parts = token.split("/", "-")
                houseNumber = parts.getOrNull(0)
                корпус = parts.getOrNull(1)
                streetEndIndex = i
                break
            }

            if (houseNumber == null && token.matches(Regex("\\d+"))) {
                houseNumber = token
                streetEndIndex = i

                if (i + 1 < tokens.size) {
                    val next = tokens[i + 1]

                    if (next == "к" && i + 2 < tokens.size && tokens[i + 2].matches(Regex("\\d+"))) {
                        корпус = tokens[i + 2]
                        break
                    }

                    if (next.matches(Regex("к\\d+"))) {
                        корпус = next.removePrefix("к")
                        break
                    }
                }

                break
            }
        }

        val streetRaw = if (streetEndIndex in 1..tokens.size) {
            tokens.subList(0, streetEndIndex).joinToString(" ")
        } else {
            normalized
        }

        return ParsedAddress(
            streetRaw = streetRaw,
            houseNumber = houseNumber,
            корпус = корпус
        )
    }
}