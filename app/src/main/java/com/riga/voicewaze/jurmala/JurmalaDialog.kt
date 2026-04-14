package com.riga.voicewaze.jurmala

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.Toast
import androidx.core.content.ContextCompat

class JurmalaDialog(
    private val context: Context,
    private val store: JurmalaPointStore
) {

    private var points = store.loadPoints()

    fun show() {
        points = store.loadPoints()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val toggle = Switch(context).apply {
            text = "Контроль Юрмалы"
            isChecked = store.isEnabled()
        }
        layout.addView(toggle)

        val paidButton = Button(context)

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                700
            )
        }

        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            points.map { formatPointLine(it) }.toMutableList()
        )
        listView.adapter = adapter

        val addButton = Button(context).apply {
            text = "Добавить точку"
        }

        val paidParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 20
            bottomMargin = 20
        }
        paidButton.layoutParams = paidParams

        paidButton.minHeight = (addButton.minHeight - 20).coerceAtLeast(0)
        paidButton.minimumHeight = (addButton.minimumHeight - 20).coerceAtLeast(0)
        paidButton.setPadding(
            addButton.paddingLeft,
            (addButton.paddingTop - 10).coerceAtLeast(0),
            addButton.paddingRight,
            (addButton.paddingBottom - 10).coerceAtLeast(0)
        )

        layout.addView(paidButton)
        layout.addView(listView)
        layout.addView(addButton)

        fun rounded(color: Int): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = 28f
                setColor(color)
            }
        }

        fun updatePaidButton() {
            val entered = store.isEnteredToday()
            val paid = store.isPaidToday()

            when {
                !entered -> {
                    paidButton.text = "ВНЕ ЗОНЫ"
                    paidButton.background = rounded(Color.parseColor("#696969")) // DimGrey
                    paidButton.setTextColor(Color.WHITE)
                }

                !paid -> {
                    paidButton.text = "НЕ ОПЛАЧЕНО"
                    paidButton.background = rounded(Color.parseColor("#D32F2F"))
                    paidButton.setTextColor(Color.WHITE)
                }

                else -> {
                    paidButton.text = "ОПЛАЧЕНО ДО КОНЦА ДНЯ"
                    paidButton.background = rounded(Color.parseColor("#388E3C"))
                    paidButton.setTextColor(Color.WHITE)
                }
            }
        }

        updatePaidButton()

        val dialog = AlertDialog.Builder(context)
            .setTitle("Зона Юрмалы")
            .setView(layout)
            .setNegativeButton("Закрыть", null)
            .create()

        toggle.setOnCheckedChangeListener { _, isChecked ->
            store.setEnabled(isChecked)

            val serviceIntent = Intent(context, JurmalaForegroundService::class.java)
            if (isChecked) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.stopService(serviceIntent)
            }
        }

        paidButton.setOnClickListener {
            val entered = store.isEnteredToday()
            if (!entered) {
                Toast.makeText(context, "Сейчас статус: ВНЕ ЗОНЫ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (store.isPaidToday()) {
                store.clearPaidToday()
                Toast.makeText(context, "Статус: НЕ ОПЛАЧЕНО", Toast.LENGTH_SHORT).show()
            } else {
                store.setPaidToday()
                Toast.makeText(context, "Статус: ОПЛАЧЕНО ДО КОНЦА ДНЯ", Toast.LENGTH_SHORT).show()
            }

            updatePaidButton()
        }

        paidButton.setOnLongClickListener {
            store.resetToOutOfZone()
            updatePaidButton()
            Toast.makeText(context, "Сброс в состояние: ВНЕ ЗОНЫ", Toast.LENGTH_SHORT).show()
            true
        }

        addButton.setOnClickListener {
            showAddDialog(adapter)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            showEditDialog(position, adapter)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            points.removeAt(position)
            store.savePoints(points)
            reloadAdapter(adapter)
            Toast.makeText(context, "Точка удалена", Toast.LENGTH_SHORT).show()
            true
        }

        dialog.show()
    }

    private fun showAddDialog(adapter: ArrayAdapter<String>) {
        val form = createPointForm()

        AlertDialog.Builder(context)
            .setTitle("Добавить точку")
            .setView(form.container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val point = buildPointFromForm(form) ?: return@setOnClickListener
                        points.add(point)
                        store.savePoints(points)
                        reloadAdapter(adapter)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showEditDialog(index: Int, adapter: ArrayAdapter<String>) {
        val point = points[index]
        val form = createPointForm(point)

        AlertDialog.Builder(context)
            .setTitle("Редактировать точку")
            .setView(form.container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updated = buildPointFromForm(form) ?: return@setOnClickListener
                        point.name = updated.name
                        point.lat = updated.lat
                        point.lng = updated.lng
                        point.radius = updated.radius
                        store.savePoints(points)
                        reloadAdapter(adapter)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun reloadAdapter(adapter: ArrayAdapter<String>) {
        adapter.clear()
        adapter.addAll(points.map { formatPointLine(it) })
        adapter.notifyDataSetChanged()
    }

    private fun formatPointLine(point: JurmalaPoint): String {
        return "${point.name} | ${point.lat}, ${point.lng} | ${point.radius} м"
    }

    private data class PointForm(
        val container: LinearLayout,
        val name: EditText,
        val lat: EditText,
        val lng: EditText,
        val radius: EditText
    )

    private fun createPointForm(point: JurmalaPoint? = null): PointForm {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val name = EditText(context).apply {
            hint = "Название"
            setText(point?.name.orEmpty())
        }
        container.addView(name)

        val lat = EditText(context).apply {
            hint = "Широта"
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(point?.lat?.toString().orEmpty())
        }
        container.addView(lat)

        val lng = EditText(context).apply {
            hint = "Долгота"
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(point?.lng?.toString().orEmpty())
        }
        container.addView(lng)

        val radius = EditText(context).apply {
            hint = "Радиус (м)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(point?.radius?.toString().orEmpty())
        }
        container.addView(radius)

        return PointForm(container, name, lat, lng, radius)
    }

    private fun buildPointFromForm(form: PointForm): JurmalaPoint? {
        val name = form.name.text.toString().trim()
        val lat = form.lat.text.toString().replace(',', '.').toDoubleOrNull()
        val lng = form.lng.text.toString().replace(',', '.').toDoubleOrNull()
        val radius = form.radius.text.toString().toIntOrNull()

        if (name.isBlank() || lat == null || lng == null || radius == null) {
            Toast.makeText(context, "Проверь введённые данные", Toast.LENGTH_SHORT).show()
            return null
        }

        return JurmalaPoint(name, lat, lng, radius)
    }
}