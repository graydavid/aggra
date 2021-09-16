package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.observerAfterStop;
import static io.github.graydavid.aggra.core.TestData.deviceWithEmptyFinalState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the logic in {@link Reply} related to identifying and retrieving exceptions. You can find other Reply-related
 * tests under Reply_*Test classes.
 */
public class Reply_ExceptionTest {
    private final Caller firstCaller = () -> Role.of("first-caller");
    // Default node: doesn't add behavior onto the backing CF on completion
    private final Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("test"))
            .role(Role.of("called-node"))
            .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
            .dependencyLifetime(DependencyLifetime.GRAPH)
            .build(device -> CompletableFuture.completedFuture(40));

    @Test
    public void maybeProducedByReplyAnswersNoIfNotCompletionOrExecutionSurroundingCall() {
        assertFalse(Reply.maybeProducedByReply(new Throwable()));
        assertFalse(Reply.maybeProducedByReply(new Error()));
        assertFalse(Reply.maybeProducedByReply(new Exception()));
        assertFalse(Reply.maybeProducedByReply(new RuntimeException()));
        assertFalse(Reply.maybeProducedByReply(new CallException(firstCaller, node, new IllegalArgumentException())));
        assertFalse(Reply.maybeProducedByReply(new CompletionException(new Throwable())));
        assertFalse(Reply.maybeProducedByReply(new ExecutionException(new Throwable())));
    }

    @Test
    public void maybeProducedByReplyAnswersYesIfCompletionOrExecutionSurroundingCall() {
        assertTrue(Reply
                .maybeProducedByReply(new CompletionException(new CallException(firstCaller, node, new Throwable()))));
        assertTrue(Reply
                .maybeProducedByReply(new ExecutionException(new CallException(firstCaller, node, new Throwable()))));
    }

    @Test
    public void getExceptionNowReturnsEmptyOptionalForSuccessfulResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(30, null, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<CompletionException> exception = reply.getExceptionNow();

        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getExceptionNowReturnsEmptyOptionalForIncompleteResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);

        Optional<CompletionException> exception = reply.getExceptionNow();

        assertFalse(reply.isDone());
        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getExceptionNowReturnsInternalRepresentationForFailedResponse() {
        CompletionException internalException = internalException(new IllegalArgumentException());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<CompletionException> exception = reply.getExceptionNow();

        assertThat(exception, is(Optional.of(internalException)));
    }

    private CompletionException internalException(Throwable encountered) {
        CallException callException = new CallException(firstCaller, node, encountered);
        return new CompletionException(callException);
    }

    @Test
    public void getCallExceptionNowReturnsEmptyOptionalForSuccessfulResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(30, null, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<CallException> exception = reply.getCallExceptionNow();

        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getCallExceptionNowReturnsEmptyOptionalForIncompleteResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);

        Optional<CallException> exception = reply.getCallExceptionNow();

        assertFalse(reply.isDone());
        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getCallExceptionNowReturnsInternalRepresentationsCallExceptionForFailedResponse() {
        CallException callException = new CallException(firstCaller, node, new Throwable());
        CompletionException internalException = new CompletionException(callException);
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<CallException> exception = reply.getCallExceptionNow();

        assertThat(exception, is(Optional.of(callException)));
    }

    @Test
    public void getCallExceptionThrowsExceptionGivenExceptionNotProducedByReply() {
        assertThrows(IllegalArgumentException.class, () -> Reply.getCallException(new Throwable()));
    }

    @Test
    public void getEncounteredExceptionNowReturnsEmptyOptionalForSuccessfulResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(30, null, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getEncounteredExceptionNow();

        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getEncounteredExceptionNowReturnsEmptyOptionalForIncompleteResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);

        Optional<Throwable> exception = reply.getEncounteredExceptionNow();

        assertFalse(reply.isDone());
        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getEncounteredExceptionNowReturnsInternalRepresentationsCallExceptionForFailedNonContainerResponse() {
        Throwable encounteredException = new Throwable();
        CompletionException internalException = internalException(encounteredException);
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getEncounteredExceptionNow();

        assertThat(exception, is(Optional.of(encounteredException)));
    }

    @Test
    public void getEncounteredExceptionNowReturnsInternalRepresentationsCallExceptionForFailedContainerResponse() {
        CompletionException encounteredException = new CompletionException(new Throwable());
        CompletionException internalException = internalException(encounteredException);
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getEncounteredExceptionNow();

        assertThat(exception, is(Optional.of(encounteredException)));
    }

    @Test
    public void getEncounteredExceptionThrowsExceptionGivenExceptionNotProducedByReply() {
        assertThrows(IllegalArgumentException.class, () -> Reply.getEncounteredException(new Throwable()));
    }

    @Test
    public void getFirstNonContainerExceptionNowReturnsEmptyOptionalForSuccessfulResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(30, null, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getFirstNonContainerExceptionNow();

        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getFirstNonContainerExceptionNowReturnsEmptyOptionalForIncompleteResponse() {
        Reply<Integer> reply = Reply.forCall(firstCaller, node);

        Optional<Throwable> exception = reply.getFirstNonContainerExceptionNow();

        assertFalse(reply.isDone());
        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getFirstNonContainerExceptionNowReturnsEmptyOptionalForFailedResponseWithOnlyContainers() {
        // Have to subclass to get access to constructor without cause
        CompletionException completionNoCause = new CompletionException("No cause") {
            private static final long serialVersionUID = 1L;
        };
        CompletionException internalException = internalException(
                new ExecutionException(new CallException(firstCaller, node, completionNoCause)));
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getFirstNonContainerExceptionNow();

        assertThat(exception, is(Optional.empty()));
    }

    @Test
    public void getFirstNonContainerExceptionNowReturnsInternalRepresentationsFirstNonContainerExceptionForFailedNonContainerResponse() {
        Throwable encounteredException = new Throwable();
        CompletionException internalException = internalException(encounteredException);
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getFirstNonContainerExceptionNow();

        assertThat(exception, is(Optional.of(encounteredException)));
    }

    @Test
    public void getFirstNonContainerExceptionNowReturnsInternalRepresentationsFirstNonContainerExceptionForFailedContainerResponse() {
        Throwable firstNonContainer = new Throwable();
        CompletionException internalException = internalException(
                new ExecutionException(new CallException(firstCaller, node, firstNonContainer)));
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Optional<Throwable> exception = reply.getFirstNonContainerExceptionNow();

        assertThat(exception, is(Optional.of(firstNonContainer)));
    }

    @Test
    public void getFirstNonContainerExceptionThrowsExceptionGivenExceptionNotProducedByReply() {
        assertThrows(IllegalArgumentException.class, () -> Reply.getFirstNonContainerException(new Throwable()));
    }

    @Test
    public void joinThrowsInternalRepresentationExceptionAsIs() {
        CompletionException internalException = internalException(new Throwable());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        Throwable thrown = assertThrows(Throwable.class, reply::join);

        assertThat(thrown, is(internalException));
    }

    @Test
    public void getNowThrowsInternalRepresentationExceptionAsIs() {
        CompletionException internalException = internalException(new Throwable());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        CompletionException thrown = assertThrows(CompletionException.class, () -> reply.getNow(3));

        assertThat(thrown, is(internalException));
    }

    @Test
    public void getThrowsInternalRepresentationExceptionCauseInExecutionException() {
        CallException callException = new CallException(firstCaller, node, new Throwable());
        CompletionException internalException = new CompletionException(callException);
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        ExecutionException thrown = assertThrows(ExecutionException.class, reply::get);

        assertThat(thrown.getCause(), is(callException));
    }

    @Test
    public void whenCompletePassesInternalRepresentationExceptionAsIs() {
        CompletionException internalException = internalException(new Throwable());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        AtomicReference<Throwable> passed = new AtomicReference<>();
        reply.whenComplete((result, throwable) -> {
            passed.set(throwable);
        });

        assertThat(passed.get(), is(internalException));
    }

    @Test
    public void handlePassesInternalRepresentationExceptionAsIs() {
        Integer valueIfMatchedInternalException = 55;
        CompletionException internalException = internalException(new Throwable());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        CompletionStage<Integer> response = reply.handle((result, throwable) -> {
            return throwable == internalException ? valueIfMatchedInternalException : 0;
        });

        assertThat(response.toCompletableFuture().join(), is(valueIfMatchedInternalException));
    }

    @Test
    public void exceptionallyPassesInternalRepresentationExceptionAsIs() {
        Integer valueIfMatchedInternalException = 55;
        CompletionException internalException = internalException(new Throwable());
        Reply<Integer> reply = Reply.forCall(firstCaller, node);
        reply.startComplete(null, internalException, deviceWithEmptyFinalState(), observerAfterStop());

        CompletionStage<Integer> response = reply.exceptionally(throwable -> {
            return throwable == internalException ? valueIfMatchedInternalException : 0;
        });

        assertThat(response.toCompletableFuture().join(), is(valueIfMatchedInternalException));
    }
}
