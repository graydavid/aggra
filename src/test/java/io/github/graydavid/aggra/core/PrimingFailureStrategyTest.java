package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.observerAfterStop;
import static io.github.graydavid.aggra.core.PrimingFailureStrategy.FAIL_FAST_STOP;
import static io.github.graydavid.aggra.core.PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE;
import static io.github.graydavid.aggra.core.TestData.deviceWithEmptyFinalState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.nodes.FunctionNodes;

public class PrimingFailureStrategyTest {
    @Test
    public void shouldContinueOnPrimingFailuresReturnsTrueForWaitForAllContinue() {
        assertTrue(WAIT_FOR_ALL_CONTINUE.shouldContinueOnPrimingFailures());
    }

    @Test
    public void shouldContinueOnPrimingFailuresReturnsFalseForFailFastStop() {
        assertFalse(FAIL_FAST_STOP.shouldContinueOnPrimingFailures());
    }

    @Test
    public void requireCompatibleWithDependencyLifetimePassesForWaitForContinueAndAllDependencyLifetimes() {
        for (DependencyLifetime lifetime : DependencyLifetime.values()) {
            WAIT_FOR_ALL_CONTINUE.requireCompatibleWithDependencyLifetime(lifetime);
        }
    }

    @Test
    public void requireCompatibleWithDependencyLifetimeFailsForFailFastStopForAnyDependencyLifetimesThatWaitForDirectDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> FAIL_FAST_STOP.requireCompatibleWithDependencyLifetime(DependencyLifetime.NODE_FOR_ALL));
        assertThrows(IllegalArgumentException.class,
                () -> FAIL_FAST_STOP.requireCompatibleWithDependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT));
        FAIL_FAST_STOP.requireCompatibleWithDependencyLifetime(DependencyLifetime.GRAPH);
    }

    @Test
    public void modifyPrimingCompletionDoesNothingToAllOfFutureForWaitForAllContinue() {
        Node<TestMemory, Integer> dependency = FunctionNodes.synchronous(Role.of("dependency"), TestMemory.class)
                .getValue(5);
        Reply<Integer> dependencyReply = Reply.forCall(() -> Role.of("caller"), dependency);
        CompletableFuture<Void> allOf = new CompletableFuture<>();

        WAIT_FOR_ALL_CONTINUE.modifyPrimingCompletion(allOf, List.of(dependencyReply));
        dependencyReply.startComplete(null, new IllegalArgumentException(), deviceWithEmptyFinalState(),
                observerAfterStop());

        assertFalse(allOf.isDone());
    }

    @Test
    public void modifyPrimingCompletionDoesNothingToAllOfFutureForNoDependencyReplies() {
        CompletableFuture<Void> allOf = new CompletableFuture<>();

        FAIL_FAST_STOP.modifyPrimingCompletion(allOf, List.of());

        assertFalse(allOf.isDone());
    }

    @Test
    public void modifyPrimingCompletionDoesNothingToAllOfFutureForFailFastStopIfPrimingSucceeds() {
        Node<TestMemory, Integer> dependency1 = FunctionNodes.synchronous(Role.of("dependency-1"), TestMemory.class)
                .getValue(5);
        Node<TestMemory, Integer> dependency2 = FunctionNodes.synchronous(Role.of("dependency-1"), TestMemory.class)
                .getValue(5);
        Reply<Integer> dependencyReply1 = Reply.forCall(() -> Role.of("caller"), dependency1);
        Reply<Integer> dependencyReply2 = Reply.forCall(() -> Role.of("caller"), dependency2);
        CompletableFuture<Void> allOf = new CompletableFuture<>();

        FAIL_FAST_STOP.modifyPrimingCompletion(allOf, List.of(dependencyReply1, dependencyReply2));
        dependencyReply1.startComplete(5, null, deviceWithEmptyFinalState(), observerAfterStop());
        dependencyReply2.startComplete(5, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertFalse(allOf.isDone());
    }

    @Test
    public void modifyPrimingCompletionCompletesAllOfForFailFastStopIfPrimingFails() {
        Node<TestMemory, Integer> dependency1 = FunctionNodes.synchronous(Role.of("dependency-1"), TestMemory.class)
                .getValue(5);
        Node<TestMemory, Integer> dependency2 = FunctionNodes.synchronous(Role.of("dependency-1"), TestMemory.class)
                .getValue(5);
        Reply<Integer> dependencyReply1 = Reply.forCall(() -> Role.of("caller"), dependency1);
        Reply<Integer> dependencyReply2 = Reply.forCall(() -> Role.of("caller"), dependency2);
        CompletableFuture<Void> allOf = new CompletableFuture<>();

        FAIL_FAST_STOP.modifyPrimingCompletion(allOf, List.of(dependencyReply1, dependencyReply2));
        dependencyReply1.startComplete(5, null, deviceWithEmptyFinalState(), observerAfterStop());
        assertFalse(allOf.isDone());

        IllegalArgumentException dependencyReplyFailure = new IllegalArgumentException();
        dependencyReply2.startComplete(null, dependencyReplyFailure, deviceWithEmptyFinalState(), observerAfterStop());
        assertTrue(allOf.isDone());
        Throwable thrown = assertThrows(Throwable.class, () -> allOf.join());
        assertThat(Reply.getFirstNonContainerException(thrown).get(), is(dependencyReplyFailure));
    }
}
