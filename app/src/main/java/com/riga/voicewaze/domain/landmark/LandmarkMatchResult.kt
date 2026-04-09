package com.riga.voicewaze.domain.landmark

data class LandmarkMatchResult(
    val spokenPhrase: String,
    val displayName: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val matchPercent: Int,
    val isConfident: Boolean
)
