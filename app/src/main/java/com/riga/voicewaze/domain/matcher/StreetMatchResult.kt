package com.riga.voicewaze.domain.matcher

data class StreetMatchResult(
    val street: String,
    val city: String,
    val score: Int,
    val isConfident: Boolean
)