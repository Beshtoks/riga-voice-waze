package com.riga.voicewaze.domain.landmark

class LandmarkMatcher {

    private companion object {
        private const val MIN_CONFIDENT_MATCH_PERCENT = 60
    }

    private val entries = listOf(
        LandmarkEntry("аэропорт", "Starptautiskā lidosta Rīga, Mārupes novads, Latvija"),
        LandmarkEntry("вокзал", "Stacijas laukums 2, Rīga, Latvija"),
        LandmarkEntry("автовокзал", "Prāgas iela 1, Rīga, Latvija"),
        LandmarkEntry("рынок", "Nēģu iela 7, Rīga, Latvija"),
        LandmarkEntry("цирк", "Merķeļa iela 4, Rīga, Latvija"),

        LandmarkEntry("мильда", "Brīvības laukums 1, Rīga, Latvija"),

        LandmarkEntry("латвия", "Elizabetes iela 55, Rīga, Latvija"),
        LandmarkEntry("даугава", "Kuģu iela 24, Rīga, Latvija"),
        LandmarkEntry("ридзене", "Reimersa iela 1, Rīga, Latvija"),
        LandmarkEntry("элизабетта", "Elizabetes iela 73, Rīga, Latvija"),
        LandmarkEntry("талинк", "Elizabetes iela 24, Rīga, Latvija"),
        LandmarkEntry("олд таун", "Zigfrīda Annas Meierovica bulvāris 10, Rīga, Latvija"),
        LandmarkEntry("астор", "Zigfrīda Annas Meierovica bulvāris 10, Rīga, Latvija"),
        LandmarkEntry("гранд палас", "Pils iela 12, Rīga, Latvija"),
        LandmarkEntry("нейбург", "Jauniela 25/27, Rīga, Latvija"),

        LandmarkEntry("риверсайт", "11. novembra krastmala 33, Rīga, Latvija"),
        LandmarkEntry("велтон олд рига", "Vaļņu iela 49, Rīga, Latvija"),
        LandmarkEntry("центрум", "Kalēju iela 33, Rīga, Latvija"),
        LandmarkEntry("велтон рига", "Vaļņu iela 49, Rīga, Latvija"),

        LandmarkEntry("метрополь", "Aspazijas bulvāris 36/38, Rīga, Latvija"),
        LandmarkEntry("форум", "Vaļņu iela 45, Rīga, Latvija"),
        LandmarkEntry("риксвелл централ", "Elizabetes iela 101, Rīga, Latvija"),
        LandmarkEntry("риксвелл олд рига", "Minsterejas iela 8/10, Rīga, Latvija"),
        LandmarkEntry("гутэнбэрк", "Doma laukums 1, Rīga, Latvija"),

        LandmarkEntry("юрмала спа", "Jomas iela 47/49, Jūrmala, Latvija"),
        LandmarkEntry("балтик бич", "Jūras iela 23/25, Jūrmala, Latvija"),
        LandmarkEntry("самарах", "Bulduru prospekts 64/68, Jūrmala, Latvija"),

        LandmarkEntry("эйси мариет", "Dzirnavu iela 33, Rīga, Latvija"),
        LandmarkEntry("амэла", "Ausekļa iela 22, Rīga, Latvija"),
        LandmarkEntry("астон", "Zigfrīda Annas Meierovica bulvāris 10, Rīga, Latvija"),
        LandmarkEntry("авалон", "13. janvāra iela 19, Rīga, Latvija"),
        LandmarkEntry("бэлэвью", "Slokas iela 1, Rīga, Latvija"),
        LandmarkEntry("бэргс", "Elizabetes iela 83/85, Rīga, Latvija"),
        LandmarkEntry("додо", "Jersikas iela 1, Rīga, Latvija"),

        LandmarkEntry("домэ хостэл", "8 Maza Jaunavaru iela, Riga"),
        LandmarkEntry("дома хостэл", "Mārstaļu iela 1, Rīga, Latvija"),
        LandmarkEntry("домус хостэл", "Tirgoņu iela 9, Rīga, Latvija"),

        LandmarkEntry("гранд поэт", "Raiņa bulvāris 5/6, Rīga, Latvija"),
        LandmarkEntry("драуги", "Mārstaļu iela 3, Rīga, Latvija"),
        LandmarkEntry("джюгент", "Pulkveža Brieža iela 11, Rīga, Latvija"),
        LandmarkEntry("юстус", "Jauniela 24, Rīga, Latvija"),
        LandmarkEntry("исландэ", "Ķīpsalas iela 2, Rīga, Latvija"),
        LandmarkEntry("кемпински", "Aspazijas bulvāris 22, Rīga, Latvija"),
        LandmarkEntry("конвэнта сэта", "Kalēju iela 9/11, Rīga, Latvija"),
        LandmarkEntry("мара", "Kalnciema iela 186, Rīga, Latvija"),
        LandmarkEntry("мэркурс", "Elizabetes iela 101, Rīga, Latvija"),
        LandmarkEntry("моника", "Elizabetes iela 21, Rīga, Latvija"),
        LandmarkEntry("монте-кристо", "Kalēju iela 56, Rīga, Latvija"),
        LandmarkEntry("окей", "Slokas iela 12, Rīga, Latvija"),
        LandmarkEntry("опера", "Raiņa bulvāris 33, Rīga, Latvija"),
        LandmarkEntry("опера и балет", "Aspazijas bulvāris 3, Rīga, Latvija"),
        LandmarkEntry("пулман", "Jēkaba iela 24, Rīga, Latvija"),
        LandmarkEntry("томо", "Raunas iela 44, Rīga, Latvija"),
        LandmarkEntry("веф", "Brīvības gatve 199c, Rīga, Latvija"),
        LandmarkEntry("вэцрига", "Gleznotāju iela 12/14, Rīga, Latvija"),
        LandmarkEntry("виктория", "Aleksandra Čaka iela 55, Rīga, Latvija"),

        LandmarkEntry("дзинтари", "Turaidas iela 1, Jūrmala, Latvija"),
        LandmarkEntry("аквапарк", "Viestura iela 24, Jūrmala, Latvija"),

        LandmarkEntry("домой", "Ieriķu iela 33, Rīga, Latvija"),
        LandmarkEntry("стоянка", "Ieriķu iela 41A, Rīga, Latvija"),
        LandmarkEntry("сэрвис", "Lubānas iela 99, Rīga, Latvija"),
        LandmarkEntry("николай", "Saharova iela 8, Rīga, Latvija"),
        LandmarkEntry("айвар", "Pavasara iela 35, Baloži, Latvija"),

        LandmarkEntry("лачупес", "Lāčupes kapi, Rīga, Latvija"),
        LandmarkEntry("мэжа", "Meža kapi, Rīga, Latvija"),

        LandmarkEntry("альфа", "Brīvības gatve 372, Rīga, Latvija"),
        LandmarkEntry("молс", "Krasta iela 46, Rīga, Latvija"),
        LandmarkEntry("спица", "Lielirbes iela 29, Rīga, Latvija"),
        LandmarkEntry("акрополь", "Maskavas iela 257, Rīga, Latvija"),

        LandmarkEntry("страдыня", "Pilsoņu iela 13, Rīga, Latvija"),
        LandmarkEntry("домина", "Ieriķu iela 3, Rīga, Latvija"),
        LandmarkEntry("майори", "Jomas iela, Jūrmala, Latvija"),

        LandmarkEntry("сейм", "Jēkaba iela 11, Rīga, Latvija"),
        LandmarkEntry("гертруда", "Ģertrūdes iela, Rīga, Latvija"),
        LandmarkEntry("мотормузей", "Sergeja Eizenšteina iela 6, Rīga, Latvija"),
        LandmarkEntry("этнографический", "Brīvdabas muzejs, Rīga, Latvija"),

        LandmarkEntry("башня", "Smilšu iela 20, Rīga, Latvija"),
        LandmarkEntry("телебашня", "Zaķusalas televīzijas tornis, Rīga, Latvija"),

        LandmarkEntry("межапарк", "Mežaparks, Rīga, Latvija"),
        LandmarkEntry("зоопарк", "Meža prospekts 1, Rīga, Latvija"),
        LandmarkEntry("кишка", "Ķīpsala, Rīga, Latvija"),
        LandmarkEntry("ботаника", "Kandavas iela 2, Rīga, Latvija"),
        LandmarkEntry("агентчик", "Rīga, Latvija"),
        LandmarkEntry("базар", "Centrāltirgus, Rīga, Latvija"),
        LandmarkEntry("лидо", "Krasta iela 76, Rīga, Latvija"),
        LandmarkEntry("арена рига", "Skanstes iela 21, Rīga, Latvija"),
        LandmarkEntry("сконто", "Hanzas iela 5, Rīga, Latvija")
    )

