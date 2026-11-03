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
import dev.androidpoet.wren.core.model.Shortcut
import dev.androidpoet.wren.databinding.ItemShortcutBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The speed-dial grid. The final cell is always an add button, so the grid never looks finished
 * and the way to extend it is where you are already looking.
 */
class ShortcutAdapter(
  private val scope: CoroutineScope,
  private val loadIcon: suspend (String) -> Bitmap?,
  private val onOpen: (Shortcut) -> Unit,
  private val onRemove: (Shortcut) -> Unit,
  private val onAdd: () -> Unit,
) : ListAdapter<ShortcutAdapter.Cell, ShortcutAdapter.ViewHolder>(DIFF) {

  sealed interface Cell {
    data class Site(val shortcut: Shortcut) : Cell
    data object Add : Cell
  }

  fun submitShortcuts(shortcuts: List<Shortcut>) {
    submitList(shortcuts.map { Cell.Site(it) } + Cell.Add)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    ViewHolder(ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemShortcutBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(cell: Cell) = when (cell) {
      is Cell.Add -> bindAdd()
      is Cell.Site -> bindSite(cell.shortcut)
    }

    private fun bindAdd() = with(binding) {
      tileLetter.text = ""
      tileIcon.isVisible = false
      tileAdd.isVisible = true
      tileBackground.setBackgroundResource(R.drawable.bg_tile)
      tileLabel.setText(R.string.add_shortcut)
      root.setOnClickListener { onAdd() }
      root.setOnLongClickListener(null)
      root.isLongClickable = false
    }

    private fun bindSite(shortcut: Shortcut) = with(binding) {
      tileAdd.isVisible = false
      tileLetter.text = Tiles.letterFor(shortcut.title, shortcut.url)
      tileLetter.isVisible = true
      tileIcon.isVisible = false
      tileBackground.background = tile(Tiles.colorFor(shortcut.url))
      tileLabel.text = Tiles.labelFor(shortcut.title, shortcut.url)
      loadIconInto(shortcut.url)
      root.setOnClickListener { onOpen(shortcut) }
      root.setOnLongClickListener {
        onRemove(shortcut)
        true
      }
    }

    /**
     * The letter tile shows immediately and is replaced if a real icon turns up. Tagging the
     * view with the URL it was bound to keeps a slow load from landing on a recycled row.
     */
    private fun loadIconInto(url: String) = with(binding) {
      tileIcon.tag = url
      scope.launch {
        val icon = loadIcon(url) ?: return@launch
        if (tileIcon.tag != url) return@launch
        tileIcon.setImageBitmap(icon)
        tileIcon.isVisible = true
        tileLetter.isVisible = false
        // A logo needs a pale ground in both themes. ink is primary *text*, which
        // inverts with the theme - using it here turned every tile black in light mode.
        tileBackground.background = null
      }
    }

    private fun tile(color: Int) = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = binding.root.resources.getDimension(R.dimen.tile_corner)
      setColor(color)
    }
  }

  private companion object {
    val DIFF = object : DiffUtil.ItemCallback<Cell>() {
      override fun areItemsTheSame(oldItem: Cell, newItem: Cell): Boolean = when {
        oldItem is Cell.Add && newItem is Cell.Add -> true
        oldItem is Cell.Site && newItem is Cell.Site -> oldItem.shortcut.url == newItem.shortcut.url
        else -> false
      }

      override fun areContentsTheSame(oldItem: Cell, newItem: Cell): Boolean = oldItem == newItem
    }
  }
}
