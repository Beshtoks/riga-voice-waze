package com.riga.voicewaze.domain.matcher

object StreetSynonyms {

    private val map = mapOf(

        // Lāčplēša
        "lachplesha" to "Lāčplēša",
        "lachplesa" to "Lāčplēša",
        "lajplesha" to "Lāčplēša",
        "lasplesha" to "Lāčplēša",

        // Brīvības
        "brivibas" to "Brīvības",
        "bribibas" to "Brīvības",
        "brivibas" to "Brīvības",

        // Čaka
        "chaka" to "Čaka",
        "czaka" to "Čaka",
        "caka" to "Čaka",

        // Ģertrūdes
        "gertrudes" to "Ģertrūdes",
        "gertrud" to "Ģertrūdes",
        "gertrudes" to "Ģertrūdes",

        // Maskavas
        "maskavas" to "Maskavas",
        "maskava" to "Maskavas",

        // Valdemāra
        "valdemara" to "Krišjāņa Valdemāra",
        "valdemara" to "Krišjāņa Valdemāra",

        // Elizabetes
        "elizabetes" to "Elizabetes",
        "elizabete" to "Elizabetes"
    )

    fun find(normalizedInput: String): String? {
        return map[normalizedInput]
    }
}