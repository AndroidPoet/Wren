/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tabs

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.core.model.TabSnapshot
import dev.androidpoet.wren.databinding.ItemTabCardBinding

/** Chrome-style grid cards: a live page snapshot under a slim title bar. */
class TabAdapter(
  private val thumbnailFor: (Long) -> Bitmap?,
  private val onSelect: (Long) -> Unit,
  private val onClose: (Long) -> Unit,
) : ListAdapter<TabSnapshot, TabAdapter.ViewHolder>(DIFF) {

  var activeTabId: Long = -1L

  init {
    // Stable ids keep swipe-to-close animations attached to the right card.
    setHasStableIds(true)
  }

  override fun getItemId(position: Int): Long = getItem(position).id

  fun tabIdAt(position: Int): Long? = currentList.getOrNull(position)?.id

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    ViewHolder(ItemTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemTabCardBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: TabSnapshot) {
      val host = UrlFormatter.prettyHost(item.url)
      // "about:blank" is what a WebView answers for a page it has not parsed yet, never a title.
      val title = item.title.takeUnless { it.isBlank() || it == UrlFormatter.NEW_TAB_URL }
      binding.tabTitle.text = title
        ?: host.ifBlank { binding.root.context.getString(R.string.new_tab) }
      binding.tabPrivateBadge.isVisible = item.isPrivate
      binding.tabCard.strokeWidth = if (item.id == activeTabId) ACTIVE_STROKE_PX else 0
      val thumbnail = thumbnailFor(item.id)
      binding.tabThumbnail.setImageBitmap(thumbnail)
      if (thumbnail != null) binding.tabThumbnail.topCrop(thumbnail)
      binding.root.setOnClickListener { onSelect(item.id) }
      binding.tabClose.setOnClickListener { onClose(item.id) }
    }
  }

  private companion object {
    const val ACTIVE_STROKE_PX = 6

    /** Fill the card width and pin the top of the page, the way a browser tab card should. */
    fun ImageView.topCrop(bitmap: Bitmap) {
      doOnLayout {
        if (width == 0 || bitmap.width == 0) return@doOnLayout
        val scale = width.toFloat() / bitmap.width
        imageMatrix = Matrix().apply { setScale(scale, scale) }
      }
    }

    val DIFF = object : DiffUtil.ItemCallback<TabSnapshot>() {
      override fun areItemsTheSame(oldItem: TabSnapshot, newItem: TabSnapshot): Boolean =
        oldItem.id == newItem.id

      override fun areContentsTheSame(oldItem: TabSnapshot, newItem: TabSnapshot): Boolean =
        oldItem == newItem
    }
  }
}
