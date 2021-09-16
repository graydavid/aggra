package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionException;
import io.github.graydavid.aggra.core.Behaviors.InterruptClearingException;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class BehaviorsTest {
    private final Reply<Integer> reply = Reply.forCall(() -> Role.of("caller"), TestData.nodeReturningValue(0));
    private final TestMemory memory = new TestMemory(CompletableFuture.completedFuture(12));

    @Test
    public void customCancelActionBehaviorResponseConstructorsThrowExceptionOnNullParameters() {
        assertThrows(NullPointerException.class, () -> new CustomCancelActionBehaviorResponse<>(null, bool -> {
        }));
        assertThrows(NullPointerException.class,
                () -> new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(12), null));
    }

    @Test
    public void customCancelActionExceptionConstructorThrowsExceptionsForNullArguments() {
        Throwable cause = new Throwable();

        assertThrows(NullPointerException.class, () -> new CustomCancelActionException(cause, null, memory));
        assertThrows(NullPointerException.class, () -> new CustomCancelActionException(cause, reply, null));
    }

    @Test
    public void customCancelActionExceptionAccessorsReturnParametersPassedToConstructor() {
        Throwable cause = new Throwable();

        CustomCancelActionException exception = new CustomCancelActionException(cause, reply, memory);

        assertThat(exception.getCause(), is(cause));
        assertThat(exception.getReply(), is(reply));
        assertThat(exception.getMemory(), is(memory));
    }

    @Test
    public void interruptClearingExceptionConstructorThrowsExceptionsForNullArguments() {
        Throwable cause = new Throwable();

        assertThrows(NullPointerException.class, () -> new InterruptClearingException(cause, null, memory));
        assertThrows(NullPointerException.class, () -> new InterruptClearingException(cause, reply, null));
    }

    @Test
    public void interruptClearingExceptionAccessorsReturnParametersPassedToConstructor() {
        Throwable cause = new Throwable();

        InterruptClearingException exception = new InterruptClearingException(cause, reply, memory);

        assertThat(exception.getCause(), is(cause));
        assertThat(exception.getReply(), is(reply));
        assertThat(exception.getMemory(), is(memory));
    }
}
