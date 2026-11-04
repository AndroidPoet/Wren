/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.ui.browser.WrenWebViewClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Finished" ends the progress bar, records the visit and takes the tab thumbnail, so it has to
 * mean the page finished. A normal load also updates history at commit, long before it is done;
 * only a single-page app changing its route has nothing but that history update to go on.
 */
@RunWith(RobolectricTestRunner::class)
class WrenWebViewClientTest {

  private val finished = mutableListOf<String>()
  private val view = WebView(ApplicationProvider.getApplicationContext())
  private val client = WrenWebViewClient(
    onPageStarted = {},
    onPageFinished = { url, _ -> finished += url },
    onExternalIntent = { false },
  )

  @Test
  fun `a normal load finishes once, at the end, not at commit`() {
    client.onPageStarted(view, PAGE, null)
    client.doUpdateVisitedHistory(view, PAGE, false)

    assertThat(finished).isEmpty()

    client.onPageFinished(view, PAGE)

    assertThat(finished).containsExactly(PAGE)
  }

  @Test
  fun `a single-page route change still reports, since no load will`() {
    client.onPageStarted(view, PAGE, null)
    client.onPageFinished(view, PAGE)

    client.doUpdateVisitedHistory(view, ROUTE, false)

    assertThat(finished).containsExactly(PAGE, ROUTE).inOrder()
  }

  @Test
  fun `a reload is not a new visit`() {
    client.doUpdateVisitedHistory(view, PAGE, true)

    assertThat(finished).isEmpty()
  }

  private companion object {
    const val PAGE = "https://example.com/"
    const val ROUTE = "https://example.com/inbox"
  }
}
