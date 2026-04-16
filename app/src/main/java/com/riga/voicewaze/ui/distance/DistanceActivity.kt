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
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
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

    private data class PointState(
        var inputText: String = "",
        var processedQuery: ProcessedAddressQuery? = null,
        var finalAddress: String = "",
        var latitude: Double? = null,
        var longitude: Double? = null,
        var validation: HouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID),
        var confirmed: Boolean = false,
        var suppressWatcher: Boolean = false
    )

    private data class SectionViews(
        val root: LinearLayout,
        val tvTitle: TextView,
        val btnRemove: Button,
        val input: EditText,
        val btnRu: Button,
        val btnLv: Button,
        val tvPrepared: TextView,
        val tvResult: TextView,
        val tvValidation: TextView,
        val tvCoords: TextView,
        val rvSuggestions: RecyclerView,
        val adapter: SuggestionAdapter
    )

    private data class RoadRouteResult(
        val distanceKm: Double,
        val durationMinutes: Double
    )

    private data class MainAddressParts(
        val street: String,
        val houseNumber: String?,
        val city: String
    )

    private lateinit var sectionsContainer: LinearLayout
    private lateinit var btnAddPoint: Button
    private lateinit var scrollSections: NestedScrollView

    private lateinit var tvDirectDistance: TextView
    private lateinit var tvRoadDistance: TextView
    private lateinit var tvRoadDuration: TextView

    private lateinit var streetMatcher: StreetMatcher
    private lateinit var landmarkMatcher: LandmarkMatcher
    private lateinit var addressPreprocessor: AddressPreprocessor
    private lateinit var houseValidator: NominatimHouseValidator
    private lateinit var wavRecorder: WavRecorder
    private lateinit var cloudTranscriber: GoogleCloudLatvianTranscriber

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    private val sections = mutableListOf<SectionViews>()
    private val states = mutableListOf<PointState>()

    private var pendingSpeechIndex: Int? = null
    private var pendingSpeechIsObject: Boolean = false
    private var currentLvRecordingIndex: Int? = null

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val index = pendingSpeechIndex
            val isObject = pendingSpeechIsObject
            pendingSpeechIndex = null
            pendingSpeechIsObject = false

            val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = list?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isBlank()) {
                toast("Речь не распознана")
                return@registerForActivityResult
            }

            if (index == null || index !in sections.indices) return@registerForActivityResult

            setInputText(index, spokenText)

            if (isObject) {
                processObjectSearch(index, spokenText)
            } else {
                processAddressSearch(index, spokenText)
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("Нет доступа к микрофону")
                return@registerForActivityResult
            }

            val index = pendingSpeechIndex ?: currentLvRecordingIndex
            if (index == null || index !in sections.indices) return@registerForActivityResult

            if (pendingSpeechIsObject) {
                startGoogleVoiceRecognition(index)
            } else {
                toggleLatvianRecording(index)
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

        sectionsContainer = findViewById(R.id.sectionsContainer)
        btnAddPoint = findViewById(R.id.btnAddPoint)
        scrollSections = findViewById(R.id.scrollSections)

        tvDirectDistance = findViewById(R.id.tvDirectDistance)
        tvRoadDistance = findViewById(R.id.tvRoadDistance)
        tvRoadDuration = findViewById(R.id.tvRoadDuration)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        btnAddPoint.setOnClickListener {
            addSection()
            scrollSections.post { scrollSections.fullScroll(View.FOCUS_DOWN) }
        }

        addSection()
        addSection()
        refreshSectionHeadersAndRemoveButtons()
        clearDistanceResult()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        toneGenerator.release()
        safeStopLatvianRecording()
    }

    private fun addSection() {
        val state = PointState()
        states.add(state)

        val sectionIndex = states.lastIndex
        val section = createSection(sectionIndex)
        sections.add(section)
        sectionsContainer.addView(section.root)

        refreshSectionHeadersAndRemoveButtons()
    }

    private fun removeSection(index: Int) {
        if (sections.size <= 2) return
        if (index !in sections.indices) return

        if (currentLvRecordingIndex == index) {
            safeStopLatvianRecording()
        } else if (currentLvRecordingIndex != null && currentLvRecordingIndex!! > index) {
            currentLvRecordingIndex = currentLvRecordingIndex!! - 1
        }

        if (pendingSpeechIndex != null && pendingSpeechIndex == index) {
            pendingSpeechIndex = null
            pendingSpeechIsObject = false
        } else if (pendingSpeechIndex != null && pendingSpeechIndex!! > index) {
            pendingSpeechIndex = pendingSpeechIndex!! - 1
        }

        sectionsContainer.removeView(sections[index].root)
        sections.removeAt(index)
        states.removeAt(index)

        refreshSectionHeadersAndRemoveButtons()
        maybeCalculateDistance()
    }

    private fun refreshSectionHeadersAndRemoveButtons() {
        sections.forEachIndexed { index, section ->
            section.tvTitle.text = sectionTitle(index)

            val removable = sections.size >= 3 && index >= 1
            section.btnRemove.visibility = if (removable) View.VISIBLE else View.GONE
        }
    }

    private fun sectionTitle(index: Int): String {
        return when {
            index == 0 -> "Откуда"
            index == sections.lastIndex -> "Куда"
            else -> "Промежуточный пункт $index"
        }
    }

    private fun createSection(index: Int): SectionViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnRemove = Button(this).apply {
            text = "Удалить"
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_dark)
            visibility = View.GONE
            setOnClickListener {
                val currentIndex = sections.indexOfFirst { it.root === root }
                if (currentIndex >= 0) removeSection(currentIndex)
            }
        }

        topRow.addView(tvTitle)
        topRow.addView(btnRemove)
        root.addView(topRow)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val input = EditText(this).apply {
            hint = "Адрес или объект"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.white)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val btnRu = Button(this).apply {
            text = "RU"
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_green_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        val btnLv = Button(this).apply {
            text = "LV"
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_blue_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        inputRow.addView(input)
        inputRow.addView(btnRu)
        inputRow.addView(btnLv)
        root.addView(inputRow)

        val tvPrepared = createDiagnosticText("#B0BEC5", 14f, 10)
        val tvResult = createDiagnosticText("#FFFFFF", 17f, 4)
        val tvValidation = createDiagnosticText("#B0BEC5", 14f, 4)
        val tvCoords = createDiagnosticText("#B0BEC5", 14f, 2)

        root.addView(tvPrepared)
        root.addView(tvResult)
        root.addView(tvValidation)
        root.addView(tvCoords)

        val rvSuggestions = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DistanceActivity)
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val adapter = SuggestionAdapter { suggestion ->
            val currentIndex = sections.indexOfFirst { it.root === root }
            if (currentIndex >= 0) onSuggestionSelected(currentIndex, suggestion)
        }
        rvSuggestions.adapter = adapter
        root.addView(rvSuggestions)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(18)
            }
        }
        root.addView(divider)

        val section = SectionViews(
            root = root,
            tvTitle = tvTitle,
            btnRemove = btnRemove,
            input = input,
            btnRu = btnRu,
            btnLv = btnLv,
            tvPrepared = tvPrepared,
            tvResult = tvResult,
            tvValidation = tvValidation,
            tvCoords = tvCoords,
            rvSuggestions = rvSuggestions,
            adapter = adapter
        )

        bindSection(section)
        return section
    }

    private fun createDiagnosticText(colorHex: String, sizeSp: Float, topMarginDp: Int): TextView {
        return TextView(this).apply {
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
            }
        }
    }

    private fun bindSection(section: SectionViews) {
        section.btnRu.setOnClickListener {
            val index = sections.indexOfFirst { it.root === section.root }
            if (index < 0) return@setOnClickListener
            pendingSpeechIndex = index
            pendingSpeechIsObject = true
            ensureMicPermissionAndStart()
        }

        section.btnLv.setOnClickListener {
            val index = sections.indexOfFirst { it.root === section.root }
            if (index < 0) return@setOnClickListener
            pendingSpeechIndex = index
            pendingSpeechIsObject = false
            ensureMicPermissionAndStart()
        }

        section.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val index = sections.indexOfFirst { it.root === section.root }
                if (index !in states.indices) return

                val state = states[index]
                if (state.suppressWatcher) return

                val input = s?.toString()?.trim().orEmpty()
                state.inputText = input
                state.confirmed = false
                state.latitude = null
                state.longitude = null
                clearDistanceResult()

                if (input.length < 3) {
                    clearSectionSuggestions(index)
                    if (input.isBlank()) {
                        resetSectionDiagnostics(index)
                    }
                    return
                }

                processAddressSearch(index, input, liveMode = true)
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
                val index = pendingSpeechIndex ?: return
                startGoogleVoiceRecognition(index)
            } else {
                val index = pendingSpeechIndex ?: return
                toggleLatvianRecording(index)
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startGoogleVoiceRecognition(index: Int) {
        pendingSpeechIndex = index
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

    private fun toggleLatvianRecording(index: Int) {
        if (currentLvRecordingIndex != null) {
            if (currentLvRecordingIndex == index) {
                stopLatvianCloudRecordingAndTranscribe(index)
            } else {
                toast("Сначала останови текущую запись")
            }
            return
        }

        startLatvianCloudRecording(index)
    }

    private fun startLatvianCloudRecording(index: Int) {
        try {
            wavRecorder.start()
            currentLvRecordingIndex = index
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            buttonForLv(index).text = "STOP"
            toast("Запись пункта...")
        } catch (_: Exception) {
            currentLvRecordingIndex = null
            buttonForLv(index).text = "LV"
            toast("Ошибка записи")
        }
    }

    private fun stopLatvianCloudRecordingAndTranscribe(index: Int) {
        val audioFile = try {
            wavRecorder.stop()
        } catch (_: Exception) {
            currentLvRecordingIndex = null
            if (index in sections.indices) buttonForLv(index).text = "LV"
            toast("Ошибка остановки записи")
            return
        }

        currentLvRecordingIndex = null
        if (index in sections.indices) buttonForLv(index).text = "LV"
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 140)

        executor.execute {
            try {
                val transcript = cloudTranscriber.transcribe(audioFile)
                runOnUiThread {
                    if (transcript.isBlank()) {
                        toast("Облако не распознало адрес")
                        return@runOnUiThread
                    }
                    if (index !in sections.indices) return@runOnUiThread
                    setInputText(index, transcript)
                    processAddressSearch(index, transcript)
                }
            } catch (_: Exception) {
                runOnUiThread { toast("Ошибка облака") }
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun safeStopLatvianRecording() {
        val index = currentLvRecordingIndex ?: return
        try {
            wavRecorder.stop().delete()
        } catch (_: Exception) {
        }
        if (index in sections.indices) {
            buttonForLv(index).text = "LV"
        }
        currentLvRecordingIndex = null
    }

    private fun processObjectSearch(index: Int, input: String) {
        executor.execute {
            val match = landmarkMatcher.findBestMatch(input)
            val accepted = match.isConfident && !match.address.isBlank() &&
                    match.latitude != null && match.longitude != null

            runOnUiThread {
                if (index !in states.indices || index !in sections.indices) return@runOnUiThread

                val state = states[index]
                val views = sections[index]

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
                clearSectionSuggestions(index)

                maybeCalculateDistance()
            }
        }
    }

    private fun processAddressSearch(index: Int, input: String, liveMode: Boolean = false) {
        executor.execute {
            val processed = addressPreprocessor.process(input)
            if (!processed.isValid) {
                runOnUiThread {
                    if (index !in states.indices || index !in sections.indices) return@runOnUiThread

                    val state = states[index]
                    val views = sections[index]
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
                    clearSectionSuggestions(index)
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
                    if (index !in states.indices || index !in sections.indices) return@runOnUiThread

                    val state = states[index]
                    val views = sections[index]
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
                    clearSectionSuggestions(index)
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
                if (index !in states.indices || index !in sections.indices) return@runOnUiThread

                val state = states[index]
                val views = sections[index]
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
                    views.adapter.submitList(others)
                } else {
                    clearSectionSuggestions(index)
                }

                maybeCalculateDistance()
            }
        }
    }

    private fun onSuggestionSelected(index: Int, suggestion: AddressSuggestion) {
        val parsed = parseDisplayAddress(suggestion.street)
        if (parsed == null || parsed.houseNumber.isNullOrBlank()) {
            if (index !in states.indices || index !in sections.indices) return

            val state = states[index]
            val views = sections[index]
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
                if (index !in states.indices || index !in sections.indices) return@runOnUiThread

                val state = states[index]
                val views = sections[index]
                state.finalAddress = finalAddress
                state.latitude = validationResult.latitude
                state.longitude = validationResult.longitude
                state.validation = validationResult
                state.confirmed = validationAllowsConfirm(validationResult)

                setInputText(index, finalAddress)
                views.tvResult.text = "$finalAddress, Latvija"
                applyValidationUi(views, validationResult)
                clearSectionSuggestions(index)
                maybeCalculateDistance()
            }
        }
    }

    private fun maybeCalculateDistance() {
        if (states.size < 2) {
            clearDistanceResult()
            return
        }

        if (states.any { !it.confirmed || it.latitude == null || it.longitude == null }) {
            clearDistanceResult()
            return
        }

        val segments = states.zipWithNext()
        val directKm = segments.sumOf { (a, b) ->
            haversineKm(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!)
        }
        tvDirectDistance.text = "По прямой: ${formatKm(directKm)}"

        tvRoadDistance.text = "По дороге: расчёт..."
        tvRoadDuration.text = "Время: расчёт..."

        executor.execute {
            var roadKm = 0.0
            var roadMinutes = 0.0

            for ((a, b) in segments) {
                val route = calculateRoadRoute(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!)
                    ?: run {
                        runOnUiThread {
                            tvRoadDistance.text = "По дороге: ошибка"
                            tvRoadDuration.text = "Время: ошибка"
                        }
                        return@execute
                    }

                roadKm += route.distanceKm
                roadMinutes += route.durationMinutes
            }

            runOnUiThread {
                tvRoadDistance.text = "По дороге: ${formatKm(roadKm)}"
                tvRoadDuration.text = "Время: ${formatMinutes(roadMinutes)}"
            }
        }
    }

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
        return SpannableString(fullText).apply {
            setSpan(
                ForegroundColorSpan(statusColor),
                prefix.length,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
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

    private fun clearSectionSuggestions(index: Int) {
        if (index in sections.indices) {
            sections[index].adapter.submitList(emptyList())
        }
    }

    private fun resetSectionDiagnostics(index: Int) {
        if (index !in sections.indices || index !in states.indices) return

        val views = sections[index]
        val state = states[index]
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

    private fun setInputText(index: Int, value: String) {
        if (index !in sections.indices || index !in states.indices) return
        val edit = sections[index].input
        states[index].suppressWatcher = true
        edit.setText(value)
        edit.setSelection(value.length)
        states[index].suppressWatcher = false
    }

    private fun buttonForLv(index: Int): Button = sections[index].btnLv

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}