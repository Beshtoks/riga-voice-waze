package com.riga.voicewaze.ui.distance

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.riga.voicewaze.R
import com.riga.voicewaze.data.local.LandmarkRepository
import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.domain.cloud.GoogleCloudLatvianTranscriber
import com.riga.voicewaze.domain.cloud.WavRecorder
import com.riga.voicewaze.domain.landmark.LandmarkMatcher
import com.riga.voicewaze.domain.matcher.AddressSuggestion
import com.riga.voicewaze.domain.matcher.StreetMatcher
import com.riga.voicewaze.domain.preprocessor.AddressPreprocessor
import com.riga.voicewaze.domain.preprocessor.ProcessedAddressQuery
import com.riga.voicewaze.domain.validator.HouseValidationResult
import com.riga.voicewaze.domain.validator.HouseValidationStatus
import com.riga.voicewaze.domain.validator.NominatimHouseValidator
import com.riga.voicewaze.ui.main.SuggestionAdapter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DistanceActivity : AppCompatActivity() {

    private enum class TargetField {
        START, END
    }

    private data class PointState(
        var inputText: String = "",
        var processedQuery: ProcessedAddressQuery? = null,
        var finalAddress: String = "",
        var latitude: Double? = null,
        var longitude: Double? = null,
        var validation: HouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID),
        var confirmed: Boolean = false
    )

    private data class SectionViews(
        val input: EditText,
        val btnRu: Button,
        val btnLv: Button,
        val tvPrepared: TextView,
        val tvResult: TextView,
        val tvValidation: TextView,
        val tvCoords: TextView,
        val rvSuggestions: RecyclerView
    )

    private lateinit var startViews: SectionViews
    private lateinit var endViews: SectionViews

    private lateinit var tvDirectDistance: TextView
    private lateinit var tvRoadDistance: TextView
    private lateinit var tvRoadDuration: TextView

    private lateinit var streetMatcher: StreetMatcher
    private lateinit var landmarkMatcher: LandmarkMatcher
    private lateinit var addressPreprocessor: AddressPreprocessor
    private lateinit var houseValidator: NominatimHouseValidator
    private lateinit var wavRecorder: WavRecorder
    private lateinit var cloudTranscriber: GoogleCloudLatvianTranscriber

    private lateinit var startSuggestionAdapter: SuggestionAdapter
    private lateinit var endSuggestionAdapter: SuggestionAdapter

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    private val startState = PointState()
    private val endState = PointState()

    private var pendingSpeechTarget: TargetField? = null
    private var pendingSpeechIsObject: Boolean = false
    private var currentLvRecordingTarget: TargetField? = null
    private var suppressStartWatcher = false
    private var suppressEndWatcher = false

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = pendingSpeechTarget
            val isObject = pendingSpeechIsObject
            pendingSpeechTarget = null
            pendingSpeechIsObject = false

            val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = list?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isBlank()) {
                toast("Речь не распознана")
                return@registerForActivityResult
            }

            if (target == null) return@registerForActivityResult

            setInputText(target, spokenText)

            if (isObject) {
                processObjectSearch(target, spokenText)
            } else {
                processAddressSearch(target, spokenText)
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("Нет доступа к микрофону")
                return@registerForActivityResult
            }

            val target = pendingSpeechTarget ?: currentLvRecordingTarget
            if (target == null) return@registerForActivityResult

            if (pendingSpeechIsObject) {
                startGoogleVoiceRecognition(target)
            } else {
                toggleLatvianRecording(target)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distance)

        streetMatcher = StreetMatcher(StreetRepository(this))
        landmarkMatcher = LandmarkMatcher(LandmarkRepository(this))
        addressPreprocessor = AddressPreprocessor()
        houseValidator = NominatimHouseValidator()
        wavRecorder = WavRecorder(cacheDir)
        cloudTranscriber = GoogleCloudLatvianTranscriber()

        startViews = SectionViews(
            input = findViewById(R.id.etStartInput),
            btnRu = findViewById(R.id.btnStartRu),
            btnLv = findViewById(R.id.btnStartLv),
            tvPrepared = findViewById(R.id.tvStartPrepared),
            tvResult = findViewById(R.id.tvStartResult),
            tvValidation = findViewById(R.id.tvStartValidation),
            tvCoords = findViewById(R.id.tvStartCoords),
            rvSuggestions = findViewById(R.id.rvStartSuggestions)
        )

        endViews = SectionViews(
            input = findViewById(R.id.etEndInput),
            btnRu = findViewById(R.id.btnEndRu),
            btnLv = findViewById(R.id.btnEndLv),
            tvPrepared = findViewById(R.id.tvEndPrepared),
            tvResult = findViewById(R.id.tvEndResult),
            tvValidation = findViewById(R.id.tvEndValidation),
            tvCoords = findViewById(R.id.tvEndCoords),
            rvSuggestions = findViewById(R.id.rvEndSuggestions)
        )

        tvDirectDistance = findViewById(R.id.tvDirectDistance)
        tvRoadDistance = findViewById(R.id.tvRoadDistance)
        tvRoadDuration = findViewById(R.id.tvRoadDuration)

        startSuggestionAdapter = SuggestionAdapter { onSuggestionSelected(TargetField.START, it) }
        endSuggestionAdapter = SuggestionAdapter { onSuggestionSelected(TargetField.END, it) }

        startViews.rvSuggestions.layoutManager = LinearLayoutManager(this)
        startViews.rvSuggestions.adapter = startSuggestionAdapter
        startViews.rvSuggestions.isNestedScrollingEnabled = false

        endViews.rvSuggestions.layoutManager = LinearLayoutManager(this)
        endViews.rvSuggestions.adapter = endSuggestionAdapter
        endViews.rvSuggestions.isNestedScrollingEnabled = false

        bindSection(TargetField.START, startViews)
        bindSection(TargetField.END, endViews)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        clearDistanceResult()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        toneGenerator.release()
        safeStopLatvianRecording()
    }

    private fun bindSection(target: TargetField, views: SectionViews) {
        views.btnRu.setOnClickListener {
            pendingSpeechTarget = target
            pendingSpeechIsObject = true
            ensureMicPermissionAndStart()
        }

        views.btnLv.setOnClickListener {
            pendingSpeechTarget = target
            pendingSpeechIsObject = false
            ensureMicPermissionAndStart()
        }

        views.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val suppressed = if (target == TargetField.START) suppressStartWatcher else suppressEndWatcher
                if (suppressed) return

                val input = s?.toString()?.trim().orEmpty()
                val state = stateFor(target)
                state.inputText = input
                state.confirmed = false
                state.latitude = null
                state.longitude = null
                clearDistanceResult()

                if (input.length < 3) {
                    clearSectionSuggestions(target)
                    if (input.isBlank()) {
                        resetSectionDiagnostics(target)
                    }
                    return
                }

                processAddressSearch(target, input, liveMode = true)
            }
        })
    }

    private fun ensureMicPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            if (pendingSpeechIsObject) {
                val target = pendingSpeechTarget ?: return
                startGoogleVoiceRecognition(target)
            } else {
                val target = pendingSpeechTarget ?: return
                toggleLatvianRecording(target)
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startGoogleVoiceRecognition(target: TargetField) {
        pendingSpeechTarget = target
        pendingSpeechIsObject = true

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи объект")
            }
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Голосовой ввод недоступен")
        } catch (_: Exception) {
            toast("Ошибка запуска микрофона")
        }
    }

    private fun toggleLatvianRecording(target: TargetField) {
        if (currentLvRecordingTarget != null) {
            if (currentLvRecordingTarget == target) {
                stopLatvianCloudRecordingAndTranscribe(target)
            } else {
                toast("Сначала останови текущую запись")
            }
            return
        }

        startLatvianCloudRecording(target)
    }

    private fun startLatvianCloudRecording(target: TargetField) {
        try {
            wavRecorder.start()
            currentLvRecordingTarget = target
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            buttonForLv(target).text = "STOP"
            toast(if (target == TargetField.START) "Запись точки отправления..." else "Запись точки назначения...")
        } catch (_: Exception) {
            currentLvRecordingTarget = null
            buttonForLv(target).text = "LV"
            toast("Ошибка записи")
        }
    }

    private fun stopLatvianCloudRecordingAndTranscribe(target: TargetField) {
        val audioFile = try {
            wavRecorder.stop()
        } catch (_: Exception) {
            currentLvRecordingTarget = null
            buttonForLv(target).text = "LV"
            toast("Ошибка остановки записи")
            return
        }

        currentLvRecordingTarget = null
        buttonForLv(target).text = "LV"
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 140)

        executor.execute {
            try {
                val transcript = cloudTranscriber.transcribe(audioFile)
                runOnUiThread {
                    if (transcript.isBlank()) {
                        toast("Облако не распознало адрес")
                        return@runOnUiThread
                    }
                    setInputText(target, transcript)
                    processAddressSearch(target, transcript)
                }
            } catch (_: Exception) {
                runOnUiThread { toast("Ошибка облака") }
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun safeStopLatvianRecording() {
        val target = currentLvRecordingTarget ?: return
        try {
            wavRecorder.stop().delete()
        } catch (_: Exception) {
        }
        currentLvRecordingTarget = null
        buttonForLv(target).text = "LV"
    }

    private fun processObjectSearch(target: TargetField, input: String) {
        executor.execute {
            val match = landmarkMatcher.findBestMatch(input)
            val accepted = match.isConfident && !match.address.isBlank() &&
                match.latitude != null && match.longitude != null

            runOnUiThread {
                val state = stateFor(target)
                val views = viewsFor(target)

                state.inputText = input
                state.finalAddress = if (accepted) match.address else ""
                state.latitude = match.latitude
                state.longitude = match.longitude
                state.confirmed = accepted
                state.validation = if (accepted) {
                    HouseValidationResult(
                        status = HouseValidationStatus.VALID,
                        latitude = match.latitude,
                        longitude = match.longitude
                    )
                } else {
                    HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Объект не найден")
                }

                views.tvPrepared.text = "Объект: ${if (accepted) match.displayName else ""}"
                views.tvResult.text = if (accepted) match.address else "Объект не найден"
                applyValidationUi(views, state.validation)
                clearSectionSuggestions(target)

                maybeCalculateDistance()
            }
        }
    }

    private fun processAddressSearch(target: TargetField, input: String, liveMode: Boolean = false) {
        executor.execute {
            val processed = addressPreprocessor.process(input)
            if (!processed.isValid) {
                runOnUiThread {
                    val state = stateFor(target)
                    val views = viewsFor(target)
                    state.inputText = input
                    state.processedQuery = processed
                    state.finalAddress = ""
                    state.latitude = null
                    state.longitude = null
                    state.confirmed = false
                    state.validation = HouseValidationResult(
                        HouseValidationStatus.NOT_FOUND,
                        processed.errorMessage ?: "Адрес введён некорректно"
                    )

                    views.tvPrepared.text = ""
                    views.tvResult.text = processed.errorMessage ?: "Адрес введён некорректно"
                    applyValidationUi(views, state.validation)
                    clearSectionSuggestions(target)
                    clearDistanceResult()
                }
                return@execute
            }

            val matches = streetMatcher.findTopMatchesDetailed(
                input = processed.matcherInput,
                preferredCity = processed.city,
                limit = 10
            )

            if (matches.isEmpty()) {
                runOnUiThread {
                    val state = stateFor(target)
                    val views = viewsFor(target)
                    state.inputText = input
                    state.processedQuery = processed
                    state.finalAddress = ""
                    state.latitude = null
                    state.longitude = null
                    state.confirmed = false
                    state.validation = HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Улица не найдена")

                    views.tvPrepared.text =
                        if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
                    views.tvResult.text = "Улица не найдена"
                    applyValidationUi(views, state.validation)
                    clearSectionSuggestions(target)
                    clearDistanceResult()
                }
                return@execute
            }

            val best = matches.first()
            val bestStreet = normalizeStreetForDisplay(best.street)
            val bestCity = if (best.city.isBlank()) processed.city else best.city
            val validationResult = validateHouseIfNeeded(
                street = bestStreet,
                houseNumber = processed.houseNumber,
                city = bestCity
            )
            val canonicalHouse = validationResult.canonicalHouseNumber ?: processed.houseNumber
            val finalAddress = buildFullAddress(bestStreet, canonicalHouse, bestCity)

            val others = matches.drop(1).map { suggestion ->
                val otherStreet = normalizeStreetForDisplay(suggestion.street)
                val otherCity = if (suggestion.city.isBlank()) processed.city else suggestion.city
                suggestion.copy(
                    street = buildFullAddress(otherStreet, processed.houseNumber, otherCity),
                    city = ""
                )
            }

            runOnUiThread {
                val state = stateFor(target)
                val views = viewsFor(target)
                state.inputText = input
                state.processedQuery = processed
                state.finalAddress = finalAddress
                state.latitude = validationResult.latitude
                state.longitude = validationResult.longitude
                state.validation = validationResult
                state.confirmed = validationAllowsConfirm(validationResult)

                views.tvPrepared.text =
                    if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
                views.tvResult.text = "$finalAddress, Latvija"
                applyValidationUi(views, validationResult)

                if (liveMode) {
                    adapterFor(target).submitList(others)
                } else {
                    clearSectionSuggestions(target)
                }

                maybeCalculateDistance()
            }
        }
    }

    private fun onSuggestionSelected(target: TargetField, suggestion: AddressSuggestion) {
        val parsed = parseDisplayAddress(suggestion.street)
        if (parsed == null || parsed.houseNumber.isNullOrBlank()) {
            val state = stateFor(target)
            val views = viewsFor(target)
            state.finalAddress = suggestion.street
            state.latitude = null
            state.longitude = null
            state.confirmed = false
            state.validation = HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Укажи номер дома")
            views.tvResult.text = suggestion.street
            applyValidationUi(views, state.validation)
            clearDistanceResult()
            return
        }

        executor.execute {
            val validationResult = validateHouseIfNeeded(
                street = parsed.street,
                houseNumber = parsed.houseNumber,
                city = parsed.city
            )
            val canonicalHouse = validationResult.canonicalHouseNumber ?: parsed.houseNumber
            val finalAddress = buildFullAddress(parsed.street, canonicalHouse, parsed.city)

            runOnUiThread {
                val state = stateFor(target)
                val views = viewsFor(target)
                state.finalAddress = finalAddress
                state.latitude = validationResult.latitude
                state.longitude = validationResult.longitude
                state.validation = validationResult
                state.confirmed = validationAllowsConfirm(validationResult)

                setInputText(target, finalAddress)
                views.tvResult.text = "$finalAddress, Latvija"
                applyValidationUi(views, validationResult)
                clearSectionSuggestions(target)
                maybeCalculateDistance()
            }
        }
    }

    private fun maybeCalculateDistance() {
        if (!startState.confirmed || !endState.confirmed) {
            clearDistanceResult()
            return
        }

        val lat1 = startState.latitude ?: return
        val lon1 = startState.longitude ?: return
        val lat2 = endState.latitude ?: return
        val lon2 = endState.longitude ?: return

        val directKm = haversineKm(lat1, lon1, lat2, lon2)
        tvDirectDistance.text = "По прямой: ${formatKm(directKm)}"

        tvRoadDistance.text = "По дороге: расчёт..."
        tvRoadDuration.text = "Время: расчёт..."

        executor.execute {
            val route = calculateRoadRoute(lat1, lon1, lat2, lon2)
            runOnUiThread {
                if (route == null) {
                    tvRoadDistance.text = "По дороге: ошибка"
                    tvRoadDuration.text = "Время: ошибка"
                } else {
                    tvRoadDistance.text = "По дороге: ${formatKm(route.distanceKm)}"
                    tvRoadDuration.text = "Время: ${formatMinutes(route.durationMinutes)}"
                }
            }
        }
    }

    private data class RoadRouteResult(
        val distanceKm: Double,
        val durationMinutes: Double
    )

    private fun calculateRoadRoute(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): RoadRouteResult? {
        val urlString =
            "https://router.project-osrm.org/route/v1/driving/" +
                "${String.format(Locale.US, "%.6f", lon1)},${String.format(Locale.US, "%.6f", lat1)};" +
                "${String.format(Locale.US, "%.6f", lon2)},${String.format(Locale.US, "%.6f", lat2)}?overview=false"

        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val routes = json.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val first = routes.optJSONObject(0) ?: return null

            val distanceMeters = first.optDouble("distance", Double.NaN)
            val durationSeconds = first.optDouble("duration", Double.NaN)
            if (distanceMeters.isNaN() || durationSeconds.isNaN()) return null

            RoadRouteResult(
                distanceKm = distanceMeters / 1000.0,
                durationMinutes = durationSeconds / 60.0
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusKm * c
    }

    private fun formatKm(value: Double): String =
        String.format(Locale.US, "%.1f км", value)

    private fun formatMinutes(value: Double): String {
        val rounded = value.roundToInt()
        return "$rounded мин"
    }

    private fun validationAllowsConfirm(result: HouseValidationResult): Boolean {
        return (result.status == HouseValidationStatus.VALID ||
            result.status == HouseValidationStatus.RELATED_FOUND) &&
            result.latitude != null && result.longitude != null
    }

    private fun applyValidationUi(views: SectionViews, result: HouseValidationResult) {
        val statusText = mapValidationStatus(result.status)
        views.tvValidation.text = buildValidationText(statusText, validationColor(result.status))
        views.tvCoords.text =
            if (result.status == HouseValidationStatus.VALID || result.status == HouseValidationStatus.RELATED_FOUND) {
                formatCoordsLine(result)
            } else {
                ""
            }
    }

    private fun buildValidationText(statusText: String, statusColor: Int): CharSequence {
        val prefix = "Проверка: "
        val fullText = prefix + statusText
        return android.text.SpannableString(fullText).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(statusColor),
                prefix.length,
                fullText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun validationColor(status: HouseValidationStatus): Int {
        return when (status) {
            HouseValidationStatus.VALID,
            HouseValidationStatus.RELATED_FOUND -> Color.parseColor("#4CAF50")
            HouseValidationStatus.NOT_FOUND -> Color.parseColor("#FFA726")
            HouseValidationStatus.CHECK_FAILED -> Color.parseColor("#FF3B30")
        }
    }

    private fun mapValidationStatus(status: HouseValidationStatus): String {
        return when (status) {
            HouseValidationStatus.VALID -> "Есть"
            HouseValidationStatus.RELATED_FOUND -> "Есть"
            HouseValidationStatus.NOT_FOUND -> "Отсутствует"
            HouseValidationStatus.CHECK_FAILED -> "Ошибка"
        }
    }

    private fun formatCoordsLine(result: HouseValidationResult): String {
        val lat = result.latitude ?: return ""
        val lon = result.longitude ?: return ""
        return "Коорд: ${String.format(Locale.US, "%.6f", lat)}, ${String.format(Locale.US, "%.6f", lon)}"
    }

    private fun buildFullAddress(street: String, houseNumber: String?, city: String): String {
        val normalizedStreet = normalizeStreetForDisplay(street)
        return if (houseNumber.isNullOrBlank()) {
            "$normalizedStreet, $city"
        } else {
            "$normalizedStreet $houseNumber, $city"
        }
    }

    private fun normalizeStreetForDisplay(street: String): String {
        if (street.isBlank() || street == "Nezināma") return ""

        val lowered = street.lowercase(Locale.ROOT)

        return when {
            lowered.contains("iela") ||
                lowered.contains("gatve") ||
                lowered.contains("prospekts") ||
                lowered.contains("bulvāris") ||
                lowered.contains("laukums") ||
                lowered.contains("krastmala") ||
                lowered.contains("ceļš") ||
                lowered.contains("dambis") ||
                lowered.contains("šķērslīnija") ||
                lowered.contains("līnija") ||
                lowered.contains("aleja") ||
                lowered.contains("gāte") ||
                lowered.contains("sēta") ||
                lowered.contains("skvērs") ||
                lowered.contains("taka") -> street
            else -> "$street iela"
        }
    }

    private fun validateHouseIfNeeded(street: String, houseNumber: String?, city: String): HouseValidationResult {
        if (houseNumber.isNullOrBlank()) {
            return HouseValidationResult(
                HouseValidationStatus.NOT_FOUND,
                "Укажи номер дома"
            )
        }

        return houseValidator.validateHouse(street = street, houseNumber = houseNumber, city = city)
    }

    private fun parseDisplayAddress(address: String): MainAddressParts? {
        val parts = address.split(",").map { it.trim() }
        if (parts.size < 2) return null

        val firstPart = parts[0]
        val houseNumber = extractHouseNumber(firstPart)
        val street = if (houseNumber.isNullOrBlank()) firstPart else firstPart.removeSuffix(" $houseNumber").trim()
        val city = parts[1]

        if (street.isBlank() || city.isBlank()) return null
        return MainAddressParts(street, houseNumber, city)
    }

    private data class MainAddressParts(
        val street: String,
        val houseNumber: String?,
        val city: String
    )

    private fun extractHouseNumber(input: String): String? {
        val regexes = listOf(
            Regex("""\b\d+[a-zA-ZА-Яа-я]?\s*k-\d+[a-zA-ZА-Яа-я]?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d+[a-zA-ZА-Яа-я]?\b""", RegexOption.IGNORE_CASE)
        )

        for (regex in regexes) {
            val match = regex.find(input)
            if (match != null) {
                return match.value.replace(Regex("""\s+"""), " ").trim()
            }
        }
        return null
    }

    private fun clearSectionSuggestions(target: TargetField) {
        adapterFor(target).submitList(emptyList())
    }

    private fun resetSectionDiagnostics(target: TargetField) {
        val views = viewsFor(target)
        val state = stateFor(target)
        state.processedQuery = null
        state.finalAddress = ""
        state.latitude = null
        state.longitude = null
        state.confirmed = false
        state.validation = HouseValidationResult(HouseValidationStatus.VALID)

        views.tvPrepared.text = ""
        views.tvResult.text = ""
        views.tvValidation.text = ""
        views.tvCoords.text = ""
    }

    private fun clearDistanceResult() {
        tvDirectDistance.text = "По прямой: —"
        tvRoadDistance.text = "По дороге: —"
        tvRoadDuration.text = "Время: —"
    }

    private fun setInputText(target: TargetField, value: String) {
        val edit = viewsFor(target).input
        if (target == TargetField.START) suppressStartWatcher = true else suppressEndWatcher = true
        edit.setText(value)
        edit.setSelection(value.length)
        if (target == TargetField.START) suppressStartWatcher = false else suppressEndWatcher = false
    }

    private fun stateFor(target: TargetField): PointState =
        if (target == TargetField.START) startState else endState

    private fun viewsFor(target: TargetField): SectionViews =
        if (target == TargetField.START) startViews else endViews

    private fun adapterFor(target: TargetField): SuggestionAdapter =
        if (target == TargetField.START) startSuggestionAdapter else endSuggestionAdapter

    private fun buttonForLv(target: TargetField): Button =
        if (target == TargetField.START) startViews.btnLv else endViews.btnLv

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
