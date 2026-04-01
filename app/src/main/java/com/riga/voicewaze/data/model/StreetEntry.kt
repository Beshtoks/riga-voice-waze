package com.riga.voicewaze.data.model

data class StreetEntry(
    val official: String,
    val city: String,
    val aliases: List<String>,
    val priority: Int
)