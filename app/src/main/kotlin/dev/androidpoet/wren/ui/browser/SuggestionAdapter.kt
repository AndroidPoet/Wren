/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.core.model.Suggestion
import dev.androidpoet.wren.databinding.ItemSuggestionBinding

class SuggestionAdapter(
  private val onClick: (Suggestion) -> Unit,
  private val onFill: (Suggestion) -> Unit,
) : ListAdapter<Suggestion, SuggestionAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
    ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
  )

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemSuggestionBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: Suggestion) {
      binding.suggestionText.text = item.phrase
      binding.suggestionUrl.text = item.url?.let { UrlFormatter.prettyHost(it) }.orEmpty()
      binding.suggestionUrl.isGone = item.url == null
      binding.suggestionIcon.setImageResource(
        if (item.fromHistory) R.drawable.ic_history else R.drawable.ic_search,
      )
      binding.root.setOnClickListener { onClick(item) }
      binding.suggestionFill.setOnClickListener { onFill(item) }
    }
  }

  private companion object {
    val DIFF = object : DiffUtil.ItemCallback<Suggestion>() {
      override fun areItemsTheSame(oldItem: Suggestion, newItem: Suggestion): Boolean =
        oldItem.phrase == newItem.phrase && oldItem.url == newItem.url

      override fun areContentsTheSame(oldItem: Suggestion, newItem: Suggestion): Boolean =
        oldItem == newItem
    }
  }
}