    fun findBestMatch(input: String): LandmarkMatchResult {
        val normalizedInput = normalize(input)

        if (normalizedInput.isBlank()) {
            return LandmarkMatchResult(
                name = "",
                address = "",
                matchPercent = 0,
                isConfident = false
            )
        }

        var bestEntry: LandmarkEntry? = null
        var bestPercent = -1
        var bestScore = Int.MAX_VALUE

        for (entry in entries) {
            val normalizedName = normalize(entry.name)
            val score = levenshtein(normalizedInput, normalizedName)
            val percent = similarityPercent(normalizedInput, normalizedName)

            if (percent > bestPercent) {
                bestPercent = percent
                bestScore = score
                bestEntry = entry
            } else if (percent == bestPercent && score < bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        if (bestEntry == null) {
            return LandmarkMatchResult(
                name = "",
                address = "",
                matchPercent = 0,
                isConfident = false
            )
        }

        return LandmarkMatchResult(
            name = bestEntry.name,
            address = bestEntry.address,
            matchPercent = bestPercent.coerceAtLeast(0),
            isConfident = bestPercent >= MIN_CONFIDENT_MATCH_PERCENT
        )
    }

    private fun similarityPercent(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100

        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0

        val distance = levenshtein(a, b)
        val percent = ((maxLen - distance).toDouble() / maxLen.toDouble()) * 100.0
        return percent.toInt().coerceIn(0, 100)
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace("ё", "е")
            .replace("й", "и")
            .replace("-", "")
            .replace(" ", "")
            .replace(".", "")
            .replace(",", "")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[a.length][b.length]
    }
}