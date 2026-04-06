package com.riga.voicewaze.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.riga.voicewaze.R
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class VoiceMode {
        ADDRESS_LV,
        OBJECT_RU
    }

    private data class DisplayAddressParts(
        val street: String,
        val houseNumber: String?,
        val city: String
    )

    private lateinit var btnMicRu: Button
    private lateinit var btnMicLv: Button
    private lateinit var etInput: EditText
    private lateinit var tvPrepared: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvValidation: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnReset: Button
    private lateinit var btnSearch: Button
    private lateinit var btnWaze: Button
    private lateinit var rvSuggestions: RecyclerView

    private lateinit var streetMatcher: StreetMatcher
    private lateinit var landmarkMatcher: LandmarkMatcher
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var wavRecorder: WavRecorder
    private lateinit var cloudTranscriber: GoogleCloudLatvianTranscriber
    private lateinit var houseValidator: NominatimHouseValidator
    private lateinit var addressPreprocessor: AddressPreprocessor

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)

    private var currentMode: VoiceMode = VoiceMode.ADDRESS_LV
    private var autoOpenAfterVoice: Boolean = false
    private var lastAddress: String = ""
    private var lastConfidencePercent: Int = 0
    private var suppressTextWatcher: Boolean = false
    private var lastAddressNeedsHouseValidation: Boolean = false
    private var lastHouseValidationResult: HouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
    private var isLatvianCloudRecording: Boolean = false
    private var isRecordingDotVisible: Boolean = true
    private var lastProcessedQuery: ProcessedAddressQuery = ProcessedAddressQuery("", "", "", null, "Rīga")

    private var recordingDotView: TextView? = null

    private val recordingBlinkRunnable = object : Runnable {
        override fun run() {
            if (!isLatvianCloudRecording) return

            isRecordingDotVisible = !isRecordingDotVisible
            recordingDotView?.visibility = if (isRecordingDotVisible) View.VISIBLE else View.INVISIBLE

            if (isLatvianCloudRecording) {
                uiHandler.postDelayed(this, 500L)
            }
        }
    }

    private val buttonLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (isLatvianCloudRecording) {
            positionRecordingDot()
        }
    }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

                if (list.isNullOrEmpty()) {
                    autoOpenAfterVoice = false
                    toast("Речь не распознана")
                    return@registerForActivityResult
                }

                val spokenText = list.firstOrNull()?.trim().orEmpty()
                if (spokenText.isBlank()) {
                    autoOpenAfterVoice = false
                    toast("Речь не распознана")
                    return@registerForActivityResult
                }

                suppressTextWatcher = true
                etInput.setText(spokenText)
                etInput.setSelection(spokenText.length)
                suppressTextWatcher = false

                handleSearch(spokenText, autoOpenAfterVoice)
            } catch (e: Exception) {
                autoOpenAfterVoice = false
                tvResult.text = "Ошибка распознавания: ${e.message ?: "unknown"}"
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                when (currentMode) {
                    VoiceMode.OBJECT_RU -> startGoogleVoiceRecognition()
                    VoiceMode.ADDRESS_LV -> toggleLatvianCloudRecording()
                }
            } else {
                autoOpenAfterVoice = false
                toast("Нет доступа к микрофону")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        btnMicRu = findViewById(R.id.btnMicRu)
        btnMicLv = findViewById(R.id.btnMicLv)
        etInput = findViewById(R.id.etInput)
        tvPrepared = findViewById(R.id.tvPrepared)
        tvResult = findViewById(R.id.tvResult)
        tvValidation = findViewById(R.id.tvValidation)
        tvCoords = findViewById(R.id.tvCoords)
        btnReset = findViewById(R.id.btnReset)
        btnSearch = findViewById(R.id.btnSearch)
        btnWaze = findViewById(R.id.btnWaze)
        rvSuggestions = findViewById(R.id.rvSuggestions)

        val streetRepository = StreetRepository(this)
        streetMatcher = StreetMatcher(streetRepository)
        landmarkMatcher = LandmarkMatcher()
        wavRecorder = WavRecorder(cacheDir)
        cloudTranscriber = GoogleCloudLatvianTranscriber()
        houseValidator = NominatimHouseValidator()
        addressPreprocessor = AddressPreprocessor()

        suggestionAdapter = SuggestionAdapter { suggestion ->
            onSuggestionSelected(suggestion)
        }

        rvSuggestions.layoutManager = LinearLayoutManager(this)
        rvSuggestions.adapter = suggestionAdapter

        ensureRecordingDotView()
        btnMicLv.addOnLayoutChangeListener(buttonLayoutListener)

        setVoiceMode(VoiceMode.ADDRESS_LV)
        clearDiagnosticLines()

        btnMicRu.setOnClickListener {
            if (isLatvianCloudRecording) {
                safeStopLatvianRecording()
            }
            setVoiceMode(VoiceMode.OBJECT_RU)
            autoOpenAfterVoice = true
            clearSuggestions()
            ensureMicPermissionAndStart()
        }

        btnMicLv.setOnClickListener {
            setVoiceMode(VoiceMode.ADDRESS_LV)
            autoOpenAfterVoice = true
            ensureMicPermissionAndStart()
        }

        btnReset.setOnClickListener {
            resetInputAndResults()
        }

        btnSearch.setOnClickListener {
            autoOpenAfterVoice = false
            handleSearch(etInput.text.toString(), false)
        }

        btnWaze.setOnClickListener {
            if (lastAddress.isBlank()) {
                toast("Сначала найди адрес")
                return@setOnClickListener
            }

            validateCurrentAddressAndOpenWaze()
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return

                val input = s?.toString()?.trim().orEmpty()

                if (currentMode != VoiceMode.ADDRESS_LV) {
                    clearSuggestions()
                    return
                }

                if (input.length < 3) {
                    clearSuggestions()
                    if (input.isBlank()) {
                        tvResult.text = ""
                        clearDiagnosticLines()
                        lastAddress = ""
                        lastConfidencePercent = 0
                        lastAddressNeedsHouseValidation = false
                        lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
                    }
                    return
                }

                updateLiveSuggestions(input)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        btnMicLv.removeOnLayoutChangeListener(buttonLayoutListener)
        stopRecordingIndicator()
        safeStopLatvianRecording()
        toneGenerator.release()
        executor.shutdownNow()
    }

    private fun ensureRecordingDotView() {
        if (recordingDotView != null) return

        val root = findViewById<ViewGroup>(android.R.id.content)
        val dotView = TextView(this).apply {
            text = "●"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(dotView)
        recordingDotView = dotView
    }

    private fun positionRecordingDot() {
        val dot = recordingDotView ?: return
        val root = findViewById<ViewGroup>(android.R.id.content)

        btnMicLv.post {
            val buttonLocation = IntArray(2)
            val rootLocation = IntArray(2)

            btnMicLv.getLocationOnScreen(buttonLocation)
            root.getLocationOnScreen(rootLocation)

            val relativeX = buttonLocation[0] - rootLocation[0]
            val relativeY = buttonLocation[1] - rootLocation[1]

            if (dot.measuredWidth == 0 || dot.measuredHeight == 0) {
                dot.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }

            val dotWidth = dot.measuredWidth
            val dotHeight = dot.measuredHeight

            val dotCenterX = relativeX + (btnMicLv.width * 0.83f)
            val dotCenterY = relativeY + (btnMicLv.height / 2f)

            dot.x = dotCenterX - (dotWidth / 2f)
            dot.y = dotCenterY - (dotHeight / 2f)
        }
    }

    private fun soundStartRecording() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
    }

    private fun soundStopRecording() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 140)
    }

    private fun setVoiceMode(mode: VoiceMode) {
        currentMode = mode
        btnMicRu.text = "Объекты"
        btnMicLv.text = "Улицы"

        when (mode) {
            VoiceMode.ADDRESS_LV -> {
                btnMicLv.alpha = 1.0f
                btnMicRu.alpha = 0.65f
                etInput.hint = "Введите адрес"
            }
            VoiceMode.OBJECT_RU -> {
                btnMicRu.alpha = 1.0f
                btnMicLv.alpha = 0.65f
                etInput.hint = "Введите объект"
            }
        }
    }

    private fun startRecordingIndicator() {
        ensureRecordingDotView()
        stopRecordingIndicator()
        isRecordingDotVisible = true
        positionRecordingDot()
        recordingDotView?.visibility = View.VISIBLE
        uiHandler.postDelayed(recordingBlinkRunnable, 500L)
    }

    private fun stopRecordingIndicator() {
        uiHandler.removeCallbacks(recordingBlinkRunnable)
        isRecordingDotVisible = true
        recordingDotView?.visibility = View.GONE
    }

    private fun ensureMicPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            when (currentMode) {
                VoiceMode.OBJECT_RU -> startGoogleVoiceRecognition()
                VoiceMode.ADDRESS_LV -> toggleLatvianCloudRecording()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleSearch(text: String, autoOpen: Boolean) {
        val input = text.trim()

        if (input.isBlank()) {
            tvResult.text = if (currentMode == VoiceMode.ADDRESS_LV) "Введите адрес" else "Введите объект"
            clearDiagnosticLines()
            lastAddress = ""
            lastConfidencePercent = 0
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
            autoOpenAfterVoice = false
            clearSuggestions()
            return
        }

        tvResult.text = "Поиск..."
        clearDiagnosticLines()
        setBusyState()

        executor.execute {
            try {
                when (currentMode) {
                    VoiceMode.ADDRESS_LV -> processAddressSearch(input, autoOpen)
                    VoiceMode.OBJECT_RU -> processObjectSearch(input, autoOpen)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    finishBusyState()
                    autoOpenAfterVoice = false
                    tvResult.text = "Ошибка обработки: ${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun processAddressSearch(input: String, autoOpen: Boolean) {
        val processed = addressPreprocessor.process(input)
        val matcherInput = processed.matcherInput.ifBlank { input }

        val matches = streetMatcher.findTopMatchesDetailed(
            input = matcherInput,
            preferredCity = processed.city,
            limit = 10
        )

        if (matches.isEmpty()) {
            runOnUiThread {
                finishBusyState()
                clearSuggestions()
                clearDiagnosticLines()
                lastAddress = ""
                lastConfidencePercent = 0
                lastAddressNeedsHouseValidation = false
                lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Улица не найдена")
                tvResult.text = "Улица не найдена"
                autoOpenAfterVoice = false
            }
            return
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
            finishBusyState()
            lastProcessedQuery = processed
            lastAddress = finalAddress
            lastConfidencePercent = best.matchPercent
            lastAddressNeedsHouseValidation = processed.houseNumber != null
            lastHouseValidationResult = validationResult

            tvPrepared.text = if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
            tvResult.text = buildPercentText(
                mainText = "$finalAddress, Latvija",
                percent = best.matchPercent
            )
            tvValidation.text = "Проверка: ${mapValidationStatus(validationResult.status)}"
            tvCoords.text = formatCoordsLine(validationResult)

            suggestionAdapter.submitList(others)

            if (autoOpen &&
                lastAddress.isNotBlank() &&
                lastConfidencePercent >= 85 &&
                validationResult.status == HouseValidationStatus.VALID
            ) {
                openWaze(lastAddress)
            }

            autoOpenAfterVoice = false
        }
    }

    private fun processObjectSearch(input: String, autoOpen: Boolean) {
        val match = landmarkMatcher.findBestMatch(input)

        runOnUiThread {
            finishBusyState()
            clearDiagnosticLines()
            lastAddress = match.address
            lastConfidencePercent = match.matchPercent
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)

            tvResult.text = if (match.address.isBlank()) {
                buildPercentText(mainText = "Объект не найден", percent = match.matchPercent)
            } else {
                buildPercentText(mainText = match.address, percent = match.matchPercent)
            }

            if (autoOpen && lastAddress.isNotBlank() && lastConfidencePercent >= 85) {
                openWaze(lastAddress)
            }

            autoOpenAfterVoice = false
        }
    }

    private fun updateLiveSuggestions(input: String) {
        executor.execute {
            try {
                val processed = addressPreprocessor.process(input)
                val matcherInput = processed.matcherInput.ifBlank { input }

                val matches = streetMatcher.findTopMatchesDetailed(
                    input = matcherInput,
                    preferredCity = processed.city,
                    limit = 10
                )

                if (matches.isEmpty()) {
                    runOnUiThread {
                        clearSuggestions()
                        clearDiagnosticLines()
                        tvResult.text = "Улица не найдена"
                        lastAddress = ""
                        lastConfidencePercent = 0
                        lastAddressNeedsHouseValidation = false
                        lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Улица не найдена")
                    }
                    return@execute
                }

                val bestOriginal = matches.first()
                val bestStreet = normalizeStreetForDisplay(bestOriginal.street)
                val bestCity = if (bestOriginal.city.isBlank()) processed.city else bestOriginal.city
                val validationResult = validateHouseIfNeeded(
                    street = bestStreet,
                    houseNumber = processed.houseNumber,
                    city = bestCity
                )
                val canonicalHouse = validationResult.canonicalHouseNumber ?: processed.houseNumber
                val bestAddress = buildFullAddress(bestStreet, canonicalHouse, bestCity)

                val preparedSuggestions = matches.map { suggestion ->
                    val street = normalizeStreetForDisplay(suggestion.street)
                    val city = if (suggestion.city.isBlank()) processed.city else suggestion.city
                    suggestion.copy(
                        street = buildFullAddress(street, processed.houseNumber, city),
                        city = ""
                    )
                }

                runOnUiThread {
                    lastProcessedQuery = processed
                    lastAddress = bestAddress
                    lastConfidencePercent = bestOriginal.matchPercent
                    lastAddressNeedsHouseValidation = processed.houseNumber != null
                    lastHouseValidationResult = validationResult

                    tvPrepared.text = if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
                    tvResult.text = buildPercentText(mainText = "$bestAddress, Latvija", percent = bestOriginal.matchPercent)
                    tvValidation.text = "Проверка: ${mapValidationStatus(validationResult.status)}"
                    tvCoords.text = formatCoordsLine(validationResult)

                    suggestionAdapter.submitList(preparedSuggestions.drop(1))
                }
            } catch (_: Exception) {
                runOnUiThread {
                    clearSuggestions()
                    clearDiagnosticLines()
                    lastAddress = ""
                    lastConfidencePercent = 0
                    lastAddressNeedsHouseValidation = false
                    lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.CHECK_FAILED, "Не удалось проверить дом через интернет")
                    tvResult.text = "Не удалось проверить дом через интернет"
                    tvValidation.text = "Проверка: Ошибка"
                }
            }
        }
    }

    private fun onSuggestionSelected(suggestion: AddressSuggestion) {
        val parsed = parseDisplayAddress(suggestion.street)
        if (parsed == null) {
            lastAddress = suggestion.street
            lastConfidencePercent = suggestion.matchPercent
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
            tvResult.text = buildPercentText(mainText = suggestion.street, percent = suggestion.matchPercent)
            tvValidation.text = ""
            tvCoords.text = ""
            return
        }

        if (parsed.houseNumber.isNullOrBlank()) {
            lastAddress = suggestion.street
            lastConfidencePercent = suggestion.matchPercent
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
            tvResult.text = buildPercentText(mainText = suggestion.street, percent = suggestion.matchPercent)
            tvValidation.text = ""
            tvCoords.text = ""
            return
        }

        tvResult.text = "Проверка дома..."
        setBusyState()

        executor.execute {
            val validationResult = validateHouseIfNeeded(
                street = parsed.street,
                houseNumber = parsed.houseNumber,
                city = parsed.city
            )
            val canonicalHouse = validationResult.canonicalHouseNumber ?: parsed.houseNumber
            val finalAddress = buildFullAddress(parsed.street, canonicalHouse, parsed.city)

            runOnUiThread {
                finishBusyState()
                lastAddress = finalAddress
                lastConfidencePercent = suggestion.matchPercent
                lastAddressNeedsHouseValidation = true
                lastHouseValidationResult = validationResult
                tvResult.text = buildPercentText(mainText = "$finalAddress, Latvija", percent = suggestion.matchPercent)
                tvValidation.text = "Проверка: ${mapValidationStatus(validationResult.status)}"
                tvCoords.text = formatCoordsLine(validationResult)
            }
        }
    }

    private fun resetInputAndResults() {
        safeStopLatvianRecording()
        suppressTextWatcher = true
        etInput.setText("")
        suppressTextWatcher = false
        lastAddress = ""
        lastConfidencePercent = 0
        lastAddressNeedsHouseValidation = false
        lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
        tvResult.text = ""
        clearDiagnosticLines()
        clearSuggestions()
        setVoiceMode(currentMode)
    }

    private fun clearDiagnosticLines() {
        tvPrepared.text = ""
        tvValidation.text = ""
        tvCoords.text = ""
    }

    private fun clearSuggestions() {
        suggestionAdapter.submitList(emptyList())
    }

    private fun setBusyState() {
        btnReset.isEnabled = false
        btnSearch.isEnabled = false
        btnMicRu.isEnabled = false
        btnMicLv.isEnabled = false
        btnWaze.isEnabled = false
    }

    private fun finishBusyState() {
        btnReset.isEnabled = true
        btnSearch.isEnabled = true
        btnMicRu.isEnabled = true
        btnMicLv.isEnabled = true
        btnWaze.isEnabled = true
    }

    private fun startGoogleVoiceRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи объект")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            }

            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            autoOpenAfterVoice = false
            toast("Голосовой ввод недоступен")
        } catch (e: Exception) {
            autoOpenAfterVoice = false
            tvResult.text = "Ошибка запуска: ${e.message ?: "unknown"}"
        }
    }

    private fun toggleLatvianCloudRecording() {
        if (isLatvianCloudRecording) {
            stopLatvianCloudRecordingAndTranscribe()
        } else {
            startLatvianCloudRecording()
        }
    }

    private fun startLatvianCloudRecording() {
        try {
            wavRecorder.start()
            isLatvianCloudRecording = true
            soundStartRecording()
            startRecordingIndicator()
            tvResult.text = "Запись адреса... Нажми ещё раз для остановки"
            clearDiagnosticLines()
            clearSuggestions()
        } catch (e: Exception) {
            isLatvianCloudRecording = false
            stopRecordingIndicator()
            tvResult.text = "Ошибка записи: ${e.message ?: "unknown"}"
        }
    }

    private fun stopLatvianCloudRecordingAndTranscribe() {
        val audioFile = try {
            wavRecorder.stop()
        } catch (e: Exception) {
            isLatvianCloudRecording = false
            stopRecordingIndicator()
            tvResult.text = "Ошибка остановки записи: ${e.message ?: "unknown"}"
            return
        }

        isLatvianCloudRecording = false
        soundStopRecording()
        stopRecordingIndicator()
        tvResult.text = "Отправка в облако..."
        setBusyState()

        executor.execute {
            try {
                val transcript = cloudTranscriber.transcribe(audioFile)

                runOnUiThread {
                    finishBusyState()

                    if (transcript.isBlank()) {
                        autoOpenAfterVoice = false
                        tvResult.text = "Облако не распознало адрес"
                        return@runOnUiThread
                    }

                    suppressTextWatcher = true
                    etInput.setText(transcript)
                    etInput.setSelection(transcript.length)
                    suppressTextWatcher = false

                    handleSearch(transcript, autoOpenAfterVoice)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    finishBusyState()
                    autoOpenAfterVoice = false
                    tvResult.text = "Ошибка облака: ${e.message ?: "unknown"}"
                }
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun safeStopLatvianRecording() {
        if (!isLatvianCloudRecording) {
            stopRecordingIndicator()
            return
        }

        try {
            wavRecorder.stop().delete()
        } catch (_: Exception) {
        }

        isLatvianCloudRecording = false
        stopRecordingIndicator()
    }

    private fun buildPercentText(mainText: String, percent: Int): CharSequence {
        val percentText = " ($percent%)"
        val builder = SpannableStringBuilder()
        builder.append(mainText)
        val start = builder.length
        builder.append(percentText)
        builder.setSpan(
            ForegroundColorSpan(percentColor(percent)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    private fun percentColor(percent: Int): Int {
        return when {
            percent >= 85 -> Color.parseColor("#4CAF50")
            percent >= 70 -> Color.parseColor("#FFD54F")
            else -> Color.parseColor("#F48FB1")
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
            return HouseValidationResult(HouseValidationStatus.VALID)
        }

        return houseValidator.validateHouse(street = street, houseNumber = houseNumber, city = city)
    }

    private fun parseDisplayAddress(address: String): DisplayAddressParts? {
        val parts = address.split(",").map { it.trim() }
        if (parts.size < 2) return null

        val firstPart = parts[0]
        val houseNumber = extractHouseNumber(firstPart)
        val street = if (houseNumber.isNullOrBlank()) {
            firstPart
        } else {
            firstPart.removeSuffix(" $houseNumber").trim()
        }
        val city = parts[1]

        if (street.isBlank() || city.isBlank()) return null

        return DisplayAddressParts(street = street, houseNumber = houseNumber, city = city)
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

    private fun validateCurrentAddressAndOpenWaze() {
        if (!lastAddressNeedsHouseValidation) {
            openWaze(lastAddress)
            return
        }

        when (lastHouseValidationResult.status) {
            HouseValidationStatus.VALID,
            HouseValidationStatus.RELATED_FOUND,
            HouseValidationStatus.CHECK_FAILED -> {
                openWaze(lastAddress)
            }
            HouseValidationStatus.NOT_FOUND -> {
                tvValidation.text = "Проверка: Отсутствует"
            }
        }
    }

    private fun buildWazeQuery(address: String): String {
        val parts = address.split(",").map { it.trim() }
        if (parts.isEmpty()) return address

        val firstPart = parts[0]
        val city = parts.getOrNull(1).orEmpty()
        val cleanCity = city.replace("Latvija", "", ignoreCase = true).trim()

        return if (cleanCity.isBlank()) firstPart else "$firstPart, $cleanCity"
    }

    private fun openWaze(address: String) {
        try {
            val wazeQuery = buildWazeQuery(address)
            val uri = Uri.parse("https://waze.com/ul?q=${Uri.encode(wazeQuery)}&navigate=yes")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Не удалось открыть Waze")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
