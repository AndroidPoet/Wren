/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.tor.BridgeMode
import dev.androidpoet.wren.browser.tor.Bridges
import dev.androidpoet.wren.browser.tor.TorState
import dev.androidpoet.wren.browser.tor.TorStatus
import dev.androidpoet.wren.databinding.SheetTorBinding
import dev.androidpoet.wren.ui.inheritSecureFlag
import dev.androidpoet.wren.ui.tv.TvMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The panel behind the Tor pill: what the connection is doing, and the switch that owns it. */
class TorSheet : BottomSheetDialogFragment() {

  interface Host {
    fun torState(): StateFlow<TorState>
    fun isTorEnabled(): Boolean
    fun onTorSetEnabled(enabled: Boolean)
    fun onTorNewIdentity()
    fun onTorVerify()
    fun isTorSafest(): Boolean
    fun onTorSafestChanged(enabled: Boolean)
    fun torBridgeMode(): BridgeMode
    fun torCustomBridges(): String
    fun onTorBridgesChanged(mode: BridgeMode, customLines: String)
  }

  private var binding: SheetTorBinding? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = SheetTorBinding.inflate(inflater, container, false)
    .also { binding = it }
    .root

  override fun onStart() {
    super.onStart()
    TvMode.expandSheet(this)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    inheritSecureFlag()
    val binding = requireNotNull(binding)
    val host = activity as? Host
    if (host == null) {
      dismiss()
      return
    }
    binding.torSwitch.isChecked = host.isTorEnabled()
    binding.torSwitch.setOnCheckedChangeListener { _, checked ->
      if (checked != host.isTorEnabled()) host.onTorSetEnabled(checked)
    }
    binding.torSafestSwitch.isChecked = host.isTorSafest()
    binding.torSafestSwitch.setOnCheckedChangeListener { _, checked ->
      host.onTorSafestChanged(checked)
    }
    binding.torNewIdentity.setOnClickListener {
      host.onTorNewIdentity()
      dismiss()
    }
    binding.torVerify.setOnClickListener {
      host.onTorVerify()
      dismiss()
    }
    renderBridges(binding, host)
    binding.torBridges.setOnClickListener {
      BridgesDialog.show(
        requireContext(),
        host.torBridgeMode(),
        host.torCustomBridges(),
      ) { mode, lines ->
        host.onTorBridgesChanged(mode, lines)
        this.binding?.let { renderBridges(it, host) }
      }
    }
    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        host.torState().collect { render(binding, it, host.isTorEnabled()) }
      }
    }
  }

  private fun render(binding: SheetTorBinding, state: TorState, enabled: Boolean) {
    binding.torStatus.text = when (state.status) {
      TorStatus.ON -> getString(R.string.tor_on)
      TorStatus.STARTING -> getString(R.string.tor_connecting, state.progress)
      TorStatus.STOPPING -> getString(R.string.tor_stopping)
      TorStatus.FAILED -> state.error?.takeIf { it.startsWith(TRANSPORT_MISSING) }
        ?.let { getString(R.string.tor_bridge_transport_missing) }
        ?: getString(
          if ((activity as? Host)?.torBridgeMode() == BridgeMode.NONE) {
            R.string.tor_failed_try_bridge
          } else {
            R.string.tor_failed
          },
        )
      TorStatus.OFF -> getString(R.string.tor_off)
    }
    binding.torStatusIcon.alpha = if (state.isOn) 1f else DIM_ALPHA
    binding.torNewIdentity.isEnabled = state.isOn
    binding.torVerify.isEnabled = state.isOn
    binding.torSafestSwitch.isEnabled = enabled
    if (binding.torSwitch.isChecked != enabled) binding.torSwitch.isChecked = enabled
  }

  private fun renderBridges(binding: SheetTorBinding, host: Host) {
    binding.torBridges.text = when (host.torBridgeMode()) {
      BridgeMode.NONE -> getString(R.string.tor_bridge_status_none)
      BridgeMode.OBFS4 -> getString(R.string.tor_bridge_status_obfs4)
      BridgeMode.SNOWFLAKE -> getString(R.string.tor_bridge_status_snowflake)
      BridgeMode.MEEK -> getString(R.string.tor_bridge_status_meek)
      BridgeMode.CUSTOM -> getString(
        R.string.tor_bridge_status_custom,
        runCatching { Bridges.parseAll(host.torCustomBridges()).size }.getOrDefault(0),
      )
    }
  }

  override fun onDestroyView() {
    binding = null
    super.onDestroyView()
  }

  companion object {
    private const val TAG = "TorSheet"
    private const val DIM_ALPHA = 0.45f
    const val TRANSPORT_MISSING = "bridge transport missing"

    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) != null) return
      TorSheet().show(fragmentManager, TAG)
    }
  }
}
