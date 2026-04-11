package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SidebarPinStateTest {

    @Test
    fun `AUTO state expanded follows isHovered`() {
        assertTrue(SidebarPinState.AUTO.isExpanded(isHovered = true))
        assertFalse(SidebarPinState.AUTO.isExpanded(isHovered = false))
    }

    @Test
    fun `PINNED_EXPANDED is always expanded`() {
        assertTrue(SidebarPinState.PINNED_EXPANDED.isExpanded(isHovered = true))
        assertTrue(SidebarPinState.PINNED_EXPANDED.isExpanded(isHovered = false))
    }

    @Test
    fun `PINNED_COLLAPSED is never expanded`() {
        assertFalse(SidebarPinState.PINNED_COLLAPSED.isExpanded(isHovered = true))
        assertFalse(SidebarPinState.PINNED_COLLAPSED.isExpanded(isHovered = false))
    }

    @Test
    fun `cycleOnClick AUTO goes to PINNED_EXPANDED`() {
        assertEquals(SidebarPinState.PINNED_EXPANDED, SidebarPinState.AUTO.cycleOnClick())
    }

    @Test
    fun `cycleOnClick PINNED_EXPANDED goes back to AUTO`() {
        assertEquals(SidebarPinState.AUTO, SidebarPinState.PINNED_EXPANDED.cycleOnClick())
    }

    @Test
    fun `cycleOnClick PINNED_COLLAPSED goes to AUTO`() {
        assertEquals(SidebarPinState.AUTO, SidebarPinState.PINNED_COLLAPSED.cycleOnClick())
    }

    @Test
    fun `toggleOnDoubleClick AUTO goes to PINNED_COLLAPSED`() {
        assertEquals(SidebarPinState.PINNED_COLLAPSED, SidebarPinState.AUTO.toggleOnDoubleClick())
    }

    @Test
    fun `toggleOnDoubleClick PINNED_COLLAPSED goes back to AUTO`() {
        assertEquals(SidebarPinState.AUTO, SidebarPinState.PINNED_COLLAPSED.toggleOnDoubleClick())
    }

    @Test
    fun `toggleOnDoubleClick PINNED_EXPANDED goes to PINNED_COLLAPSED`() {
        assertEquals(SidebarPinState.PINNED_COLLAPSED, SidebarPinState.PINNED_EXPANDED.toggleOnDoubleClick())
    }
}
