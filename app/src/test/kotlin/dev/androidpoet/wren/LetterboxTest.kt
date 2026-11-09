/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.view.ViewGroup
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.privacy.Letterbox
import org.junit.Test

class LetterboxTest {

  @Test
  fun `a page is rounded down to whole 100 px steps`() {
    // A 412 x 783 CSS px viewport at density 2.625 (a Pixel-class phone).
    val step = Letterbox.STEP_CSS_PX * 2.625f

    assertThat(Letterbox.letterboxedSize((412 * 2.625f).toInt(), step)).isEqualTo(1050)
    // 700 CSS px is 1837.5 physical; 1838 reads back as 701 on the device, 1837 as 700.
    assertThat(Letterbox.letterboxedSize((783 * 2.625f).toInt(), step)).isEqualTo(1837)
  }

  @Test
  fun `an exact multiple is kept whole`() {
    assertThat(Letterbox.letterboxedSize(800, 100f)).isEqualTo(800)
  }

  @Test
  fun `a container without a size or smaller than one step fills its parent`() {
    assertThat(Letterbox.letterboxedSize(0, 100f)).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
    assertThat(Letterbox.letterboxedSize(99, 100f)).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
    assertThat(Letterbox.letterboxedSize(500, 0f)).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
  }
}
