package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.CancelMode.COMPOSITE_SIGNAL;
import static io.github.graydavid.aggra.core.CancelMode.CUSTOM_ACTION;
import static io.github.graydavid.aggra.core.CancelMode.DEFAULT;
import static io.github.graydavid.aggra.core.CancelMode.INTERRUPT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CancelModeTest {

    @Test
    public void setsCorrectPropertiesForDefault() {
        assertFalse(DEFAULT.supportsReplySignalPassiveHook());
        assertFalse(DEFAULT.supportsCompositeSignal());
        assertFalse(DEFAULT.supportsActiveHooks());
        assertFalse(DEFAULT.supportsCustomAction());
        assertFalse(DEFAULT.supportsCustomActionInterrupt());
    }

    @Test
    public void setsCorrectPropertiesForCompositeSignal() {
        assertTrue(COMPOSITE_SIGNAL.supportsReplySignalPassiveHook());
        assertTrue(COMPOSITE_SIGNAL.supportsCompositeSignal());
        assertFalse(COMPOSITE_SIGNAL.supportsActiveHooks());
        assertFalse(COMPOSITE_SIGNAL.supportsCustomAction());
        assertFalse(COMPOSITE_SIGNAL.supportsCustomActionInterrupt());
    }

    @Test
    public void setsCorrectPropertiesForCustomAction() {
        assertTrue(CUSTOM_ACTION.supportsReplySignalPassiveHook());
        assertTrue(CUSTOM_ACTION.supportsCompositeSignal());
        assertTrue(CUSTOM_ACTION.supportsActiveHooks());
        assertTrue(CUSTOM_ACTION.supportsCustomAction());
        assertFalse(CUSTOM_ACTION.supportsCustomActionInterrupt());
    }

    @Test
    public void setsCorrectPropertiesForInterrupt() {
        assertTrue(INTERRUPT.supportsReplySignalPassiveHook());
        assertTrue(INTERRUPT.supportsCompositeSignal());
        assertTrue(INTERRUPT.supportsActiveHooks());
        assertTrue(INTERRUPT.supportsCustomAction());
        assertTrue(INTERRUPT.supportsCustomActionInterrupt());
    }
}
