package app.tether.client

import app.tether.client.ui.BubbleColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Issue #34: a number in a sent text was drawn in the accent colour on the accent bubble, so the bubble looked empty. */
class BubbleColorsTest {
    @Test fun linksStayVisibleOnOurBubbles() {
        assertNotEquals(R.color.accent, BubbleColors.link(mine = true))
        assertEquals(BubbleColors.text(mine = true), BubbleColors.link(mine = true))
        assertEquals(R.color.accent, BubbleColors.link(mine = false))
    }
}
