/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.ui.tv.TvMediaKeys
import org.junit.Test

class TvMediaKeysTest {

  @Test
  fun `play pause toggles the media element`() {
    val script = TvMediaKeys.scriptFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    assertThat(script).contains("('toggle', 10);")
  }

  @Test
  fun `stop pauses, because a page has no stop`() {
    val script = TvMediaKeys.scriptFor(KeyEvent.KEYCODE_MEDIA_STOP)

    assertThat(script).contains("('pause', 10);")
  }

  @Test
  fun `seek keys move by the step`() {
    assertThat(
      TvMediaKeys.scriptFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
    ).contains("('forward', 10);")
    assertThat(TvMediaKeys.scriptFor(KeyEvent.KEYCODE_MEDIA_REWIND)).contains("('rewind', 10);")
  }

  @Test
  fun `an unplayed video is started by a press on its middle`() {
    val script = TvMediaKeys.scriptFor(KeyEvent.KEYCODE_MEDIA_PLAY)

    assertThat(script).contains("media.played.length === 0 ? middle() : ''")
    assertThat(TvMediaKeys.tapPoint("\"tap:480.5,270\"")).isEqualTo(480.5f to 270f)
  }

  @Test
  fun `an answer that is not a point presses nothing`() {
    assertThat(TvMediaKeys.tapPoint("\"\"")).isNull()
    assertThat(TvMediaKeys.tapPoint("null")).isNull()
    assertThat(TvMediaKeys.tapPoint(null)).isNull()
    assertThat(TvMediaKeys.tapPoint("\"tap:12\"")).isNull()
    assertThat(TvMediaKeys.tapPoint("\"12,40\"")).isNull()
  }

  @Test
  fun `keys that are not transport keys are left alone`() {
    assertThat(TvMediaKeys.scriptFor(KeyEvent.KEYCODE_DPAD_CENTER)).isNull()
    assertThat(TvMediaKeys.scriptFor(KeyEvent.KEYCODE_VOLUME_UP)).isNull()
  }
}
