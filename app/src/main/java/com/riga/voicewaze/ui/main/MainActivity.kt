package com.riga.voicewaze.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.riga.voicewaze.R
import com.riga.voicewaze.data.local.StreetRepository
import com.riga.voicewaze.domain.matcher.StreetMatcher
import com.riga.voicewaze.domain.parser.AddressParser

class MainActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var tvResult: TextView
    private lateinit var btnWaze: Button

    private lateinit var addressParser: AddressParser
    private lateinit var streetMatcher: StreetMatcher

    private var lastResult: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressParser = AddressParser()
        streetMatcher = StreetMatcher(StreetRepository(this))

        etInput = findViewById(R.id.etInput)
        btnSearch = findViewById(R.id.btnSearch)
        tvResult = findViewById(R.id.tvResult)
        btnWaze = findViewById(R.id.btnWaze)

        btnSearch.setOnClickListener {
            val input = etInput.text.toString()
            val result = processAddress(input)
            tvResult.text = result
            lastResult = result
        }

        btnWaze.setOnClickListener {
            if (lastResult.isNotBlank()) {
                openWaze(lastResult)
            }
        }
    }

    private fun processAddress(input: String): String {
        val parsed = addressParser.parse(input)
        val matchedStreet = streetMatcher.findBestMatch(parsed.streetRaw)

        return if (parsed.houseNumber != null) {
            "$matchedStreet iela ${parsed.houseNumber}, Rīga"
        } else {
            "$matchedStreet iela, Rīga"
        }
    }

    private fun openWaze(address: String) {
        val uri = Uri.parse("waze://?q=$address&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }
}