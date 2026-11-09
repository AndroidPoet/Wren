/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.settings

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.AppLock
import dev.androidpoet.wren.browser.applyAppLock
import dev.androidpoet.wren.browser.blocking.FilterListManager
import dev.androidpoet.wren.browser.tor.TorController
import dev.androidpoet.wren.core.data.repository.BackupRepository
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.databinding.ActivitySettingsBinding
import dev.androidpoet.wren.settings.SettingsStore
import dev.androidpoet.wren.ui.padForSystemBars
import dev.androidpoet.wren.ui.tv.TvMode
import dev.androidpoet.wren.ui.useLightSystemBarIcons
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

  @Inject
  lateinit var settings: SettingsStore

  @Inject
  lateinit var backupRepository: BackupRepository

  @Inject
  lateinit var filterListManager: FilterListManager

  @Inject
  lateinit var appLock: AppLock

  @Inject
  lateinit var torController: TorController

  private val exportBookmarksLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
      uri?.let { chosen ->
        writeDocument(chosen, R.string.bookmarks_exported) {
          backupRepository.exportBookmarksHtml()
        }
      }
    }
  private val importBookmarksLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let {
        readDocument(it) { text ->
          val count = backupRepository.importBookmarksHtml(text)
          getString(R.string.bookmarks_imported, count)
        }
      }
    }
  private val backupLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      uri?.let { writeDocument(it, R.string.backup_done) { backupRepository.exportJson() } }
    }
  private val restoreLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let {
        readDocument(it) { text ->
          backupRepository.importJson(text)
          getString(R.string.restore_done)
        }
      }
    }

  private lateinit var binding: ActivitySettingsBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivitySettingsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applyAppLock(appLock, binding.root)
    useLightSystemBarIcons()
    binding.root.padForSystemBars()

    binding.toolbar.setNavigationOnClickListener { finish() }

    renderSearchEngine()
    binding.searchEngineRow.setOnClickListener { chooseSearchEngine() }
    // The TV layout has one bar, at the top, so there is nothing for this switch to move.
    val hasTouchLayout = !TvMode.isTv(this)
    binding.bottomBarSwitch.isVisible = hasTouchLayout
    binding.bottomBarSummary.isVisible = hasTouchLayout
    bindSwitch(
      view = binding.bottomBarSwitch,
      read = { settings.bottomAddressBar },
      write = { settings.bottomAddressBar = it },
    )
    bindSwitch(
      view = binding.keepScreenOnSwitch,
      read = { settings.keepScreenOn },
      write = { settings.keepScreenOn = it },
    )
    binding.orientationRow.setOnClickListener { chooseOrientation() }
    renderOrientation()
    bindSwitch(
      view = binding.dataSaverSwitch,
      read = { settings.dataSaver },
      write = { settings.dataSaver = it },
    )
    binding.appLockSwitch.isChecked = settings.appLock
    binding.appLockSwitch.setOnCheckedChangeListener { _, checked ->
      if (checked && !appLock.canAuthenticate(this)) {
        binding.appLockSwitch.isChecked = false
        android.widget.Toast.makeText(
          this,
          R.string.app_lock_unavailable,
          android.widget.Toast.LENGTH_LONG,
        ).show()
      } else {
        settings.appLock = checked
        appLock.shieldRecents(this)
      }
    }
    binding.filterListRow.setOnClickListener { promptFilterList() }
    renderFilterList()
    binding.exportBookmarksRow.setOnClickListener {
      exportBookmarksLauncher.launch("wren-bookmarks.html")
    }
    binding.importBookmarksRow.setOnClickListener {
      importBookmarksLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
    }
    binding.backupDataRow.setOnClickListener { backupLauncher.launch("wren-backup.json") }
    binding.restoreDataRow.setOnClickListener {
      restoreLauncher.launch(arrayOf("application/json", "*/*"))
    }

    renderTheme()
    binding.themeRow.setOnClickListener { chooseTheme() }

    renderTextSize()
    binding.textSizeRow.setOnClickListener { chooseTextSize() }

    bindSwitch(
      view = binding.remoteSuggestionsSwitch,
      read = { settings.remoteSuggestions },
      write = { settings.remoteSuggestions = it },
    )
    bindSwitch(
      view = binding.javascriptSwitch,
      read = { settings.javaScriptEnabled },
      write = { settings.javaScriptEnabled = it },
    )
    bindSwitch(
      view = binding.blockAdsSwitch,
      read = { settings.blockAds },
      write = { settings.blockAds = it },
    )
    bindSwitch(
      view = binding.darkenWebPagesSwitch,
      read = { settings.darkenWebPages },
      write = { settings.darkenWebPages = it },
    )
    bindSwitch(
      view = binding.preloadPagesSwitch,
      read = { settings.preloadPages },
      write = { settings.preloadPages = it },
    )
    bindSwitch(
      view = binding.siteIconsSwitch,
      read = { settings.loadSiteIcons },
      write = { settings.loadSiteIcons = it },
    )
    binding.torModeSwitch.isChecked = torController.isEnabled
    binding.torModeSwitch.setOnCheckedChangeListener { _, checked ->
      when {
        checked && !torController.isSupported -> {
          binding.torModeSwitch.isChecked = false
          Toast.makeText(this, R.string.tor_unsupported, Toast.LENGTH_SHORT).show()
        }
        checked != torController.isEnabled -> torController.setEnabled(checked)
      }
    }
    bindSwitch(
      view = binding.cookiePopupsSwitch,
      read = { settings.answerCookiePopups },
      write = { settings.answerCookiePopups = it },
    )
    bindSwitch(
      view = binding.fingerprintingSwitch,
      read = { settings.blockFingerprinting },
      write = { settings.blockFingerprinting = it },
    )
    bindSwitch(
      view = binding.trimReferrersSwitch,
      read = { settings.trimReferrers },
      write = { settings.trimReferrers = it },
    )
    bindSwitch(
      view = binding.httpsOnlySwitch,
      read = { settings.httpsOnly },
      write = { settings.httpsOnly = it },
    )
    bindSwitch(
      view = binding.httpsUpgradeSwitch,
      read = { settings.httpsUpgrade },
      write = { settings.httpsUpgrade = it },
    )
    bindSwitch(
      view = binding.stripTrackingSwitch,
      read = { settings.stripTrackingParameters },
      write = { settings.stripTrackingParameters = it },
    )
    bindSwitch(
      view = binding.thirdPartyCookiesSwitch,
      read = { settings.blockThirdPartyCookies },
      write = { settings.blockThirdPartyCookies = it },
    )
    bindSwitch(
      view = binding.doNotTrackSwitch,
      read = { settings.sendDoNotTrack },
      write = { settings.sendDoNotTrack = it },
    )
    bindSwitch(
      view = binding.recordHistorySwitch,
      read = { settings.recordHistory },
      write = { settings.recordHistory = it },
    )
    bindSwitch(
      view = binding.clearOnExitSwitch,
      read = { settings.clearOnExit },
      write = { settings.clearOnExit = it },
    )
    bindSwitch(
      view = binding.desktopModeSwitch,
      read = { settings.desktopMode },
      write = { settings.desktopMode = it },
    )
  }

  private fun bindSwitch(
    view: android.widget.CompoundButton,
    read: () -> Boolean,
    write: (Boolean) -> Unit,
  ) {
    view.isChecked = read()
    view.setOnCheckedChangeListener { _, isChecked -> write(isChecked) }
  }

  private fun renderTheme() {
    binding.themeValue.setText(themeLabel(settings.themeMode))
  }

  private fun themeLabel(mode: Int): Int = when (mode) {
    SettingsStore.MODE_NIGHT_NO -> R.string.theme_light
    SettingsStore.MODE_NIGHT_YES -> R.string.theme_dark
    else -> R.string.theme_system
  }

  private fun chooseTheme() {
    val modes = SettingsStore.THEME_MODES
    val labels = modes.map { getString(themeLabel(it)) }.toTypedArray()
    AlertDialog.Builder(this)
      .setTitle(R.string.theme)
      .setSingleChoiceItems(labels, modes.indexOf(settings.themeMode)) { dialog, which ->
        settings.themeMode = modes[which]
        dialog.dismiss()
        // Recreates every activity in the task, which is how the change reaches the browser.
        AppCompatDelegate.setDefaultNightMode(modes[which])
      }
      .show()
  }

  private fun renderTextSize() {
    binding.textSizeValue.text = getString(R.string.text_size_value, settings.textZoom)
  }

  private fun chooseTextSize() {
    val steps = SettingsStore.TEXT_ZOOM_STEPS
    val labels = steps.map { getString(R.string.text_size_value, it) }.toTypedArray()
    AlertDialog.Builder(this)
      .setTitle(R.string.text_size)
      .setSingleChoiceItems(labels, steps.indexOf(settings.textZoom)) { dialog, which ->
        settings.textZoom = steps[which]
        renderTextSize()
        dialog.dismiss()
      }
      .show()
  }

  private fun renderSearchEngine() {
    binding.searchEngineValue.text = settings.searchLabel
  }

  private fun chooseSearchEngine() {
    val engines = SearchEngine.entries
    val labels = engines.map { it.label } + getString(R.string.search_engine_custom)
    val current =
      if (settings.usingCustomSearch) engines.size else engines.indexOf(settings.searchEngine)
    AlertDialog.Builder(this)
      .setTitle(R.string.search_engine)
      .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
        dialog.dismiss()
        if (which == engines.size) {
          promptCustomSearch()
        } else {
          settings.customSearchTemplate = ""
          settings.searchEngine = engines[which]
          renderSearchEngine()
        }
      }
      .show()
  }

  private fun promptCustomSearch() {
    val pad = (resources.displayMetrics.density * 20).toInt()
    val name = EditText(this).apply {
      hint = getString(R.string.custom_search_name_hint)
      setText(settings.customSearchName)
    }
    val url = EditText(this).apply {
      hint = getString(R.string.custom_search_url_hint)
      setText(settings.customSearchTemplate)
    }
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
      addView(name)
      addView(url)
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.search_engine_custom)
      .setView(container)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val template = url.text.toString().trim()
        if (!template.contains("%s")) {
          android.widget.Toast.makeText(
            this,
            R.string.custom_search_invalid,
            android.widget.Toast.LENGTH_LONG,
          ).show()
          return@setPositiveButton
        }
        settings.customSearchTemplate = template
        settings.customSearchName = name.text.toString().trim()
        renderSearchEngine()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun orientationLabels(): Array<String> = arrayOf(
    getString(R.string.orientation_auto),
    getString(R.string.orientation_portrait),
    getString(R.string.orientation_landscape),
  )

  private fun renderOrientation() {
    binding.orientationValue.text = orientationLabels()[settings.orientationLock]
  }

  private fun chooseOrientation() {
    AlertDialog.Builder(this)
      .setTitle(R.string.orientation)
      .setSingleChoiceItems(orientationLabels(), settings.orientationLock) { dialog, which ->
        settings.orientationLock = which
        renderOrientation()
        dialog.dismiss()
      }
      .show()
  }

  private fun renderFilterList() {
    val enabled = filterListManager.lists.count { filterListManager.isEnabled(it.id) } +
      if (settings.blocklistUrl.isBlank()) 0 else 1
    val updated = filterListManager.lastUpdated()
    binding.filterListValue.text = if (updated == 0L) {
      resources.getQuantityString(R.plurals.filter_lists_enabled, enabled, enabled)
    } else {
      getString(
        R.string.filter_lists_summary,
        resources.getQuantityString(R.plurals.filter_lists_enabled, enabled, enabled),
        android.text.format.DateUtils.getRelativeTimeSpanString(updated),
      )
    }
  }

  /**
   * One dialog for the whole catalog: the defaults cover ads and trackers worldwide, the rest
   * are opt-in because each costs memory and can hide things a reader wanted. Toggling a list
   * fetches it in the background and recompiles the blocker; nothing here blocks the UI.
   */
  private fun promptFilterList() {
    val lists = filterListManager.lists
    val labels = lists.map { list ->
      val prefix = when (list.group) {
        dev.androidpoet.wren.browser.blocking.FilterList.Group.CORE -> ""
        dev.androidpoet.wren.browser.blocking.FilterList.Group.ANNOYANCES ->
          getString(R.string.filter_group_annoyances) + ": "
        dev.androidpoet.wren.browser.blocking.FilterList.Group.REGIONAL ->
          getString(R.string.filter_group_regional) + ": "
      }
      prefix + list.title
    }.toTypedArray()
    val checked = BooleanArray(lists.size) { filterListManager.isEnabled(lists[it].id) }
    AlertDialog.Builder(this)
      .setTitle(R.string.filter_list)
      .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
        filterListManager.setEnabled(lists[which].id, isChecked)
        renderFilterList()
      }
      .setNeutralButton(R.string.filter_list_custom) { _, _ -> promptCustomFilterList() }
      .setNegativeButton(R.string.filter_lists_update_now) { _, _ -> updateFilterLists() }
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun promptCustomFilterList() {
    val pad = (resources.displayMetrics.density * 20).toInt()
    val input = EditText(this).apply {
      hint = getString(R.string.filter_list_hint)
      setText(settings.blocklistUrl)
    }
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
      addView(input)
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.filter_list_custom)
      .setView(container)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        settings.blocklistUrl = input.text.toString().trim()
        updateFilterLists()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun updateFilterLists() {
    renderFilterList()
    lifecycleScope.launch {
      val count = filterListManager.refreshNow()
      val message = when {
        count < 0 -> getString(R.string.filter_list_failed)
        count == 0 -> getString(R.string.filter_lists_current)
        else -> resources.getQuantityString(R.plurals.filter_lists_updated, count, count)
      }
      android.widget.Toast.makeText(
        this@SettingsActivity,
        message,
        android.widget.Toast.LENGTH_LONG,
      ).show()
      renderFilterList()
    }
  }

  /** Streams produced content into a user-chosen document, entirely off the main thread. */
  private fun writeDocument(uri: Uri, doneText: Int, produce: suspend () -> String) {
    lifecycleScope.launch {
      val result = runCatching {
        val text = produce()
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
          ?: error("no stream")
      }
      toastResult(result.isSuccess, getString(doneText))
    }
  }

  private fun readDocument(uri: Uri, consume: suspend (String) -> String) {
    lifecycleScope.launch {
      val result = runCatching {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
          ?: error("no stream")
        consume(text)
      }
      toastResult(result.isSuccess, result.getOrNull().orEmpty())
    }
  }

  private fun toastResult(ok: Boolean, doneText: String) {
    val message = if (ok) doneText else getString(R.string.data_action_failed)
    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
  }
}
