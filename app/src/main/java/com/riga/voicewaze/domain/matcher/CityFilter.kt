package com.riga.voicewaze.domain.matcher

object CityFilter {

    private val allowedCities = setOf(
        "Rīga",
        "Daugavpils",
        "Liepāja",
        "Jelgava",
        "Jūrmala",
        "Ventspils",
        "Rēzekne",
        "Valmiera",
        "Ogre",
        "Cēsis",
        "Salaspils",
        "Tukums",
        "Kuldīga",
        "Saldus",
        "Talsi",
        "Dobele",
        "Sigulda",
        "Krāslava",
        "Bauska",
        "Ludza",
        "Madona",
        "Gulbene",
        "Preiļi",
        "Ādaži",
        "Līvāni",
        "Smiltene",
        "Balvi",
        "Aizkraukle",
        "Limbaži",
        "Alūksne",
        "Olaine"
    )

    fun isAllowed(city: String): Boolean {
        return allowedCities.contains(city)
    }
}