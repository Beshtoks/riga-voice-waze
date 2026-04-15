package com.riga.voicewaze.jurmala

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class JurmalaAlertActivity : Activity() {

    private lateinit var store: JurmalaPointStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = JurmalaPointStore(this)

        val pointName = intent.getStringExtra("point_name") ?: "Юрмала"

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val tv = TextView(this)
        tv.text = "Въезд в зону: $pointName"
        tv.textSize = 20f

        val btnThanks = Button(this)
        btnThanks.text = "Спасибо"

        val btnPaid = Button(this)
        btnPaid.text = "Оплатил"

        layout.addView(tv)
        layout.addView(btnThanks)
        layout.addView(btnPaid)

        setContentView(layout)

        btnThanks.setOnClickListener {
            store.markThanksPressedToday()
            JurmalaScheduler.scheduleAfterThanks(this)
            finish()
        }

        btnPaid.setOnClickListener {
            store.setPaidToday()
            JurmalaScheduler.cancelAll(this)
            finish()
        }

        setFinishOnTouchOutside(false)
    }

    override fun onBackPressed() {
        // блокируем назад
    }
}