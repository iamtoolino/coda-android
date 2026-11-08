package dev.coda.android.ui.shell

import org.junit.Assert.*
import org.junit.Test

class DockScrollPolicyTest {
    @Test fun thresholdsReverseAndAccessibilityGuard() {
        val state = DockScrollPolicy()
        state.scroll(-23f, true); assertFalse(state.collapsed)
        state.scroll(-1f, true); assertTrue(state.collapsed)
        state.scroll(7f, true); assertTrue(state.collapsed)
        state.scroll(1f, true); assertFalse(state.collapsed)
        state.scroll(-20f, true); state.scroll(1f, true); state.scroll(-5f, true)
        assertFalse(state.collapsed)
        state.scroll(-24f, true); assertTrue(state.collapsed)
        state.scroll(-100f, false); assertFalse(state.collapsed)
    }
}
