package com.riga.voicewaze.domain.parser

class AddressParser {

    fun parse(input: String): ParsedAddress {
        val normalized = input.lowercase().trim()
        val parts = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        val houseNumber = parts.lastOrNull()?.takeIf { token ->
            token.all { ch -> ch.isDigit() }
        }

        val streetRaw = if (houseNumber != null) {
            parts.dropLast(1).joinToString(" ")
        } else {
            normalized
        }

        return ParsedAddress(
            streetRaw = streetRaw,
            houseNumber = houseNumber
        )
    }
}