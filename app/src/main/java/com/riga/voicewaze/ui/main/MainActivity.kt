package com.riga.voicewaze.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.riga.voicewaze.R
import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.domain.matcher.StreetMatchResult
import com.riga.voicewaze.domain.matcher.StreetMatcher
import com.riga.voicewaze.domain.parser.AddressParser

class MainActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private lateinit var btnMic: Button
    private lateinit var btnSearch: Button
    private lateinit var tvResult: TextView
    private lateinit var btnWaze: Button

    private lateinit var addressParser: AddressParser
    private lateinit var streetMatcher: StreetMatcher

    private var lastResult: String = ""
    private var lastMatchWasConfident: Boolean = false
    private var autoLaunchAfterVoice: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(
                    this,
                    "Разрешение на микрофон не выдано",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: run {
                autoLaunchAfterVoice = false
                return@registerForActivityResult
            }

            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim().orEmpty()

            if (spokenText.isNotBlank()) {
                etInput.setText(spokenText)
                performSearch()

                if (autoLaunchAfterVoice && lastResult.isNotBlank() && lastMatchWasConfident) {
                    mainHandler.postDelayed({
                        openWaze(lastResult)
                    }, 500)
                } else if (autoLaunchAfterVoice && !lastMatchWasConfident) {
                    Toast.makeText(
                        this,
                        "Совпадение неуверенное. Проверь адрес перед запуском Waze.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Речь не распознана",
                    Toast.LENGTH_SHORT
                ).show()
            }

            autoLaunchAfterVoice = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressParser = AddressParser()
        streetMatcher = StreetMatcher(StreetRepository(this))

        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnSearch = findViewById(R.id.btnSearch)
        tvResult = findViewById(R.id.tvResult)
        btnWaze = findViewById(R.id.btnWaze)

        btnMic.setOnClickListener {
            autoLaunchAfterVoice = true
            checkAudioPermissionAndStart()
        }

        btnSearch.setOnClickListener {
            performSearch()
        }

        btnWaze.setOnClickListener {
            if (lastResult.isNotBlank()) {
                openWaze(lastResult)
            } else {
                Toast.makeText(
                    this,
                    "Сначала выполни поиск адреса",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performSearch() {
        val input = etInput.text.toString().trim()

        if (input.isBlank()) {
            tvResult.text = "Введите или скажите адрес"
            lastResult = ""
            lastMatchWasConfident = false
            return
        }

        val resolved = processAddress(input)
        tvResult.text = resolved.displayText
        lastResult = resolved.addressForWaze
        lastMatchWasConfident = resolved.isConfident
    }

    private fun processAddress(input: String): ResolvedAddress {
        val parsed = addressParser.parse(input)
        val matchResult = streetMatcher.findBestMatchDetailed(parsed.streetRaw)

        val address = buildAddressString(
            street = matchResult.street,
            houseNumber = parsed.houseNumber,
            corpus = parsed.корпус
        )

        val display = if (matchResult.isConfident) {
            address
        } else {
            "$address\n\nПроверь улицу: совпадение неуверенное."
        }

        return ResolvedAddress(
            addressForWaze = address,
            displayText = display,
            isConfident = matchResult.isConfident,
            matchResult = matchResult
        )
    }

    private fun buildAddressString(
        street: String,
        houseNumber: String?,
        corpus: String?
    ): String {
        if (houseNumber.isNullOrBlank()) {
            return "$street iela, Rīga"
        }

        return if (corpus.isNullOrBlank()) {
            "$street iela $houseNumber, Rīga"
        } else {
            "$street iela $houseNumber-$corpus, Rīga"
        }
    }

    private fun checkAudioPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startVoiceRecognition()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи улицу и номер дома")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            autoLaunchAfterVoice = false
            Toast.makeText(
                this,
                "На устройстве недоступен голосовой ввод",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openWaze(address: String) {
        val encodedAddress = Uri.encode(address)
        val uri = Uri.parse("waze://?q=$encodedAddress&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Waze не установлен",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private data class ResolvedAddress(
        val addressForWaze: String,
        val displayText: String,
        val isConfident: Boolean,
        val matchResult: StreetMatchResult
    )
}