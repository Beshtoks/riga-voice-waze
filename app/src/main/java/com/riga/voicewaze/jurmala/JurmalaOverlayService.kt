package com.riga.voicewaze.jurmala

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class JurmalaOverlayService : Service() {

    private lateinit var store: JurmalaPointStore
    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var ringtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        store = JurmalaPointStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pointName = intent?.getStringExtra(JurmalaForegroundService.EXTRA_POINT_NAME)
            ?: intent?.getStringExtra("point_name")
            ?: "Юрмала"

        playAlertSignal()
        showOverlay(pointName)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAlertSignal()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(pointName: String) {
        removeOverlay()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            isClickable = true
            isFocusable = true
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

        btnThanks.setOnClickListener {
            stopAlertSignal()
            store.markThanksPressedToday()
            JurmalaScheduler.scheduleAfterThanks(this)
            stopSelf()
        }

        btnPaid.setOnClickListener {
            stopAlertSignal()
            store.setPaidToday()
            JurmalaScheduler.cancelAll(this)
            stopSelf()
        }

        buttonRow.addView(btnPaid)
        buttonRow.addView(btnThanks)

        card.addView(title)
        card.addView(divider)
        card.addView(line1)
        card.addView(point)
        card.addView(line2)
        card.addView(buttonRow)

        val cardParams = FrameLayout.LayoutParams(
            dp(340),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(18)
            marginEnd = dp(18)
        }
        root.addView(card, cardParams)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = root
        windowManager.addView(root, params)
    }

    private fun playAlertSignal() {
        vibrate()
        stopAlertSignal()

        val transmitterUri = findSystemSoundUriByName("Transmitter")
        val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val finalUri = transmitterUri ?: fallbackUri ?: return

        try {
            ringtone = RingtoneManager.getRingtone(applicationContext, finalUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopAlertSignal() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(450)
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(450)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun findSystemSoundUriByName(targetName: String): Uri? {
        val external = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val internal = MediaStore.Audio.Media.INTERNAL_CONTENT_URI

        findUriInCollection(external, targetName)?.let { return it }
        findUriInCollection(internal, targetName)?.let { return it }

        return null
    }

    private fun findUriInCollection(collection: Uri, targetName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        return try {
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val title = cursor.getString(titleIndex).orEmpty()
                    val displayName = cursor.getString(displayNameIndex).orEmpty()

                    val matches = title.equals(targetName, ignoreCase = true) ||
                            displayName.equals(targetName, ignoreCase = true) ||
                            displayName.substringBeforeLast('.').equals(targetName, ignoreCase = true)

                    if (matches) {
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}