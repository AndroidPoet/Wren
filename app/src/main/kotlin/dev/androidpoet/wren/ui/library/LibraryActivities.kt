/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.AppLock
import dev.androidpoet.wren.browser.applyAppLock
import dev.androidpoet.wren.databinding.ActivityLibraryBinding
import dev.androidpoet.wren.ui.browser.BrowserActivity
import dev.androidpoet.wren.ui.padForSystemBars
import dev.androidpoet.wren.ui.useLightSystemBarIcons
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared chrome for the two list screens. Both show the same row, differ only in where the rows
 * come from and what removing one means.
 */
abstract class LibraryActivity : AppCompatActivity() {

  @Inject
  lateinit var appLock: AppLock

  protected lateinit var binding: ActivityLibraryBinding

  protected val adapter by lazy {
    LinkAdapter(
      onClick = ::openInBrowser,
      onRemove = { row -> onRemove(row.url) },
      onLongClick = onRowLongPress(),
    )
  }

  /** Screens that offer a long-press action on rows return a handler; others inherit none. */
  protected open fun onRowLongPress(): ((LinkRow) -> Unit)? = null

  protected abstract val titleRes: Int
  protected abstract val emptyRes: Int
  protected abstract val rows: StateFlow<List<LinkRow>>

  protected abstract fun onRemove(url: String)

  protected abstract fun onClearAll()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityLibraryBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applyAppLock(appLock, binding.root)
    useLightSystemBarIcons()
    binding.root.padForSystemBars()

    binding.toolbar.setTitle(titleRes)
    binding.toolbar.setNavigationOnClickListener { finish() }
    binding.emptyLabel.setText(emptyRes)
    binding.clearAllButton.setOnClickListener { onClearAll() }

    binding.linkList.layoutManager = LinearLayoutManager(this)
    binding.linkList.adapter = adapter

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        rows.collectLatest { items ->
          adapter.submitList(items)
          binding.emptyLabel.isVisible = items.isEmpty()
          binding.clearAllButton.isVisible = items.isNotEmpty()
        }
      }
    }
  }

  private fun openInBrowser(row: LinkRow) {
    startActivity(
      Intent(this, BrowserActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = row.url.toUri()
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      },
    )
    finish()
  }
}

@AndroidEntryPoint
class HistoryActivity : LibraryActivity() {

  private val viewModel: HistoryViewModel by viewModels()

  override val titleRes: Int = R.string.history
  override val emptyRes: Int = R.string.history_empty
  override val rows: StateFlow<List<LinkRow>> get() = viewModel.rows

  override fun onRemove(url: String) = viewModel.remove(url)

  override fun onClearAll() = viewModel.clearAll()
}

@AndroidEntryPoint
class BookmarksActivity : LibraryActivity() {

  private val viewModel: BookmarksViewModel by viewModels()

  override val titleRes: Int = R.string.bookmarks
  override val emptyRes: Int = R.string.bookmarks_empty
  override val rows: StateFlow<List<LinkRow>> get() = viewModel.rows

  override fun onRemove(url: String) = viewModel.remove(url)

  override fun onClearAll() = viewModel.clearAll()

  override fun onRowLongPress(): ((LinkRow) -> Unit) = ::promptMoveToFolder

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.folders.collectLatest(::renderFolderChips)
      }
    }
  }

  private fun renderFolderChips(folders: List<String>) {
    binding.folderBar.isVisible = folders.isNotEmpty()
    binding.folderChips.removeAllViews()
    val names = listOf<String?>(null) + folders
    names.forEach { folder ->
      val chip = com.google.android.material.chip.Chip(this).apply {
        text = folder ?: getString(R.string.folder_all)
        isCheckable = true
        isChecked = viewModel.activeFolder.value == folder
        setOnClickListener { viewModel.activeFolder.value = folder }
      }
      binding.folderChips.addView(chip)
    }
  }

  private fun promptMoveToFolder(row: LinkRow) {
    val folders = viewModel.folders.value
    val labels = buildList {
      add(getString(R.string.no_folder))
      addAll(folders)
      add(getString(R.string.new_folder))
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.move_to_folder)
      .setItems(labels.toTypedArray()) { _, which ->
        when (which) {
          0 -> viewModel.moveToFolder(row.url, "")
          labels.lastIndex -> promptNewFolder(row)
          else -> viewModel.moveToFolder(row.url, folders[which - 1])
        }
      }
      .show()
  }

  private fun promptNewFolder(row: LinkRow) {
    val pad = (resources.displayMetrics.density * 20).toInt()
    val input = android.widget.EditText(this).apply { hint = getString(R.string.folder_name_hint) }
    val container = android.widget.LinearLayout(this).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
      addView(input)
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.new_folder)
      .setView(container)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val name = input.text.toString().trim()
        if (name.isNotEmpty()) viewModel.moveToFolder(row.url, name)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
