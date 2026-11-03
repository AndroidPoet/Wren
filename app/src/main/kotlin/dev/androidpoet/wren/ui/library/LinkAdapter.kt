/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.androidpoet.wren.databinding.ItemLinkBinding

/** A saved or visited page, as shown in the history and bookmark lists. */
data class LinkRow(
  val url: String,
  val title: String,
  val subtitle: String,
  val folder: String = "",
)

class LinkAdapter(
  private val onClick: (LinkRow) -> Unit,
  private val onRemove: (LinkRow) -> Unit,
  private val onLongClick: ((LinkRow) -> Unit)? = null,
) : ListAdapter<LinkRow, LinkAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    ViewHolder(ItemLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemLinkBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: LinkRow) {
      binding.linkTitle.text = item.title.ifBlank { item.url }
      binding.linkSubtitle.text = item.subtitle
      binding.root.setOnClickListener { onClick(item) }
      onLongClick?.let { handler ->
        binding.root.setOnLongClickListener {
          handler(item)
          true
        }
      }
      binding.linkRemove.setOnClickListener { onRemove(item) }
    }
  }

  private companion object {
    val DIFF = object : DiffUtil.ItemCallback<LinkRow>() {
      override fun areItemsTheSame(oldItem: LinkRow, newItem: LinkRow): Boolean =
        oldItem.url == newItem.url

      override fun areContentsTheSame(oldItem: LinkRow, newItem: LinkRow): Boolean =
        oldItem == newItem
    }
  }
}
