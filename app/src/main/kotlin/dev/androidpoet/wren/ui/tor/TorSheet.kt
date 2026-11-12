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
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.tor.BridgeMode
import dev.androidpoet.wren.browser.tor.Bridges
import dev.androidpoet.wren.browser.tor.TorCircuit
import dev.androidpoet.wren.browser.tor.TorController
import dev.androidpoet.wren.browser.tor.TorState
import dev.androidpoet.wren.browser.tor.TorStatus
import dev.androidpoet.wren.browser.tor.TorVpnStatus
import dev.androidpoet.wren.databinding.SheetTorBinding
import dev.androidpoet.wren.settings.TorSecurityLevel
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
    fun torSecurityLevel(): TorSecurityLevel
    fun onTorSecurityLevelChanged(level: TorSecurityLevel)
    fun torBridgeMode(): BridgeMode
    fun torCustomBridges(): String
    fun onTorBridgesChanged(mode: BridgeMode, customLines: String)

    /** The host of the page on screen, when it is a web page; its circuit is shown. */
    fun torSiteHost(): String?
    suspend fun torCircuitFor(host: String): TorCircuit?
    fun onTorNewCircuitForSite(host: String)
    fun torVpnStatus(): StateFlow<TorVpnStatus>
    fun isTorVpnEnabled(): Boolean

    /** The host asks for VPN consent first when turning it on; it answers with the result. */
    fun onTorVpnChanged(enabled: Boolean)
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
    binding.torLevelGroup.check(levelButton(host.torSecurityLevel()))
    renderLevel(binding, host.torSecurityLevel())
    binding.torLevelGroup.addOnButtonCheckedListener { _, id, checked ->
      if (!checked) return@addOnButtonCheckedListener
      val level = when (id) {
        R.id.torLevelSafer -> TorSecurityLevel.SAFER
        R.id.torLevelSafest -> TorSecurityLevel.SAFEST
        else -> TorSecurityLevel.STANDARD
      }
      renderLevel(binding, level)
      if (level != host.torSecurityLevel()) host.onTorSecurityLevelChanged(level)
    }
    binding.torVpnSwitch.isChecked = host.isTorVpnEnabled()
    binding.torVpnSwitch.setOnCheckedChangeListener { _, checked ->
      if (checked != host.isTorVpnEnabled()) host.onTorVpnChanged(checked)
    }
    host.torSiteHost()?.let { site ->
      binding.torNewCircuit.setOnClickListener {
        host.onTorNewCircuitForSite(site)
        dismiss()
      }
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
        launch { host.torState().collect { render(binding, it, host.isTorEnabled()) } }
        launch { host.torVpnStatus().collect { renderVpn(binding, host, it) } }
        launch { renderCircuit(binding, host) }
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
        ?: state.error?.takeIf { it == TorController.PROXY_UNSUPPORTED }
          ?.let { getString(R.string.tor_unsupported) }
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
    for (i in 0 until binding.torLevelGroup.childCount) binding.torLevelGroup.getChildAt(
      i,
    ).isEnabled = enabled
    binding.torVpnSwitch.isEnabled = enabled || binding.torVpnSwitch.isChecked
    if (binding.torSwitch.isChecked != enabled) binding.torSwitch.isChecked = enabled
  }

  private fun levelButton(level: TorSecurityLevel): Int = when (level) {
    TorSecurityLevel.STANDARD -> R.id.torLevelStandard
    TorSecurityLevel.SAFER -> R.id.torLevelSafer
    TorSecurityLevel.SAFEST -> R.id.torLevelSafest
  }

  private fun renderLevel(binding: SheetTorBinding, level: TorSecurityLevel) {
    binding.torLevelSummary.setText(
      when (level) {
        TorSecurityLevel.STANDARD -> R.string.tor_level_standard_summary
        TorSecurityLevel.SAFER -> R.string.tor_level_safer_summary
        TorSecurityLevel.SAFEST -> R.string.tor_safest_summary
      },
    )
  }

  private fun renderVpn(binding: SheetTorBinding, host: Host, status: TorVpnStatus) {
    val enabled = host.isTorVpnEnabled()
    if (binding.torVpnSwitch.isChecked != enabled) binding.torVpnSwitch.isChecked = enabled
    binding.torVpnSummary.setText(
      when {
        status == TorVpnStatus.ON -> R.string.tor_vpn_running
        status == TorVpnStatus.FAILED && enabled -> R.string.tor_vpn_failed
        else -> R.string.tor_vpn_summary
      },
    )
  }

  /** Tor Browser's circuit display for the page on screen, read once when the sheet opens. */
  private suspend fun renderCircuit(binding: SheetTorBinding, host: Host) {
    val site = host.torSiteHost()
    if (site == null || !host.isTorEnabled()) {
      binding.torCircuitSection.isVisible = false
      return
    }
    binding.torCircuitSection.isVisible = true
    binding.torCircuitTitle.text = getString(R.string.tor_circuit_title, site)
    val circuit = host.torCircuitFor(site)
    binding.torCircuitPath.text = if (circuit == null) {
      getString(R.string.tor_circuit_none)
    } else {
      (
        listOf(getString(R.string.tor_circuit_you)) +
          circuit.relays.map { relay ->
            relay.address?.let { getString(R.string.tor_circuit_relay, relay.nickname, it) }
              ?: getString(R.string.tor_circuit_bridge, relay.nickname)
          } +
          site
        ).joinToString("\n")
    }
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
