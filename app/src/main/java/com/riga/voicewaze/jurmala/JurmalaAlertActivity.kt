package com.riga.voicewaze.jurmala

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class JurmalaAlertActivity : Activity() {

    private lateinit var store: JurmalaPointStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = JurmalaPointStore(this)

        val pointName = intent.getStringExtra("point_name") ?: "Юрмала"

        window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#66000000"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#20252B"))
                setStroke(dp(2), Color.parseColor("#4A5563"))
            }
        }

        val title = TextView(this).apply {
            text = "ПЛАТНАЯ ЗОНА ЮРМАЛЫ"
            setTextColor(Color.parseColor("#F2F4F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val divider = TextView(this).apply {
            text = "────────"
            setTextColor(Color.parseColor("#6E7B8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }

        val line1 = TextView(this).apply {
            text = "Зафиксирован въезд через точку:"
            setTextColor(Color.parseColor("#C5CDD6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val point = TextView(this).apply {
            text = pointName
            setTextColor(Color.parseColor("#FFD166"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }

        val line2 = TextView(this).apply {
            text = "Подтверди получение сообщения"
            setTextColor(Color.parseColor("#D7DEE7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnPaid = Button(this).apply {
            text = "Оплатил"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1565C0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnThanks = Button(this).apply {
            text = "Спасибо"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#2E7D32"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        }

        buttonRow.addView(btnPaid)
        buttonRow.addView(btnThanks)

        card.addView(title)
        card.addView(divider)
        card.addView(line1)
        card.addView(point)
        card.addView(line2)
        card.addView(buttonRow)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                dp(340),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

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
        // намеренно блокируем закрытие назад
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}