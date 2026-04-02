package com.riga.voicewaze.domain.parser

data class ParsedAddress(
    val streetRaw: String,
    val streetMerged: String,
    val houseNumber: String?,
    val cityRaw: String
)