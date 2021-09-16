package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class MisbehaviorExceptionTest {

    @Test
    public void messageConstructorSetsMessage() {
        MisbehaviorException exception = new MisbehaviorException("message");

        assertThat(exception.getMessage(), is("message"));
        assertNull(exception.getCause());
    }
}
