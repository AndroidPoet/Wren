/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.databinding.SheetTabsBinding
import dev.androidpoet.wren.ui.browser.BrowserActivity
import dev.androidpoet.wren.ui.browser.BrowserViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The tab switcher. Shares [BrowserViewModel] with the activity so the tab list is one source. */
@AndroidEntryPoint
class TabsSheet : BottomSheetDialogFragment() {

  private val viewModel: BrowserViewModel by activityViewModels()
  private var binding: SheetTabsBinding? = null

  @Inject
  lateinit var thumbnails: TabThumbnails

  private val adapter by lazy {
    TabAdapter(
      thumbnailFor = { id -> thumbnails[id] },
      onSelect = { id ->
        (activity as? BrowserActivity)?.switchToTab(id)
        dismiss()
      },
      onClose = { id -> (activity as? BrowserActivity)?.closeTab(id) },
    )
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = SheetTabsBinding.inflate(inflater, container, false).also { binding = it }.root

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val binding = requireNotNull(binding)
    binding.tabList.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
    binding.tabList.adapter = adapter
    installSwipeToClose(binding.tabList)
    expandFully()
    binding.sessionButton.text =
      getString(dev.androidpoet.wren.R.string.session_label, currentSessionName())
    binding.sessionButton.setOnClickListener {
      (activity as? BrowserActivity)?.showSessionManager()
      dismiss()
    }
    binding.newTabButton.setOnClickListener {
      (activity as? BrowserActivity)?.openNewTab(isPrivate = false)
      dismiss()
    }
    binding.newPrivateTabButton.setOnClickListener {
      (activity as? BrowserActivity)?.openNewTab(isPrivate = true)
      dismiss()
    }

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          viewModel.tabManager.snapshots.collectLatest { tabs ->
            adapter.activeTabId = viewModel.tabManager.activeTabId.value
            adapter.submitList(tabs)
          }
        }
        launch {
          viewModel.tabManager.activeTabId.collectLatest { id ->
            adapter.activeTabId = id
            adapter.notifyDataSetChanged()
          }
        }
      }
    }
  }

  /** The switcher is a place of its own, not a peek: open at full height like Chrome's grid. */
  private fun expandFully() {
    val sheet = (dialog as? BottomSheetDialog) ?: return
    sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    sheet.behavior.skipCollapsed = true
    (view?.parent as? View)?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
  }

  private fun installSwipeToClose(list: RecyclerView) {
    val callback = object : ItemTouchHelper.SimpleCallback(
      0,
      ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    ) {
      override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
      ): Boolean = false

      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.tabIdAt(viewHolder.bindingAdapterPosition)
          ?.let { (activity as? BrowserActivity)?.closeTab(it) }
      }
    }
    ItemTouchHelper(callback).attachToRecyclerView(list)
  }

  override fun onDestroyView() {
    binding?.tabList?.adapter = null
    binding = null
    super.onDestroyView()
  }

  private fun currentSessionName(): String =
    (activity as? BrowserActivity)?.currentSessionName().orEmpty()

  companion object {
    private const val TAG = "TabsSheet"
    private const val GRID_COLUMNS = 2

    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        TabsSheet().show(fragmentManager, TAG)
      }
    }
  }
}
