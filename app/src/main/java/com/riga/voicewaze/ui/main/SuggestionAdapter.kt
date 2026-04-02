package com.riga.voicewaze.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.riga.voicewaze.databinding.ItemSearchSuggestionBinding
import com.riga.voicewaze.domain.matcher.AddressSuggestion

class SuggestionAdapter(
    private val onSuggestionClick: (AddressSuggestion) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.SuggestionViewHolder>() {

    private val items = mutableListOf<AddressSuggestion>()

    fun submitList(newItems: List<AddressSuggestion>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchSuggestionBinding.inflate(inflater, parent, false)
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(items[position], position + 1)
    }

    override fun getItemCount(): Int = items.size

    inner class SuggestionViewHolder(
        private val binding: ItemSearchSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AddressSuggestion, number: Int) {
            binding.tvOrder.text = "$number."
            binding.tvAddress.text = item.street

            binding.root.setOnClickListener {
                onSuggestionClick(item)
            }
        }
    }
}