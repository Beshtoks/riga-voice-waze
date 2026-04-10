package com.riga.voicewaze.ui.main

import android.Manifest
import android.app.AlertDialog
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
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.riga.voicewaze.R
import com.riga.voicewaze.data.local.LandmarkRepository
import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.domain.cloud.GoogleCloudLatvianTranscriber
import com.riga.voicewaze.domain.cloud.WavRecorder
import com.riga.voicewaze.domain.landmark.LandmarkEntry
import com.riga.voicewaze.domain.landmark.LandmarkMatcher
import com.riga.voicewaze.domain.matcher.AddressSuggestion
import com.riga.voicewaze.domain.matcher.StreetMatcher
import com.riga.voicewaze.domain.preprocessor.AddressPreprocessor
import com.riga.voicewaze.domain.preprocessor.ProcessedAddressQuery
import com.riga.voicewaze.domain.validator.HouseValidationResult
import com.riga.voicewaze.domain.validator.HouseValidationStatus
import com.riga.voicewaze.domain.validator.NominatimHouseValidator
import com.riga.voicewaze.ui.map.MapPickerActivity
import com.riga.voicewaze.ui.distance.DistanceActivity
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
    private lateinit var landmarkRepository: LandmarkRepository
    private lateinit var landmarkMatcher: LandmarkMatcher
    private lateinit var wavRecorder: WavRecorder
    private lateinit var cloudTranscriber: GoogleCloudLatvianTranscriber
    private lateinit var houseValidator: NominatimHouseValidator
    private lateinit var addressPreprocessor: AddressPreprocessor
    private lateinit var suggestionAdapter: SuggestionAdapter

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    private var currentMode: VoiceMode = VoiceMode.ADDRESS_LV
    private var autoOpenAfterVoice: Boolean = false
    private var lastAddress: String = ""
    private var suppressTextWatcher: Boolean = false
    private var lastAddressNeedsHouseValidation: Boolean = false
    private var lastHouseValidationResult: HouseValidationResult =
        HouseValidationResult(HouseValidationStatus.VALID)
    private var isLatvianCloudRecording: Boolean = false
    private var isRecordingDotVisible: Boolean = true
    private var lastProcessedQuery: ProcessedAddressQuery =
        ProcessedAddressQuery("", "", "", null, "Rīga", false, null)

    private var recordingDotView: TextView? = null
    private var activeLandmarkDialog: AlertDialog? = null
    private var activeLandmarkSpokenEdit: EditText? = null
    private var activeLandmarkDisplayEdit: EditText? = null
    private var activeLandmarkAddressEdit: EditText? = null
    private var activeLandmarkCoordsText: TextView? = null
    private var activeLandmarkLatitude: Double? = null
    private var activeLandmarkLongitude: Double? = null

    private val recordingBlinkRunnable = object : Runnable {
        override fun run() {
            if (!isLatvianCloudRecording) return

            isRecordingDotVisible = !isRecordingDotVisible
            recordingDotView?.visibility =
                if (isRecordingDotVisible) View.VISIBLE else View.INVISIBLE

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

    private val mapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val data = result.data ?: return@registerForActivityResult
            val latitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LATITUDE, Double.NaN)
            val longitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LONGITUDE, Double.NaN)
            val displayName = data.getStringExtra(MapPickerActivity.EXTRA_DISPLAY_NAME).orEmpty()
            val address = data.getStringExtra(MapPickerActivity.EXTRA_ADDRESS).orEmpty()

            if (latitude.isNaN() || longitude.isNaN()) return@registerForActivityResult

            activeLandmarkLatitude = latitude
            activeLandmarkLongitude = longitude
            if (displayName.isNotBlank()) {
                activeLandmarkDisplayEdit?.setText(displayName)
                activeLandmarkDisplayEdit?.setSelection(
                    activeLandmarkDisplayEdit?.text?.length ?: 0
                )
            }
            if (address.isNotBlank()) {
                activeLandmarkAddressEdit?.setText(address)
                activeLandmarkAddressEdit?.setSelection(
                    activeLandmarkAddressEdit?.text?.length ?: 0
                )
            }
            activeLandmarkCoordsText?.text = formatLandmarkCoords(latitude, longitude)
        }

    private val createLandmarksDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult

            try {
                val json = landmarkRepository.exportToJsonString()
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                toast("Экспорт завершён")
            } catch (_: Exception) {
                toast("Ошибка экспорта")
            }
        }

    private val openLandmarksDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            try {
                val json = contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }.orEmpty()

                if (json.isBlank()) {
                    toast("Файл пустой")
                    return@registerForActivityResult
                }

                val imported = landmarkRepository.importFromJsonString(json)
                if (!imported) {
                    toast("Не удалось импортировать файл")
                    return@registerForActivityResult
                }

                reloadLandmarkMatcher()
                activeLandmarkDialog?.dismiss()
                showLandmarkListDialog()
                toast("Импорт завершён")
            } catch (_: Exception) {
                toast("Ошибка импорта")
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
        landmarkRepository = LandmarkRepository(this)
        landmarkMatcher = LandmarkMatcher(landmarkRepository)
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

        btnMicRu.setOnLongClickListener {
            showLandmarkListDialog()
            true
        }

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
            switchToAddressModeForManualInput()
            autoOpenAfterVoice = true
            ensureMicPermissionAndStart()
        }

        btnReset.setOnClickListener {
            handleResetButtonTap()
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

        btnWaze.setOnLongClickListener {
            startActivity(Intent(this, DistanceActivity::class.java))
            true
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

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
                        lastAddressNeedsHouseValidation = false
                        lastHouseValidationResult =
                            HouseValidationResult(HouseValidationStatus.VALID)
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

    private fun switchToAddressModeForManualInput() {
        setVoiceMode(VoiceMode.ADDRESS_LV)
        autoOpenAfterVoice = false
        etInput.isEnabled = true
        etInput.isFocusable = true
        etInput.isFocusableInTouchMode = true
        etInput.isCursorVisible = true
        etInput.requestFocus()
        clearSuggestions()
        if (etInput.text?.isBlank() != false) {
            tvResult.text = "Введите адрес"
            clearDiagnosticLines()
            lastAddress = ""
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
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
        recordingDotView?.visibility = View.GONE
        isRecordingDotVisible = true
    }

    private fun ensureMicPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            when (currentMode) {
                VoiceMode.OBJECT_RU -> startGoogleVoiceRecognition()
                VoiceMode.ADDRESS_LV -> toggleLatvianCloudRecording()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
        btnMicRu.isEnabled = false
        btnMicLv.isEnabled = false
        btnSearch.isEnabled = false
        btnReset.isEnabled = false
        btnWaze.isEnabled = false
        etInput.isEnabled = false
    }

    private fun finishBusyState() {
        btnMicRu.isEnabled = true
        btnMicLv.isEnabled = true
        btnSearch.isEnabled = true
        btnReset.isEnabled = true
        btnWaze.isEnabled = true
        etInput.isEnabled = true
    }

    private fun handleSearch(text: String, autoOpen: Boolean) {
        val input = text.trim()

        if (input.isBlank()) {
            tvResult.text =
                if (currentMode == VoiceMode.ADDRESS_LV) "Введите адрес" else "Введите объект"
            clearDiagnosticLines()
            lastAddress = ""
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
        if (!processed.isValid) {
            runOnUiThread {
                finishBusyState()
                clearSuggestions()
                clearDiagnosticLines()
                lastProcessedQuery = processed
                lastAddress = ""
                lastAddressNeedsHouseValidation = false
                lastHouseValidationResult = HouseValidationResult(
                    HouseValidationStatus.NOT_FOUND,
                    processed.errorMessage ?: "Адрес введён некорректно"
                )
                tvResult.text = processed.errorMessage ?: "Адрес введён некорректно"
                autoOpenAfterVoice = false
            }
            return
        }

        val matcherInput = processed.matcherInput

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
                lastAddressNeedsHouseValidation = false
                lastHouseValidationResult =
                    HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Улица не найдена")
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
            lastAddressNeedsHouseValidation = processed.houseNumber != null
            lastHouseValidationResult = validationResult

            tvPrepared.text =
                if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
            tvResult.text = "$finalAddress, Latvija"
            applyValidationUi(validationResult)

            suggestionAdapter.submitList(others)

            if (autoOpen && lastAddress.isNotBlank() && validationAllowsOpen(validationResult)) {
                openResolvedDestination(validationResult, lastAddress)
            }

            autoOpenAfterVoice = false
        }
    }

    private fun processObjectSearch(input: String, autoOpen: Boolean) {
        val match = landmarkMatcher.findBestMatch(input)
        val accepted = match.isConfident && match.address.isNotBlank()

        runOnUiThread {
            finishBusyState()
            clearDiagnosticLines()
            lastAddress = if (accepted) match.address else ""
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = if (accepted) {
                HouseValidationResult(
                    status = HouseValidationStatus.VALID,
                    latitude = match.latitude,
                    longitude = match.longitude
                )
            } else {
                HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Объект не найден")
            }

            tvResult.text = if (accepted) {
                match.address
            } else {
                "Объект не найден"
            }
            applyValidationUi(lastHouseValidationResult)

            if (autoOpen && accepted) {
                openResolvedDestination(lastHouseValidationResult, lastAddress)
            }

            autoOpenAfterVoice = false
        }
    }

    private fun updateLiveSuggestions(input: String) {
        executor.execute {
            try {
                val processed = addressPreprocessor.process(input)
                if (!processed.isValid) {
                    runOnUiThread {
                        clearSuggestions()
                        clearDiagnosticLines()
                        lastProcessedQuery = processed
                        lastAddress = ""
                        lastAddressNeedsHouseValidation = false
                        lastHouseValidationResult = HouseValidationResult(
                            HouseValidationStatus.NOT_FOUND,
                            processed.errorMessage ?: "Адрес введён некорректно"
                        )
                        tvResult.text = processed.errorMessage ?: "Адрес введён некорректно"
                    }
                    return@execute
                }

                val matcherInput = processed.matcherInput

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
                        lastAddressNeedsHouseValidation = false
                        lastHouseValidationResult =
                            HouseValidationResult(HouseValidationStatus.NOT_FOUND, "Улица не найдена")
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

                val preparedSuggestions = matches.drop(1).map { suggestion ->
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
                    lastAddressNeedsHouseValidation = processed.houseNumber != null
                    lastHouseValidationResult = validationResult

                    tvPrepared.text =
                        if (processed.displayText.isBlank()) "" else "Preprocessor: ${processed.displayText}"
                    tvResult.text = "$bestAddress, Latvija"
                    applyValidationUi(validationResult)

                    suggestionAdapter.submitList(preparedSuggestions)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    clearSuggestions()
                    clearDiagnosticLines()
                    lastAddress = ""
                    lastAddressNeedsHouseValidation = false
                    lastHouseValidationResult = HouseValidationResult(
                        HouseValidationStatus.CHECK_FAILED,
                        "Не удалось проверить дом через интернет"
                    )
                    tvResult.text = "Не удалось проверить дом через интернет"
                    applyValidationUi(lastHouseValidationResult)
                }
            }
        }
    }

    private fun onSuggestionSelected(suggestion: AddressSuggestion) {
        val parsed = parseDisplayAddress(suggestion.street)
        if (parsed == null) {
            lastAddress = suggestion.street
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
            tvResult.text = suggestion.street
            applyValidationUi(lastHouseValidationResult)
            return
        }

        if (parsed.houseNumber.isNullOrBlank()) {
            lastAddress = suggestion.street
            lastAddressNeedsHouseValidation = false
            lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
            tvResult.text = suggestion.street
            applyValidationUi(lastHouseValidationResult)
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
                lastAddressNeedsHouseValidation = true
                lastHouseValidationResult = validationResult
                tvResult.text = "$finalAddress, Latvija"
                applyValidationUi(validationResult)
            }
        }
    }

    private fun handleResetButtonTap() {
        val wasObjectMode = currentMode == VoiceMode.OBJECT_RU
        if (wasObjectMode) {
            setVoiceMode(VoiceMode.ADDRESS_LV)
        }
        resetInputAndResults()
        switchToAddressModeForManualInput()
    }

    private fun resetInputAndResults() {
        safeStopLatvianRecording()
        suppressTextWatcher = true
        etInput.setText("")
        suppressTextWatcher = false
        clearDiagnosticLines()
        clearSuggestions()
        lastAddress = ""
        lastAddressNeedsHouseValidation = false
        lastHouseValidationResult = HouseValidationResult(HouseValidationStatus.VALID)
        autoOpenAfterVoice = false
        tvResult.text =
            if (currentMode == VoiceMode.ADDRESS_LV) "Введите адрес" else "Введите объект"
    }

    private fun startGoogleVoiceRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи объект")
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

    private fun applyValidationUi(result: HouseValidationResult) {
        val statusText = mapValidationStatus(result.status)
        tvValidation.text = buildValidationText(statusText, validationColor(result.status))
        tvCoords.text =
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

    private fun validationAllowsOpen(result: HouseValidationResult): Boolean {
        return result.status == HouseValidationStatus.VALID ||
                result.status == HouseValidationStatus.RELATED_FOUND
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

    private fun validateHouseIfNeeded(
        street: String,
        houseNumber: String?,
        city: String
    ): HouseValidationResult {
        if (houseNumber.isNullOrBlank()) {
            return HouseValidationResult(
                HouseValidationStatus.NOT_FOUND,
                "Укажи номер дома"
            )
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
        if (lastAddress.isBlank()) return
        openResolvedDestination(lastHouseValidationResult, lastAddress)
    }

    private fun buildWazeQuery(address: String): String {
        val parts = address.split(",").map { it.trim() }
        if (parts.isEmpty()) return address

        val firstPart = parts[0]
        val city = parts.getOrNull(1).orEmpty()
        val cleanCity = city.replace("Latvija", "", ignoreCase = true).trim()

        return if (cleanCity.isBlank()) firstPart else "$firstPart, $cleanCity"
    }

    private fun openResolvedDestination(result: HouseValidationResult, address: String) {
        if (result.latitude != null && result.longitude != null) {
            openWazeByCoordinates(result.latitude, result.longitude)
        } else {
            openWazeByAddress(address)
        }
    }

    private fun openWazeByCoordinates(latitude: Double, longitude: Double) {
        try {
            val lat = String.format(Locale.US, "%.6f", latitude)
            val lon = String.format(Locale.US, "%.6f", longitude)
            val uri = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Не удалось открыть Waze")
        }
    }

    private fun openWazeByAddress(address: String) {
        try {
            val wazeQuery = buildWazeQuery(address)
            val uri = Uri.parse("https://waze.com/ul?q=${Uri.encode(wazeQuery)}&navigate=yes")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Не удалось открыть Waze")
        }
    }

    private fun showLandmarkListDialog() {
        activeLandmarkDialog?.dismiss()

        val landmarks = landmarkRepository.getAll()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            setBackgroundColor(Color.parseColor("#4A4A4A"))
        }

        val title = TextView(this).apply {
            text = "Редактор объектов"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        root.addView(title)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
            }
            setBackgroundColor(Color.parseColor("#666666"))
        })

        val scrollView = ScrollView(this)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (landmarks.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Список объектов пуст"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, dp(18), 0, dp(18))
            })
        } else {
            landmarks.forEach { landmark ->
                listContainer.addView(TextView(this).apply {
                    text = "${landmark.spokenPhrase} → ${landmark.displayName}"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(0, dp(14), 0, dp(14))
                    setOnClickListener {
                        activeLandmarkDialog?.dismiss()
                        showLandmarkEditDialog(landmark)
                    }
                })
            }
        }

        scrollView.addView(listContainer)

        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            weight = 1f
        }
        root.addView(scrollView, scrollParams)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
            setBackgroundColor(Color.parseColor("#666666"))
        })

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        fun createBottomButton(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 13f
                maxLines = 1
                isSingleLine = true
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        val closeButton = createBottomButton("Закрыть") {
            dialog.dismiss()
        }
        val exportButton = createBottomButton("Экспорт") {
            dialog.dismiss()
            exportLandmarks()
        }
        val importButton = createBottomButton("Импорт") {
            dialog.dismiss()
            importLandmarks()
        }
        val addButton = createBottomButton("Добавить") {
            dialog.dismiss()
            showLandmarkEditDialog(null)
        }

        buttonsRow.addView(closeButton)
        buttonsRow.addView(exportButton)
        buttonsRow.addView(importButton)
        buttonsRow.addView(addButton)
        root.addView(buttonsRow)

        dialog.setOnDismissListener {
            if (activeLandmarkDialog === dialog) {
                activeLandmarkDialog = null
            }
        }

        activeLandmarkDialog = dialog
        dialog.show()

        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun exportLandmarks() {
        createLandmarksDocumentLauncher.launch("riga_voice_waze_objects.json")
    }

    private fun importLandmarks() {
        openLandmarksDocumentLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun showLandmarkEditDialog(existing: LandmarkEntry?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val spokenEdit = EditText(this).apply {
            hint = "Что говорю"
            setText(existing?.spokenPhrase.orEmpty())
        }
        val displayEdit = EditText(this).apply {
            hint = "Название объекта"
            setText(existing?.displayName.orEmpty())
        }
        val addressEdit = EditText(this).apply {
            hint = "Адрес объекта"
            setText(existing?.address.orEmpty())
            minLines = 2
        }
        val coordsText = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 15f
            text = if (existing?.latitude != null && existing.longitude != null) {
                formatLandmarkCoords(existing.latitude, existing.longitude)
            } else {
                "Координаты не выбраны"
            }
        }
        val mapButton = Button(this).apply {
            text = "Выбрать на карте"
            setOnClickListener {
                activeLandmarkSpokenEdit = spokenEdit
                activeLandmarkDisplayEdit = displayEdit
                activeLandmarkAddressEdit = addressEdit
                activeLandmarkCoordsText = coordsText
                activeLandmarkLatitude = existing?.latitude
                activeLandmarkLongitude = existing?.longitude
                val intent = Intent(this@MainActivity, MapPickerActivity::class.java)
                if (activeLandmarkLatitude != null && activeLandmarkLongitude != null) {
                    intent.putExtra(
                        MapPickerActivity.EXTRA_INITIAL_LATITUDE,
                        activeLandmarkLatitude!!
                    )
                    intent.putExtra(
                        MapPickerActivity.EXTRA_INITIAL_LONGITUDE,
                        activeLandmarkLongitude!!
                    )
                }
                mapPickerLauncher.launch(intent)
            }
        }

        container.addView(spokenEdit)
        addDialogSpacing(container)
        container.addView(displayEdit)
        addDialogSpacing(container)
        container.addView(addressEdit)
        addDialogSpacing(container)
        container.addView(coordsText)
        addDialogSpacing(container)
        container.addView(mapButton)

        activeLandmarkLatitude = existing?.latitude
        activeLandmarkLongitude = existing?.longitude

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Добавить объект" else "Изменить объект")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .apply {
                if (existing != null) {
                    setNeutralButton("Удалить", null)
                }
            }
            .create()

        activeLandmarkDialog = dialog

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spokenPhrase = spokenEdit.text.toString().trim()
                val displayName = displayEdit.text.toString().trim()
                val address = addressEdit.text.toString().trim()
                val latitude = activeLandmarkLatitude
                val longitude = activeLandmarkLongitude

                when {
                    spokenPhrase.isBlank() -> {
                        spokenEdit.error = "Укажи фразу"
                    }
                    displayName.isBlank() -> {
                        displayEdit.error = "Укажи название"
                    }
                    address.isBlank() -> {
                        addressEdit.error = "Укажи адрес"
                    }
                    latitude == null || longitude == null -> {
                        toast("Сначала выбери точку на карте")
                    }
                    landmarkRepository.hasDuplicateSpokenPhrase(spokenPhrase, existing?.id) -> {
                        spokenEdit.error = "Такая фраза уже существует"
                    }
                    else -> {
                        landmarkRepository.save(
                            LandmarkEntry(
                                id = existing?.id ?: landmarkRepository.nextId(),
                                spokenPhrase = spokenPhrase,
                                displayName = displayName,
                                address = address,
                                latitude = latitude,
                                longitude = longitude
                            )
                        )
                        reloadLandmarkMatcher()
                        dialog.dismiss()
                        showLandmarkListDialog()
                    }
                }
            }

            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    landmarkRepository.delete(existing.id)
                    reloadLandmarkMatcher()
                    dialog.dismiss()
                    showLandmarkListDialog()
                }
            }
        }

        dialog.setOnDismissListener {
            if (activeLandmarkDialog === dialog) {
                activeLandmarkDialog = null
            }
            activeLandmarkSpokenEdit = null
            activeLandmarkDisplayEdit = null
            activeLandmarkAddressEdit = null
            activeLandmarkCoordsText = null
            activeLandmarkLatitude = null
            activeLandmarkLongitude = null
        }

        dialog.show()
    }

    private fun reloadLandmarkMatcher() {
        landmarkMatcher = LandmarkMatcher(landmarkRepository)
    }

    private fun formatLandmarkCoords(latitude: Double, longitude: Double): String {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        return "Коорд: $lat, $lon"
    }

    private fun addDialogSpacing(container: LinearLayout) {
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10)
            )
        })
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
