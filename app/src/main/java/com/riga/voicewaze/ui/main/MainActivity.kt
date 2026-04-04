package com.riga.voicewaze.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
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
import com.riga.voicewaze.domain.parser.AddressParser
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class VoiceMode {
        ADDRESS_LV,
        OBJECT_RU
    }

    private lateinit var btnMicRu: Button
    private lateinit var btnMicLv: Button
    private lateinit var etInput: EditText
    private lateinit var tvResult: TextView
    private lateinit var btnReset: Button
    private lateinit var btnSearch: Button
    private lateinit var btnWaze: Button
    private lateinit var rvSuggestions: RecyclerView

    private lateinit var parser: AddressParser
    private lateinit var streetMatcher: StreetMatcher
    private lateinit var landmarkMatcher: LandmarkMatcher
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var wavRecorder: WavRecorder
    private lateinit var cloudTranscriber: GoogleCloudLatvianTranscriber

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentMode: VoiceMode = VoiceMode.ADDRESS_LV
    private var autoOpenAfterVoice: Boolean = false
    private var lastAddress: String = ""
    private var lastConfidencePercent: Int = 0
    private var suppressTextWatcher: Boolean = false
    private var isLatvianCloudRecording: Boolean = false

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
        tvResult = findViewById(R.id.tvResult)
        btnReset = findViewById(R.id.btnReset)
        btnSearch = findViewById(R.id.btnSearch)
        btnWaze = findViewById(R.id.btnWaze)
        rvSuggestions = findViewById(R.id.rvSuggestions)

        val streetRepository = StreetRepository(this)
        parser = AddressParser(streetRepository)
        streetMatcher = StreetMatcher(streetRepository)
        landmarkMatcher = LandmarkMatcher()
        wavRecorder = WavRecorder(cacheDir)
        cloudTranscriber = GoogleCloudLatvianTranscriber()

        suggestionAdapter = SuggestionAdapter { suggestion ->
            onSuggestionSelected(suggestion)
        }

        rvSuggestions.layoutManager = LinearLayoutManager(this)
        rvSuggestions.adapter = suggestionAdapter

        setVoiceMode(VoiceMode.ADDRESS_LV)

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

            openWaze(lastAddress)
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

                if (input.length < 5) {
                    clearSuggestions()
                    if (input.isBlank()) {
                        tvResult.text = ""
                        lastAddress = ""
                        lastConfidencePercent = 0
                    }
                    return
                }

                updateLiveSuggestions(input)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        safeStopLatvianRecording()
        executor.shutdownNow()
    }

    private fun setVoiceMode(mode: VoiceMode) {
        currentMode = mode
        btnMicRu.text = "Объекты"
        btnMicLv.text = if (isLatvianCloudRecording) "Стоп улицы" else "Улицы"

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

    private fun handleSearch(text: String, autoOpen: Boolean) {
        val input = text.trim()

        if (input.isBlank()) {
            tvResult.text = if (currentMode == VoiceMode.ADDRESS_LV) {
                "Введите адрес"
            } else {
                "Введите объект"
            }
            lastAddress = ""
            lastConfidencePercent = 0
            autoOpenAfterVoice = false
            clearSuggestions()
            return
        }

        tvResult.text = "Поиск..."
        btnReset.isEnabled = false
        btnSearch.isEnabled = false
        btnMicRu.isEnabled = false
        btnMicLv.isEnabled = false
        btnWaze.isEnabled = false

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
        val houseNumber = extractHouseNumber(input)

        val matches = streetMatcher.findTopMatchesForTypedInput(
            input = input,
            limit = 10
        )

        runOnUiThread {
            finishBusyState()

            if (matches.isEmpty()) {
                clearSuggestions()
                lastAddress = ""
                lastConfidencePercent = 0
                tvResult.text = "Улица не найдена"
                autoOpenAfterVoice = false
                return@runOnUiThread
            }

            val best = matches.first()
            val street = normalizeStreetForDisplay(best.street)
            val city = if (best.city.isBlank()) "Rīga" else best.city

            val finalAddress = if (houseNumber.isNullOrBlank()) {
                "$street, $city, Latvija"
            } else {
                "$street $houseNumber, $city, Latvija"
            }

            lastAddress = finalAddress
            lastConfidencePercent = best.matchPercent

            tvResult.text = buildPercentText(
                mainText = finalAddress,
                percent = best.matchPercent
            )

            val others = matches.drop(1).map { suggestion ->
                val otherStreet = normalizeStreetForDisplay(suggestion.street)
                val otherCity = if (suggestion.city.isBlank()) "Rīga" else suggestion.city
                val otherAddress = if (houseNumber.isNullOrBlank()) {
                    "$otherStreet, $otherCity, Latvija"
                } else {
                    "$otherStreet $houseNumber, $otherCity, Latvija"
                }

                suggestion.copy(
                    street = otherAddress,
                    city = ""
                )
            }

            suggestionAdapter.submitList(others)

            if (autoOpen && lastAddress.isNotBlank() && lastConfidencePercent >= 85) {
                openWaze(lastAddress)
            }

            autoOpenAfterVoice = false
        }
    }

    private fun processObjectSearch(input: String, autoOpen: Boolean) {
        val match = landmarkMatcher.findBestMatch(input)

        runOnUiThread {
            finishBusyState()
            lastAddress = match.address
            lastConfidencePercent = match.matchPercent

            tvResult.text = if (match.address.isBlank()) {
                buildPercentText(
                    mainText = "Объект не найден",
                    percent = match.matchPercent
                )
            } else {
                buildPercentText(
                    mainText = match.address,
                    percent = match.matchPercent
                )
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
                val houseNumber = extractHouseNumber(input)

                val matches = streetMatcher.findTopMatchesForTypedInput(
                    input = input,
                    limit = 10
                )

                val preparedSuggestions = matches.map { suggestion ->
                    val street = normalizeStreetForDisplay(suggestion.street)
                    val address = if (houseNumber.isNullOrBlank()) {
                        "$street, ${suggestion.city}, Latvija"
                    } else {
                        "$street $houseNumber, ${suggestion.city}, Latvija"
                    }

                    suggestion.copy(
                        street = address,
                        city = ""
                    )
                }

                runOnUiThread {
                    if (preparedSuggestions.isEmpty()) {
                        clearSuggestions()
                        tvResult.text = "Улица не найдена"
                        lastAddress = ""
                        lastConfidencePercent = 0
                    } else {
                        val best = preparedSuggestions.first()

                        lastAddress = best.street
                        lastConfidencePercent = best.matchPercent

                        tvResult.text = buildPercentText(
                            mainText = best.street,
                            percent = best.matchPercent
                        )

                        suggestionAdapter.submitList(preparedSuggestions.drop(1))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    clearSuggestions()
                }
            }
        }
    }

    private fun onSuggestionSelected(suggestion: AddressSuggestion) {
        lastAddress = suggestion.street
        lastConfidencePercent = suggestion.matchPercent
        tvResult.text = buildPercentText(
            mainText = suggestion.street,
            percent = suggestion.matchPercent
        )
    }

    private fun extractHouseNumber(input: String): String? {
        val regex = Regex("""\b\d+[a-zA-ZА-Яа-я]?(?:/\d+[a-zA-ZА-Яа-я]?)?\b""")
        return regex.find(input)?.value
    }

    private fun resetInputAndResults() {
        safeStopLatvianRecording()
        suppressTextWatcher = true
        etInput.setText("")
        suppressTextWatcher = false
        lastAddress = ""
        lastConfidencePercent = 0
        tvResult.text = ""
        clearSuggestions()
        setVoiceMode(currentMode)
    }

    private fun clearSuggestions() {
        suggestionAdapter.submitList(emptyList())
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
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи объект")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    5000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    5000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    3000L
                )
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
            btnMicLv.text = "Стоп улицы"
            tvResult.text = "Запись адреса... Нажми ещё раз для остановки"
            clearSuggestions()
        } catch (e: Exception) {
            isLatvianCloudRecording = false
            btnMicLv.text = "Улицы"
            tvResult.text = "Ошибка записи: ${e.message ?: "unknown"}"
        }
    }

    private fun stopLatvianCloudRecordingAndTranscribe() {
        val audioFile = try {
            wavRecorder.stop()
        } catch (e: Exception) {
            isLatvianCloudRecording = false
            btnMicLv.text = "Улицы"
            tvResult.text = "Ошибка остановки записи: ${e.message ?: "unknown"}"
            return
        }

        isLatvianCloudRecording = false
        btnMicLv.text = "Улицы"
        tvResult.text = "Отправка в облако..."
        btnMicRu.isEnabled = false
        btnMicLv.isEnabled = false
        btnReset.isEnabled = false
        btnSearch.isEnabled = false
        btnWaze.isEnabled = false

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
        if (!isLatvianCloudRecording) return

        try {
            wavRecorder.stop().delete()
        } catch (_: Exception) {
        }

        isLatvianCloudRecording = false

        if (::btnMicLv.isInitialized) {
            btnMicLv.text = "Улицы"
        }
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

    private fun openWaze(address: String) {
        try {
            val uri = Uri.parse("https://waze.com/ul?q=${Uri.encode(address)}")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Не удалось открыть Waze")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}