package com.riga.voicewaze.domain.landmark

data class LandmarkEntry(
    val id: Long,
    val spokenPhrase: String,
    val displayName: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)
