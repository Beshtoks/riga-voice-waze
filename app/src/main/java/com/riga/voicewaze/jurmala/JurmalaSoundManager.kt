package com.riga.voicewaze.jurmala

import android.media.AudioManager
import android.media.ToneGenerator
import java.util.Calendar

class JurmalaSoundManager {

    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    fun play() {
        if (isLate()) {
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
        } else {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        }
    }

    fun release() {
        tone.release()
    }

    private fun isLate(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return hour > 23 || (hour == 23 && minute >= 30)
    }
}
