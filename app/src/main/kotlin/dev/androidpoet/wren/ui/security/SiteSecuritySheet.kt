/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.security

import android.net.http.SslCertificate
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.IdnDisplay
import dev.androidpoet.wren.browser.blocking.TrackerOwners
import dev.androidpoet.wren.databinding.ItemTrackerGroupBinding
import dev.androidpoet.wren.databinding.SheetSiteSecurityBinding
import dev.androidpoet.wren.ui.inheritSecureFlag
import dev.androidpoet.wren.ui.tv.TvMode
import java.text.DateFormat

/** What the lock icon knows about the page currently loaded. */
data class SiteSecurityInfo(
  val host: String,
  val isSecure: Boolean,
  val certificate: SslCertificate?,
  val blockedHosts: List<String>,
  val isBlockingEnabled: Boolean,
  val isJsEnabledHere: Boolean,
  val isDesktopHere: Boolean,
  val isKeptSignedIn: Boolean,
  /** Under Tor the site switch covers blocking only; the page scripts always stay. */
  val isOnTor: Boolean = false,
)

/**
 * The panel behind the lock icon: whether the connection is encrypted, who vouched for it, and
 * what was blocked on the way in.
 *
 * The certificate is worth surfacing because the padlock on its own says almost nothing - it
 * means the connection is encrypted, not that the site is who it claims to be to a human reader.
 * Showing who issued it is what lets someone notice that a familiar name has an unfamiliar
 * issuer.
 */
class SiteSecuritySheet : BottomSheetDialogFragment() {

  /** The hosting activity supplies the live page state; none of it survives a process death. */
  interface Host {
    fun siteSecurityInfo(): SiteSecurityInfo?
    fun onToggleSiteBlocking()
    fun onSetSiteJavascript(enabled: Boolean)
    fun onSetSiteDesktop(enabled: Boolean)
    fun onSetKeepSignedIn(enabled: Boolean)
    fun onForgetSite()
    fun onReportBrokenSite()
  }

  private var binding: SheetSiteSecurityBinding? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = SheetSiteSecurityBinding.inflate(inflater, container, false)
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
    val info = host?.siteSecurityInfo()
    if (info == null) {
      dismiss()
      return
    }

    binding.securityHost.text = IdnDisplay.forDisplay(info.host)
    binding.securityConnection.setText(
      if (info.isSecure) R.string.connection_secure else R.string.connection_not_secure,
    )
    binding.securityConnectionIcon.setImageResource(
      if (info.isSecure) R.drawable.ic_lock else R.drawable.ic_lock_open,
    )

    bindCertificate(binding, info.certificate)
    bindTrackers(binding, info)

    binding.securityBlockingAction.setOnClickListener {
      host.onToggleSiteBlocking()
      dismiss()
    }
    binding.siteJavascriptSwitch.isChecked = info.isJsEnabledHere
    binding.siteJavascriptSwitch.setOnCheckedChangeListener { _, checked ->
      host.onSetSiteJavascript(checked)
      dismiss()
    }
    binding.securityReportAction.setOnClickListener {
      host.onReportBrokenSite()
      dismiss()
    }
    binding.securityForgetAction.setOnClickListener {
      host.onForgetSite()
      dismiss()
    }
    binding.siteDesktopSwitch.isChecked = info.isDesktopHere
    binding.siteDesktopSwitch.setOnCheckedChangeListener { _, checked ->
      host.onSetSiteDesktop(checked)
      dismiss()
    }
    // Takes effect at the next clear, so the sheet stays open: nothing to reload.
    binding.siteKeepSignedInSwitch.isChecked = info.isKeptSignedIn
    binding.siteKeepSignedInSwitch.setOnCheckedChangeListener { _, checked ->
      host.onSetKeepSignedIn(checked)
    }
  }

  private fun bindCertificate(binding: SheetSiteSecurityBinding, certificate: SslCertificate?) {
    if (certificate == null) {
      binding.certificateGroup.isVisible = false
      return
    }
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    binding.certificateGroup.isVisible = true
    binding.certificateIssuedTo.text = describe(
      certificate.issuedTo?.cName,
      certificate.issuedTo?.oName,
    )
    binding.certificateIssuedBy.text = describe(
      certificate.issuedBy?.cName,
      certificate.issuedBy?.oName,
    )
    binding.certificateValidity.text = getString(
      R.string.certificate_validity,
      certificate.validNotBeforeDate?.let(dates::format).orEmpty(),
      certificate.validNotAfterDate?.let(dates::format).orEmpty(),
    )
  }

  private fun bindTrackers(binding: SheetSiteSecurityBinding, info: SiteSecurityInfo) {
    binding.trackerCount.text = resources.getQuantityString(
      R.plurals.trackers_blocked_here,
      info.blockedHosts.size,
      info.blockedHosts.size,
    )
    val groups = binding.trackerGroups
    groups.removeAllViews()
    groups.isVisible = info.blockedHosts.isNotEmpty()
    val inflater = layoutInflater
    TrackerOwners.group(info.blockedHosts, getString(R.string.tracker_owner_other))
      .forEach { (owner, hosts) ->
        val row = ItemTrackerGroupBinding.inflate(inflater, groups, true)
        row.ownerName.text = owner
        row.ownerCount.text =
          resources.getQuantityString(R.plurals.tracker_host_count, hosts.size, hosts.size)
        val shown = hosts.take(MAX_HOSTS_PER_OWNER)
        row.ownerHosts.text = buildString {
          append(shown.joinToString("\n"))
          if (hosts.size > shown.size) {
            append('\n').append(getString(R.string.tracker_more_hosts, hosts.size - shown.size))
          }
        }
      }
    binding.securityBlockingAction.setText(
      when {
        info.isOnTor && info.isBlockingEnabled -> R.string.allow_ads_here
        info.isOnTor -> R.string.block_ads_here
        info.isBlockingEnabled -> R.string.protection_off_here
        else -> R.string.protection_on_here
      },
    )
  }

  private fun describe(commonName: String?, organisation: String?): String = listOfNotNull(
    commonName?.takeIf { it.isNotBlank() },
    organisation?.takeIf { it.isNotBlank() },
  ).joinToString(" · ").ifBlank { getString(R.string.certificate_unknown) }

  override fun onDestroyView() {
    binding = null
    super.onDestroyView()
  }

  companion object {
    private const val TAG = "SiteSecuritySheet"
    private const val MAX_HOSTS_PER_OWNER = 3

    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(TAG) == null) {
        SiteSecuritySheet().show(fragmentManager, TAG)
      }
    }
  }
}
