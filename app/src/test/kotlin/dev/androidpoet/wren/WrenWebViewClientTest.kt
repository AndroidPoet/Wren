/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.net.Uri
import android.webkit.WebResourceRequest
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
  private val handedOff = mutableListOf<String>()
  private val rewritten = mutableListOf<String>()
  private val view = WebView(ApplicationProvider.getApplicationContext())
  private val client = WrenWebViewClient(
    onPageStarted = {},
    onPageFinished = { url, _ -> finished += url },
    onExternalIntent = {
      handedOff += it
      true
    },
    onRewriteUrl = { url ->
      rewritten += url
      url.replace("http://", "https://")
    },
  )

  private fun request(url: String, mainFrame: Boolean, gesture: Boolean) =
    object : WebResourceRequest {
      override fun getUrl(): Uri = Uri.parse(url)
      override fun isForMainFrame() = mainFrame
      override fun isRedirect() = false
      override fun hasGesture() = gesture
      override fun getMethod() = "GET"
      override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

  @Test
  fun `only the document's own navigation is rewritten, never a frame's`() {
    val frame = request("http://tracker.example/pixel", mainFrame = false, gesture = false)
    assertThat(client.shouldOverrideUrlLoading(view, frame)).isFalse()
    assertThat(rewritten).isEmpty()

    val document = request("http://news.example/", mainFrame = true, gesture = true)
    assertThat(client.shouldOverrideUrlLoading(view, document)).isTrue()
    assertThat(rewritten).containsExactly("http://news.example/")
  }

  @Test
  fun `a tapped app link leaves for the other app, an unprompted one is swallowed`() {
    val tapped = request("whatsapp://send?text=hi", mainFrame = true, gesture = true)
    assertThat(client.shouldOverrideUrlLoading(view, tapped)).isTrue()
    assertThat(handedOff).containsExactly("whatsapp://send?text=hi")

    val frame = request("market://details?id=x", mainFrame = false, gesture = false)
    assertThat(client.shouldOverrideUrlLoading(view, frame)).isTrue()
    val redirect = request("intent://scan/#Intent;end", mainFrame = true, gesture = false)
    assertThat(client.shouldOverrideUrlLoading(view, redirect)).isTrue()
    assertThat(handedOff).hasSize(1)
  }

  @Test
  fun `a top frame steered to a script or data address is refused`() {
    assertThat(client.shouldOverrideUrlLoading(view, request("javascript:void(0)", true, false)))
      .isTrue()
    assertThat(client.shouldOverrideUrlLoading(view, request("data:text/html,x", true, false)))
      .isTrue()
    assertThat(handedOff).isEmpty()
  }

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
