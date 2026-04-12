package com.riga.voicewaze.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.riga.voicewaze.R
import com.riga.voicewaze.domain.landmark.LandmarkEntry

class LandmarkEditorAdapter(
    private val onEditClicked: (LandmarkEntry) -> Unit,
    private val onDeleteClicked: (LandmarkEntry) -> Unit
) : RecyclerView.Adapter<LandmarkEditorAdapter.LandmarkViewHolder>() {

    private val items = mutableListOf<LandmarkEntry>()

    fun submitList(entries: List<LandmarkEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LandmarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_landmark_editor, parent, false)
        return LandmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: LandmarkViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class LandmarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvLandmarkName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvLandmarkAddress)
        private val btnEdit: Button = itemView.findViewById(R.id.btnEditLandmark)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteLandmark)

        fun bind(entry: LandmarkEntry) {
            tvName.text = buildString {
                append(entry.displayName)
                if (entry.spokenPhrase.isNotBlank()) {
                    append(" — ")
                    append(entry.spokenPhrase)
                }
            }

            tvAddress.text = buildString {
                append(entry.address)
                if (!entry.latitude.isNaN() && !entry.longitude.isNaN()) {
                    append("\n")
                    append("Lat: ")
                    append(String.format(java.util.Locale.US, "%.6f", entry.latitude))
                    append(", Lon: ")
                    append(String.format(java.util.Locale.US, "%.6f", entry.longitude))
                }
            }

            btnEdit.setOnClickListener { onEditClicked(entry) }
            btnDelete.setOnClickListener { onDeleteClicked(entry) }
        }
    }
}
