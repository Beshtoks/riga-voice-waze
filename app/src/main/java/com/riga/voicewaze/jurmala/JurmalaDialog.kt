package com.riga.voicewaze.jurmala

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

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

        val paidButton = Button(context).apply {
            text = if (store.isPaidToday(currentDayKey())) {
                "✔ Оплачено сегодня"
            } else {
                "Оплачено сегодня"
            }
        }
        layout.addView(paidButton)

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
        layout.addView(listView)

        val addButton = Button(context).apply {
            text = "Добавить точку"
        }
        layout.addView(addButton)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Зона Юрмалы")
            .setView(layout)
            .setNegativeButton("Закрыть", null)
            .create()

        toggle.setOnCheckedChangeListener { _, isChecked ->
            store.setEnabled(isChecked)
        }

        paidButton.setOnClickListener {
            store.setPaidToday(currentDayKey())
            paidButton.text = "✔ Оплачено сегодня"
            Toast.makeText(context, "Напоминания отключены до 23:59", Toast.LENGTH_SHORT).show()
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
        val latRaw = form.lat.text.toString().trim().replace(',', '.')
        val lngRaw = form.lng.text.toString().trim().replace(',', '.')
        val radiusRaw = form.radius.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(context, "Введите название точки", Toast.LENGTH_SHORT).show()
            return null
        }

        val lat = latRaw.toDoubleOrNull()
        if (lat == null) {
            Toast.makeText(context, "Неверная широта", Toast.LENGTH_SHORT).show()
            return null
        }

        val lng = lngRaw.toDoubleOrNull()
        if (lng == null) {
            Toast.makeText(context, "Неверная долгота", Toast.LENGTH_SHORT).show()
            return null
        }

        val radius = radiusRaw.toIntOrNull()
        if (radius == null || radius <= 0) {
            Toast.makeText(context, "Неверный радиус", Toast.LENGTH_SHORT).show()
            return null
        }

        return JurmalaPoint(
            name = name,
            lat = lat,
            lng = lng,
            radius = radius
        )
    }

    private fun currentDayKey(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}
