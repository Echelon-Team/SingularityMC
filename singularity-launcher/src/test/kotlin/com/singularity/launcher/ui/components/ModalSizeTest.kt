package com.singularity.launcher.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModalSizeTest {

    @Test
    fun `ModalSize has 4 values matching prototype`() {
        val values = ModalSize.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(ModalSize.SMALL))
        assertTrue(values.contains(ModalSize.MEDIUM))
        assertTrue(values.contains(ModalSize.LARGE))
        assertTrue(values.contains(ModalSize.XLARGE))
    }

    @Test
    fun `SMALL is 420dp`() { assertEquals(420.dp, ModalSize.SMALL.width) }

    @Test
    fun `MEDIUM is 550dp`() { assertEquals(550.dp, ModalSize.MEDIUM.width) }

    @Test
    fun `LARGE is 720dp`() { assertEquals(720.dp, ModalSize.LARGE.width) }

    @Test
    fun `XLARGE is 900dp`() { assertEquals(900.dp, ModalSize.XLARGE.width) }

    @Test
    fun `sizes are monotonically increasing`() {
        assertTrue(ModalSize.SMALL.width < ModalSize.MEDIUM.width)
        assertTrue(ModalSize.MEDIUM.width < ModalSize.LARGE.width)
        assertTrue(ModalSize.LARGE.width < ModalSize.XLARGE.width)
    }
}
