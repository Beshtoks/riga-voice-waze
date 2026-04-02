package com.riga.voicewaze.domain.landmark

data class LandmarkMatchResult(
    val name: String,
    val address: String,
    val matchPercent: Int,
    val isConfident: Boolean
)