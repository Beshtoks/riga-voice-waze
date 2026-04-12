package com.riga.voicewaze.jurmala

import android.app.AlertDialog
import android.content.Context

class JurmalaAlertDialog(
    private val context: Context,
    private val store: JurmalaPointStore,
    private val soundPlayer: JurmalaSoundPlayer,
    private val onPay: () -> Unit,
    private val onThanks: () -> Unit
) {

    private var activeDialog: AlertDialog? = null

    fun showIfNeeded(forceUrgent: Boolean = false) {
        val today = JurmalaTime.todayKey()
        if (store.isPaidToday(today)) return
        if (activeDialog?.isShowing == true) return

        val urgent = forceUrgent || JurmalaTime.isUrgentWindow()
        val message = if (urgent) {
            "Пропуск Юрмалы сегодня ещё не подтверждён.\nДо конца дня осталось мало времени.\nНужно оплатить до 23:59."
        } else {
            "Ты въехал в платную зону Юрмалы.\nНужно оплатить пропуск до 23:59."
        }

        if (urgent) {
            soundPlayer.playUrgent()
        } else {
            soundPlayer.playNormal()
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Зона Юрмалы")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Оплатить") { _, _ ->
                store.setPaidToday(today)
                onPay()
                activeDialog = null
            }
            .setNegativeButton("Спасибо") { dialogInterface, _ ->
                onThanks()
                dialogInterface.dismiss()
                activeDialog = null
            }
            .create()

        dialog.setOnDismissListener {
            activeDialog = null
        }

        activeDialog = dialog
        dialog.show()
    }
}
