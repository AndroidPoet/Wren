/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.home

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.databinding.ItemMostVisitedBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MostVisitedAdapter(
  private val scope: CoroutineScope,
  private val loadIcon: suspend (String) -> Bitmap?,
  private val onOpen: (HistoryEntry) -> Unit,
) : ListAdapter<HistoryEntry, MostVisitedAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    ViewHolder(ItemMostVisitedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemMostVisitedBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(entry: HistoryEntry): Unit = with(binding) {
      visitedLetter.text = Tiles.letterFor(entry.title, entry.url)
      visitedLetter.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = root.resources.getDimension(R.dimen.tile_corner_small)
        setColor(Tiles.colorFor(entry.url))
      }
      visitedHost.text = HostMatcher.hostOf(entry.url)?.removePrefix("www.").orEmpty()
      visitedTitle.text = entry.title
      root.setOnClickListener { onOpen(entry) }

      visitedLetter.isVisible = true
      visitedIcon.isVisible = false
      visitedIcon.tag = entry.url
      scope.launch {
        val icon = loadIcon(entry.url) ?: return@launch
        if (visitedIcon.tag != entry.url) return@launch
        visitedIcon.setImageBitmap(icon)
        visitedIcon.isVisible = true
        visitedLetter.isVisible = false
      }
    }
  }

  private companion object {
    val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
      override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
        oldItem.url == newItem.url

      override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
        oldItem == newItem
    }
  }
}
