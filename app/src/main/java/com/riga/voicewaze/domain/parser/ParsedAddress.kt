package com.riga.voicewaze.domain.parser

data class ParsedAddress(
    val streetRaw: String,
    val houseNumber: String?,
    val корпус: String?,
    val cityRaw: String
)