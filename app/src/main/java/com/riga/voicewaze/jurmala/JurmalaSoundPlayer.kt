package com.riga.voicewaze.jurmala

import android.content.Context
import android.media.MediaPlayer
import com.riga.voicewaze.R

class JurmalaSoundPlayer(private val context: Context) {

    private var activePlayer: MediaPlayer? = null

    fun playNormal() {
        play(R.raw.jurmala_alert_normal)
    }

    fun playUrgent() {
        play(R.raw.jurmala_alert_urgent)
    }

    private fun play(resId: Int) {
        release()
        activePlayer = MediaPlayer.create(context, resId)?.apply {
            isLooping = false
            setOnCompletionListener {
                release()
                activePlayer = null
            }
            start()
        }
    }

    fun release() {
        activePlayer?.runCatching {
            stop()
        }
        activePlayer?.release()
        activePlayer = null
    }
}
