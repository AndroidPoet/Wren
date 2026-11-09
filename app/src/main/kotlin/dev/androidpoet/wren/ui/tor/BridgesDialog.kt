/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tor

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.tor.BridgeMode
import dev.androidpoet.wren.browser.tor.Bridges
import dev.androidpoet.wren.databinding.DialogTorBridgesBinding

/** Picks how Tor reaches its first relay. Custom lines are checked before they are kept. */
object BridgesDialog {

  fun show(
    context: Context,
    mode: BridgeMode,
    customLines: String,
    onChosen: (BridgeMode, String) -> Unit,
  ) {
    val binding = DialogTorBridgesBinding.inflate(LayoutInflater.from(context))
    binding.bridgeModes.check(
      when (mode) {
        BridgeMode.NONE -> R.id.bridgeNone
        BridgeMode.OBFS4 -> R.id.bridgeObfs4
        BridgeMode.SNOWFLAKE -> R.id.bridgeSnowflake
        BridgeMode.MEEK -> R.id.bridgeMeek
        BridgeMode.CUSTOM -> R.id.bridgeCustom
      },
    )
    binding.customBridges.setText(customLines)
    binding.customBridgesLayout.isVisible = mode == BridgeMode.CUSTOM
    binding.bridgeModes.setOnCheckedChangeListener { _, checkedId ->
      binding.customBridgesLayout.isVisible = checkedId == R.id.bridgeCustom
      binding.customBridgesLayout.error = null
    }
    val dialog = AlertDialog.Builder(context)
      .setTitle(R.string.tor_bridges_title)
      .setView(binding.root)
      .setPositiveButton(android.R.string.ok, null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    dialog.setOnShowListener {
      // Set after show so a bad line keeps the dialog open with the error under the field.
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val chosen = when (binding.bridgeModes.checkedRadioButtonId) {
          R.id.bridgeObfs4 -> BridgeMode.OBFS4
          R.id.bridgeSnowflake -> BridgeMode.SNOWFLAKE
          R.id.bridgeMeek -> BridgeMode.MEEK
          R.id.bridgeCustom -> BridgeMode.CUSTOM
          else -> BridgeMode.NONE
        }
        val lines = binding.customBridges.text?.toString().orEmpty()
        if (chosen == BridgeMode.CUSTOM) {
          val problem = runCatching { Bridges.parseAll(lines) }.exceptionOrNull()
          if (problem != null) {
            binding.customBridgesLayout.error = if (lines.isBlank()) {
              context.getString(R.string.tor_bridge_custom_empty)
            } else {
              context.getString(R.string.tor_bridge_custom_invalid, problem.message.orEmpty())
            }
            return@setOnClickListener
          }
        }
        onChosen(chosen, lines)
        dialog.dismiss()
      }
    }
    dialog.show()
  }
}
