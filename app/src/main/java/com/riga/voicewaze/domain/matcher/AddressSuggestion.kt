package com.riga.voicewaze.domain.matcher

data class AddressSuggestion(
    val street: String,
    val city: String,
    val matchPercent: Int,
    val score: Int
)