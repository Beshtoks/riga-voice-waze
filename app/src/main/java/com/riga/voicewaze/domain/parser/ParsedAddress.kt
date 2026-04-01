package com.riga.voicewaze.domain.parser

data class ParsedAddress(
    val streetRaw: String,
    val houseNumber: String?
)