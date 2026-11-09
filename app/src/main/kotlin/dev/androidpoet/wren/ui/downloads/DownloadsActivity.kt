/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.downloads

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.AppLock
import dev.androidpoet.wren.browser.applyAppLock
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import dev.androidpoet.wren.databinding.ActivityDownloadsBinding
import dev.androidpoet.wren.download.DownloadController
import dev.androidpoet.wren.ui.padForSystemBars
import dev.androidpoet.wren.ui.useLightSystemBarIcons
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsActivity : AppCompatActivity() {

  @Inject
  lateinit var controller: DownloadController

  @Inject
  lateinit var appLock: AppLock

  private val viewModel: DownloadsViewModel by viewModels()
  private lateinit var binding: ActivityDownloadsBinding

  private val adapter = DownloadAdapter(onOpen = ::open, onMenu = ::showRowMenu)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityDownloadsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applyAppLock(appLock, binding.root)
    useLightSystemBarIcons()
    binding.root.padForSystemBars()

    binding.toolbar.setNavigationOnClickListener { finish() }
    binding.clearAllButton.setOnClickListener { viewModel.clearFinished() }
    binding.downloadList.layoutManager = LinearLayoutManager(this)
    binding.downloadList.adapter = adapter

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.rows.collectLatest { rows ->
          adapter.submitList(rows)
          binding.emptyLabel.isVisible = rows.isEmpty()
          binding.clearAllButton.isVisible =
            rows.any { it.download.status != DownloadStatus.RUNNING }
        }
      }
    }
  }

  private fun open(row: DownloadRow) {
    if (row.download.status != DownloadStatus.COMPLETE) return
    val uri = controller.contentUriFor(row.download) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, row.download.mimeType.ifBlank { "*/*" })
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
      startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
    }
  }

  private fun share(row: DownloadRow) {
    val uri = controller.contentUriFor(row.download) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = row.download.mimeType.ifBlank { "*/*" }
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.share_file)))
  }

  private fun showRowMenu(row: DownloadRow, anchor: View) {
    val popup = PopupMenu(this, anchor)
    val menu = popup.menu
    when (row.download.status) {
      DownloadStatus.COMPLETE -> {
        menu.add(0, ACTION_OPEN, 0, R.string.download_open)
        menu.add(0, ACTION_SHARE, 1, R.string.share_file)
        menu.add(0, ACTION_REMOVE, 2, R.string.download_remove)
      }

      DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
        if (controller.canPause(row.download)) {
          if (row.download.status == DownloadStatus.RUNNING) {
            menu.add(0, ACTION_PAUSE, 0, R.string.download_pause)
          } else {
            menu.add(0, ACTION_RESUME, 0, R.string.download_resume)
          }
        }
        menu.add(0, ACTION_CANCEL, 1, R.string.download_cancel)
      }

      DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
        menu.add(0, ACTION_RETRY, 0, R.string.download_retry)
        menu.add(0, ACTION_REMOVE, 1, R.string.download_remove)
      }
    }
    popup.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        ACTION_OPEN -> open(row)
        ACTION_SHARE -> share(row)
        ACTION_RETRY -> if (!viewModel.retry(row.download)) {
          Toast.makeText(this, failureMessage(row.download), Toast.LENGTH_SHORT).show()
        }
        ACTION_PAUSE -> controller.pause(row.download)
        ACTION_RESUME -> if (!controller.resume(row.download)) {
          Toast.makeText(this, failureMessage(row.download), Toast.LENGTH_SHORT).show()
        }
        ACTION_CANCEL, ACTION_REMOVE -> viewModel.dismiss(row.download)
        else -> return@setOnMenuItemClickListener false
      }
      true
    }
    popup.show()
  }

  private fun failureMessage(download: Download): Int =
    if (!controller.routeFor(download)) R.string.download_needs_tor else R.string.download_failed

  private companion object {
    const val ACTION_OPEN = 1
    const val ACTION_SHARE = 2
    const val ACTION_RETRY = 3
    const val ACTION_PAUSE = 6
    const val ACTION_RESUME = 7
    const val ACTION_CANCEL = 4
    const val ACTION_REMOVE = 5
  }
}
