/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.menu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.androidpoet.wren.R
import dev.androidpoet.wren.databinding.ItemMenuSectionBinding
import dev.androidpoet.wren.databinding.ItemMenuTileBinding
import dev.androidpoet.wren.databinding.SheetMenuBinding
import dev.androidpoet.wren.ui.inheritSecureFlag

/** Everything the overflow menu can do, in the order the sheet lays it out. */
enum class MenuAction(
  @StringRes val label: Int,
  @DrawableRes val icon: Int,
  /** Acts on the loaded page, so it has nothing to do while the home screen is up. */
  val needsPage: Boolean = false,
) {
  BOOKMARK(R.string.add_bookmark, R.drawable.ic_bookmark_outline, needsPage = true),
  SHARE(R.string.share_page, R.drawable.ic_share, needsPage = true),
  FIND(R.string.find_in_page, R.drawable.ic_find, needsPage = true),
  READER(R.string.reader_mode, R.drawable.ic_reader, needsPage = true),
  TRANSLATE(R.string.translate_page, R.drawable.ic_translate, needsPage = true),
  DESKTOP_SITE(R.string.desktop_site, R.drawable.ic_desktop),
  RENDERING(R.string.rendering_mode, R.drawable.ic_tune, needsPage = true),
  SITE_BLOCKING(R.string.blocking_on_site, R.drawable.ic_shield, needsPage = true),
  PRINT(R.string.print_page, R.drawable.ic_print, needsPage = true),
  SAVE_PAGE(R.string.save_page, R.drawable.ic_save_offline, needsPage = true),
  ADD_TO_HOME(R.string.add_to_home, R.drawable.ic_add_to_home, needsPage = true),
  SHARE_QR(R.string.share_qr, R.drawable.ic_qr, needsPage = true),
  NEW_TAB(R.string.new_tab, R.drawable.ic_add),
  NEW_PRIVATE_TAB(R.string.new_private_tab_short, R.drawable.ic_private),
  TOR(R.string.tor, R.drawable.ic_tor),
  BOOKMARKS(R.string.bookmarks, R.drawable.ic_bookmarks),
  HISTORY(R.string.history, R.drawable.ic_history),
  DOWNLOADS(R.string.downloads, R.drawable.ic_download),
  CLEAR_DATA(R.string.clear_browsing_data, R.drawable.ic_clear_data),
  SETTINGS(R.string.settings, R.drawable.ic_settings),
}

/** The bits of page state that change how a tile reads. */
data class MenuState(
  val hasPage: Boolean,
  val isBookmarked: Boolean,
  val isReaderActive: Boolean,
  val isDesktopSite: Boolean,
  val siteBlockingLabel: String,
  val torLabel: String,
  val isTorOn: Boolean,
)

/**
 * The overflow menu as a grid of tiles in a bottom sheet, grouped into what acts on this page
 * and what belongs to the browser as a whole.
 *
 * Nineteen actions in a single popup column ran off the bottom of the screen; four to a row
 * they fit in one glance, and the icon does most of the telling so the labels can stay short.
 */
class MenuSheet : BottomSheetDialogFragment() {

  interface Host {
    fun menuState(): MenuState
    fun onMenuAction(action: MenuAction)
  }

  private var binding: SheetMenuBinding? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = SheetMenuBinding.inflate(inflater, container, false)
    .also { binding = it }
    .root

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    inheritSecureFlag()
    val host = activity as? Host
    if (host == null) {
      dismiss()
      return
    }
    val state = host.menuState()
    val sections = requireNotNull(binding).menuSections
    addSection(sections, R.string.menu_section_page, PAGE_ACTIONS, state, host)
    addSection(sections, R.string.menu_section_browser, BROWSER_ACTIONS, state, host)
  }

  private fun addSection(
    parent: LinearLayout,
    @StringRes title: Int,
    actions: List<MenuAction>,
    state: MenuState,
    host: Host,
  ) {
    val inflater = layoutInflater
    parent.addView(
      ItemMenuSectionBinding.inflate(inflater, parent, false).root.apply { setText(title) },
    )
    actions.chunked(COLUMNS).forEach { row ->
      val rowView = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        )
      }
      row.forEach { action -> rowView.addView(tileFor(inflater, rowView, action, state, host)) }
      // Pad a short last row so its tiles keep the same width as the full rows above.
      repeat(COLUMNS - row.size) {
        rowView.addView(Space(parent.context), LinearLayout.LayoutParams(0, 0, 1f))
      }
      parent.addView(rowView)
    }
  }

  private fun tileFor(
    inflater: LayoutInflater,
    parent: ViewGroup,
    action: MenuAction,
    state: MenuState,
    host: Host,
  ): View {
    val tile = ItemMenuTileBinding.inflate(inflater, parent, false)
    val active = when (action) {
      MenuAction.BOOKMARK -> state.isBookmarked
      MenuAction.READER -> state.isReaderActive
      MenuAction.DESKTOP_SITE -> state.isDesktopSite
      MenuAction.TOR -> state.isTorOn
      else -> false
    }
    tile.tileLabel.text = when (action) {
      MenuAction.BOOKMARK ->
        getString(if (state.isBookmarked) R.string.remove_bookmark else R.string.add_bookmark)
      MenuAction.READER ->
        getString(if (state.isReaderActive) R.string.reader_mode_exit else R.string.reader_mode)
      MenuAction.SITE_BLOCKING -> state.siteBlockingLabel
      MenuAction.TOR -> state.torLabel
      else -> getString(action.label)
    }
    tile.tileIcon.setImageResource(
      if (action == MenuAction.BOOKMARK && state.isBookmarked) {
        R.drawable.ic_bookmark
      } else {
        action.icon
      },
    )
    val context = tile.root.context
    ImageViewCompat.setImageTintList(
      tile.tileIcon,
      ContextCompat.getColorStateList(context, if (active) R.color.russet else R.color.ink),
    )
    tile.tileBackground.setBackgroundResource(
      if (active) R.drawable.bg_menu_tile_active else R.drawable.bg_menu_tile,
    )
    val enabled = state.hasPage || !action.needsPage
    tile.root.isEnabled = enabled
    tile.root.alpha = if (enabled) 1f else DISABLED_ALPHA
    tile.root.setOnClickListener {
      dismiss()
      host.onMenuAction(action)
    }
    return tile.root
  }

  override fun onDestroyView() {
    binding = null
    super.onDestroyView()
  }

  companion object {
    private const val TAG = "MenuSheet"
    private const val COLUMNS = 4
    private const val DISABLED_ALPHA = 0.35f

    private val PAGE_ACTIONS = listOf(
      MenuAction.BOOKMARK,
      MenuAction.SHARE,
      MenuAction.FIND,
      MenuAction.READER,
      MenuAction.TRANSLATE,
      MenuAction.DESKTOP_SITE,
      MenuAction.RENDERING,
      MenuAction.SITE_BLOCKING,
      MenuAction.PRINT,
      MenuAction.SAVE_PAGE,
      MenuAction.ADD_TO_HOME,
      MenuAction.SHARE_QR,
    )

    private val BROWSER_ACTIONS = listOf(
      MenuAction.NEW_TAB,
      MenuAction.NEW_PRIVATE_TAB,
      MenuAction.TOR,
      MenuAction.BOOKMARKS,
      MenuAction.HISTORY,
      MenuAction.DOWNLOADS,
      MenuAction.CLEAR_DATA,
      MenuAction.SETTINGS,
    )

    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        MenuSheet().show(fragmentManager, TAG)
      }
    }
  }
}
