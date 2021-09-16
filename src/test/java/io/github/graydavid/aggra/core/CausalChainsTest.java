package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

public class CausalChainsTest {

    @Test
    public void findFirstThrowsExceptionGivenNullMatchingThrowable() {
        assertThrows(NullPointerException.class, () -> CausalChains.findFirst(null, throwable -> true));
    }

    @Test
    public void findFirstThrowsExceptionGivenNullNonMatchingThrowable() {
        assertThrows(NullPointerException.class, () -> CausalChains.findFirst(null, throwable -> false));
    }

    @Test
    public void findFirstDetectsMatchInBaseException() {
        Throwable base = new Throwable("message");

        Optional<Throwable> containsMessage = CausalChains.findFirst(base, t -> "message".equals(t.getMessage()));
        Optional<Throwable> containsNonMessage = CausalChains.findFirst(base,
                t -> "non-message".equals(t.getMessage()));

        assertThat(containsMessage, is(Optional.of(base)));
        assertThat(containsNonMessage, is(Optional.empty()));
    }

    @Test
    public void findFirstDetectsMatchInCauseException() {
        Throwable cause = new Throwable("message");
        Throwable base = new Throwable(cause);

        Optional<Throwable> containsMessage = CausalChains.findFirst(base, t -> "message".equals(t.getMessage()));

        assertThat(containsMessage, is(Optional.of(cause)));
    }

    @Test
    public void findFirstDetectsMisMatchesGivenNullCause() {
        Throwable hasNullCause = new Throwable("message", null);

        Optional<Throwable> noMatch = CausalChains.findFirst(hasNullCause, t -> false);

        assertThat(noMatch, is(Optional.empty()));
    }

    @Test
    public void findFirstCanDetectLackOfMatchInCircularExceptionChain() {
        Throwable cause = new Throwable();
        Throwable base = new Throwable(cause);
        cause.initCause(base);

        Optional<Throwable> containsMessage = CausalChains.findFirst(base, t -> "message".equals(t.getMessage()));

        assertThat(containsMessage, is(Optional.empty()));
    }

    @Test
    public void findFirstDetectsMatchInCauseExceptionDisguisedToLookLikeBase() {
        Throwable base = new Throwable();
        Throwable cause = new Throwable("message") {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean equals(Object object) {
                return base.equals(object);
            }

            @Override
            public int hashCode() {
                return base.hashCode();
            }
        };
        base.initCause(cause);

        Optional<Throwable> containsMessage = CausalChains.findFirst(base, t -> "message".equals(t.getMessage()));

        assertThat(containsMessage, is(Optional.of(cause)));
        assertThat(containsMessage.get(), sameInstance(cause));
    }
}
