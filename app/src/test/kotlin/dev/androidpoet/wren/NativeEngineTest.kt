/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.rules.NativeEngine
import dev.androidpoet.wren.browser.blocking.rules.NativeEngine.Verdict
import dev.androidpoet.wren.browser.blocking.rules.RequestType
import org.junit.Test
import java.util.Base64

class NativeEngineTest {

  private fun NativeEngine.at(url: String, page: String = SITE, type: Int = RequestType.IMAGE) =
    check(url, page, type)

  private fun engine(
    vararg lines: String,
    resources: List<NativeEngine.Resource> = emptyList(),
  ): NativeEngine {
    val text = NativeEngine.ListText()
    lines.forEach(text::add)
    text.endList()
    return checkNotNull(text.build(resources)) { "native engine did not build" }
  }

  @Test
  fun `the native library loads on the machine running the tests`() {
    assertThat(NativeEngine.isAvailable).isTrue()
  }

  @Test
  fun `a plain host rule blocks first-party requests too, as uBlock Origin does`() {
    val engine = engine("||ads.example^")

    val firstParty = engine.at("https://ads.example/a.js", page = "https://ads.example/")
    val thirdParty = engine.at("https://ads.example/a.js")

    assertThat(firstParty).isEqualTo(Verdict.Block)
    assertThat(thirdParty).isEqualTo(Verdict.Block)
  }

  @Test
  fun `options and exceptions are honoured`() {
    val engine = engine(
      "||cdn.example^\$third-party,script",
      "@@||cdn.example/lib/\$script",
    )

    val script = RequestType.SCRIPT
    assertThat(engine.at("https://cdn.example/t.js", type = script)).isEqualTo(Verdict.Block)
    assertThat(engine.at("https://cdn.example/t.png")).isNull()
    assertThat(engine.at("https://cdn.example/t.js", "https://cdn.example/", script)).isNull()
    assertThat(engine.at("https://cdn.example/lib/a.js", type = script)).isNull()
  }

  @Test
  fun `a request of uncertain type is blocked when a rule for any of its candidates applies`() {
    val engine = engine("||api.tracker.example^\$xmlhttprequest")
    val anyStar = RequestType.SCRIPT or RequestType.XHR or RequestType.FONT or RequestType.OTHER

    val verdict = engine.at("https://api.tracker.example/v1", type = anyStar)

    assertThat(verdict).isEqualTo(Verdict.Block)
  }

  @Test
  fun `a redirect rule serves the named stand-in`() {
    val noop = NativeEngine.Resource(
      "noop.js",
      listOf("noopjs"),
      "application/javascript",
      Base64.getEncoder().encodeToString("(function(){})();".toByteArray()),
    )
    val engine = engine("||ads.example/loader.js\$script,redirect=noopjs", resources = listOf(noop))

    val verdict = engine.at("https://ads.example/loader.js", type = RequestType.SCRIPT)

    assertThat(verdict).isInstanceOf(Verdict.Redirect::class.java)
    assertThat((verdict as Verdict.Redirect).dataUrl)
      .startsWith("data:application/javascript;base64,")
  }

  @Test
  fun `hosts-file lines and bare domains become host rules`() {
    val engine = engine("0.0.0.0 tracker.example", "bare.example", "127.0.0.1 localhost")

    assertThat(engine.at("https://tracker.example/p")).isEqualTo(Verdict.Block)
    assertThat(engine.at("https://x.bare.example/p")).isEqualTo(Verdict.Block)
    assertThat(engine.at("http://localhost/p")).isNull()
  }

  @Test
  fun `only the chromium mobile branch of a list condition is read`() {
    val engine = engine(
      "!#if env_firefox",
      "||firefox-only.example^",
      "!#else",
      "||chromium.example^",
      "!#endif",
    )

    assertThat(engine.at("https://firefox-only.example/a")).isNull()
    assertThat(engine.at("https://chromium.example/a")).isEqualTo(Verdict.Block)
  }

  @Test
  fun `an engine survives serialization`() {
    val bytes = checkNotNull(engine("||ads.example^").serialize())

    val restored = checkNotNull(NativeEngine.restore(bytes, emptyList()))

    assertThat(restored.at("https://ads.example/a")).isEqualTo(Verdict.Block)
    assertThat(NativeEngine.restore(byteArrayOf(1, 2, 3), emptyList())).isNull()
  }

  @Test
  fun `a closed engine answers nothing and is freed once`() {
    val engine = engine("||ads.example^")

    engine.close()
    engine.close()

    assertThat(engine.at("https://ads.example/a")).isNull()
    assertThat(engine.serialize()).isNull()
  }

  @Test
  fun `closing while other threads check frees only after they finish`() {
    val engine = engine("||ads.example^")
    val threads = (1..8).map {
      Thread {
        repeat(20_000) {
          val verdict = engine.at("https://ads.example/$it")
          check(verdict == null || verdict == Verdict.Block)
        }
      }
    }

    threads.forEach(Thread::start)
    Thread.sleep(20)
    engine.close()
    threads.forEach(Thread::join)

    assertThat(engine.at("https://ads.example/a")).isNull()
  }

  private companion object {
    const val SITE = "https://site.example/"
  }
}
