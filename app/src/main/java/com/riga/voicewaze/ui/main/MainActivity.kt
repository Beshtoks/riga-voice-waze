package com.riga.voicewaze.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.riga.voicewaze.R
import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.domain.matcher.StreetMatcher
import com.riga.voicewaze.domain.parser.AddressParser
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private lateinit var tvResult: TextView
    private lateinit var btnSearch: Button
    private lateinit var btnMic: Button
    private lateinit var btnWaze: Button

    private lateinit var parser: AddressParser
    private lateinit var matcher: StreetMatcher

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var lastAddress: String = ""
    private var lastConfident: Boolean = false
    private var autoOpenAfterVoice: Boolean = false

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (list.isNullOrEmpty()) {
                    autoOpenAfterVoice = false
                    toast("Runa nav atpazīta")
                    return@registerForActivityResult
                }

                val spokenText = list[0].trim()
                if (spokenText.isBlank()) {
                    autoOpenAfterVoice = false
                    toast("Runa nav atpazīta")
                    return@registerForActivityResult
                }

                etInput.setText(spokenText)
                etInput.setSelection(spokenText.length)
                handleSearch(spokenText, autoOpenAfterVoice)
            } catch (e: Exception) {
                autoOpenAfterVoice = false
                tvResult.text = "Kļūda: ${e.message ?: "speech result"}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etInput = findViewById(R.id.etInput)
        tvResult = findViewById(R.id.tvResult)
        btnSearch = findViewById(R.id.btnSearch)
        btnMic = findViewById(R.id.btnMic)
        btnWaze = findViewById(R.id.btnWaze)

        parser = AddressParser()
        matcher = StreetMatcher(StreetRepository(this))

        btnSearch.setOnClickListener {
            autoOpenAfterVoice = false
            handleSearch(etInput.text.toString(), false)
        }

        btnMic.setOnClickListener {
            autoOpenAfterVoice = true
            startVoice()
        }

        btnWaze.setOnClickListener {
            if (lastAddress.isBlank()) {
                toast("Vispirms atrodi adresi")
                return@setOnClickListener
            }

            if (lastConfident) {
                openWaze(lastAddress)
            } else {
                confirmAndOpen(lastAddress)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun handleSearch(text: String, autoOpen: Boolean) {
        val input = text.trim()
        if (input.isBlank()) {
            tvResult.text = "Ievadi adresi"
            lastAddress = ""
            lastConfident = false
            autoOpenAfterVoice = false
            return
        }

        tvResult.text = "Meklē..."
        btnSearch.isEnabled = false
        btnMic.isEnabled = false
        btnWaze.isEnabled = false

        executor.execute {
            try {
                val parsed = parser.parse(input)

                val match = matcher.findBestMatchDetailed(
                    input = parsed.streetRaw,
                    preferredCity = parsed.cityRaw
                )

                val city = if (match.city.isBlank()) "Rīga" else match.city

                val streetFinal = if (match.street.lowercase(Locale.ROOT).contains("iela")) {
                    match.street
                } else {
                    "${match.street} iela"
                }

                val address = if (parsed.houseNumber.isNullOrBlank()) {
                    "$streetFinal, $city, Latvija"
                } else {
                    "$streetFinal ${parsed.houseNumber}, $city, Latvija"
                }

                val display = if (match.isConfident) {
                    address
                } else {
                    "$address\n\nPārbaudi adresi"
                }

                runOnUiThread {
                    btnSearch.isEnabled = true
                    btnMic.isEnabled = true
                    btnWaze.isEnabled = true

                    tvResult.text = display
                    lastAddress = address
                    lastConfident = match.isConfident

                    if (autoOpen && lastAddress.isNotBlank()) {
                        if (lastConfident) {
                            openWaze(lastAddress)
                        } else {
                            confirmAndOpen(lastAddress)
                        }
                    }

                    autoOpenAfterVoice = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnSearch.isEnabled = true
                    btnMic.isEnabled = true
                    btnWaze.isEnabled = true
                    autoOpenAfterVoice = false
                    tvResult.text = "Kļūda apstrādē: ${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun startVoice() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "lv-LV")
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Pasaki adresi")
            }

            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            autoOpenAfterVoice = false
            toast("Balss ievade nav pieejama")
        } catch (e: Exception) {
            autoOpenAfterVoice = false
            tvResult.text = "Kļūda: ${e.message ?: "voice start"}"
        }
    }

    private fun confirmAndOpen(address: String) {
        AlertDialog.Builder(this)
            .setTitle("Apstiprināt?")
            .setMessage(address)
            .setNegativeButton("Nē", null)
            .setPositiveButton("Jā") { _, _ ->
                openWaze(address)
            }
            .show()
    }

    private fun openWaze(address: String) {
        try {
            val uri = Uri.parse("https://waze.com/ul?q=${Uri.encode(address)}")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Neizdevās atvērt Waze")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}