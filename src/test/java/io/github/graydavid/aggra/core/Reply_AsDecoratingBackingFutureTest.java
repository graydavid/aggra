package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the logic in {@link Reply} related to it being a decorator for a backing CompletableFuture. This is the only
 * class in the Aggra framework that should use the {@link Reply#ofBackingForCall(CompletableFuture, Caller, Node)}
 * factory method directly. You can find other Reply-related tests under Reply_*Test classes.
 */
public class Reply_AsDecoratingBackingFutureTest {
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<Integer> backing = mock(CompletableFuture.class);
    private final Caller firstCaller = () -> Role.of("first-caller");
    // Default node: doesn't add behavior onto the backing CF on completion
    private final Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("test"))
            .role(Role.of("called-node"))
            .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
            .dependencyLifetime(DependencyLifetime.GRAPH)
            .build(device -> CompletableFuture.completedFuture(40));
    private final Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final Function<Integer, String> function = mock(Function.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final BiFunction<Integer, Double, String> biFunction = mock(BiFunction.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final Consumer<Integer> consumer = mock(Consumer.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final BiConsumer<Integer, Double> doubleBiConsumer = mock(BiConsumer.class);
    private final Runnable runnable = mock(Runnable.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<Double> doubleOther = mock(CompletableFuture.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<Integer> intOther = mock(CompletableFuture.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final Function<Integer, CompletableFuture<String>> composeFunction = mock(Function.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final BiFunction<Integer, Throwable, String> handleBiFunction = mock(BiFunction.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final BiConsumer<Integer, Throwable> completeBiConsumer = mock(BiConsumer.class);
    private final Executor executor = mock(Executor.class);

    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<Integer> intBackingResult = mock(CompletableFuture.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<String> stringBackingResult = mock(CompletableFuture.class);
    // Suppress justification: mock only ever used in compatible way for declared type
    @SuppressWarnings("unchecked")
    private final CompletableFuture<Void> voidBackingResult = mock(CompletableFuture.class);


    @Test
    public void thenApplyInvokesBackingFuture() {
        when(backing.thenApply(function)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenApply(function);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenApplyAsyncInvokesBackingFuture() {
        when(backing.thenApplyAsync(function)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenApplyAsync(function);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenApplyAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenApplyAsync(function, executor)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenApplyAsync(function, executor);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenAcceptInvokesBackingFuture() {
        when(backing.thenAccept(consumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAccept(consumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenAcceptAsyncInvokesBackingFuture() {
        when(backing.thenAcceptAsync(consumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAcceptAsync(consumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenAcceptAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenAcceptAsync(consumer, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAcceptAsync(consumer, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenRunInvokesBackingFuture() {
        when(backing.thenRun(runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenRun(runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenRunAsyncInvokesBackingFuture() {
        when(backing.thenRunAsync(runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenRunAsync(runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenRunAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenRunAsync(runnable, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenRunAsync(runnable, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenCombineInvokesBackingFuture() {
        when(backing.thenCombine(doubleOther, biFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenCombine(doubleOther, biFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenCombineAsyncInvokesBackingFuture() {
        when(backing.thenCombineAsync(doubleOther, biFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenCombineAsync(doubleOther, biFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenCombineAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenCombineAsync(doubleOther, biFunction, executor)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenCombineAsync(doubleOther, biFunction, executor);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenAcceptBothInvokesBackingFuture() {
        when(backing.thenAcceptBoth(doubleOther, doubleBiConsumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAcceptBoth(doubleOther, doubleBiConsumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenAcceptBothAsyncInvokesBackingFuture() {
        when(backing.thenAcceptBothAsync(doubleOther, doubleBiConsumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAcceptBothAsync(doubleOther, doubleBiConsumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenAcceptBothAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenAcceptBothAsync(doubleOther, doubleBiConsumer, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.thenAcceptBothAsync(doubleOther, doubleBiConsumer, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterBothInvokesBackingFuture() {
        when(backing.runAfterBoth(doubleOther, runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterBoth(doubleOther, runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterBothAsyncInvokesBackingFuture() {
        when(backing.runAfterBothAsync(doubleOther, runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterBothAsync(doubleOther, runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterBothAsyncWithExecutorInvokesBackingFuture() {
        when(backing.runAfterBothAsync(doubleOther, runnable, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterBothAsync(doubleOther, runnable, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void applyToEitherInvokesBackingFuture() {
        when(backing.applyToEither(intOther, function)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.applyToEither(intOther, function);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void applyToEitherAsyncInvokesBackingFuture() {
        when(backing.applyToEitherAsync(intOther, function)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.applyToEitherAsync(intOther, function);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void applyToEitherAsyncWithExecutorInvokesBackingFuture() {
        when(backing.applyToEitherAsync(intOther, function, executor)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.applyToEitherAsync(intOther, function, executor);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void acceptEitherInvokesBackingFuture() {
        when(backing.acceptEither(intOther, consumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.acceptEither(intOther, consumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void acceptEitherAsyncInvokesBackingFuture() {
        when(backing.acceptEitherAsync(intOther, consumer)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.acceptEitherAsync(intOther, consumer);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void acceptEitherAsyncWithExecutorInvokesBackingFuture() {
        when(backing.acceptEitherAsync(intOther, consumer, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.acceptEitherAsync(intOther, consumer, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterEitherInvokesBackingFuture() {
        when(backing.runAfterEither(intOther, runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterEither(intOther, runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterEitherAsyncInvokesBackingFuture() {
        when(backing.runAfterEitherAsync(intOther, runnable)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterEitherAsync(intOther, runnable);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void runAfterEitherAsyncWithExecutorInvokesBackingFuture() {
        when(backing.runAfterEitherAsync(intOther, runnable, executor)).thenReturn(voidBackingResult);

        CompletionStage<Void> actualResult = reply.runAfterEitherAsync(intOther, runnable, executor);

        assertThat(actualResult, is(voidBackingResult));
    }

    @Test
    public void thenComposeInvokesBackingFuture() {
        when(backing.thenCompose(composeFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenCompose(composeFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenComposeAsyncInvokesBackingFuture() {
        when(backing.thenComposeAsync(composeFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenComposeAsync(composeFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void thenComposeAsyncWithExecutorInvokesBackingFuture() {
        when(backing.thenComposeAsync(composeFunction, executor)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.thenComposeAsync(composeFunction, executor);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void handleInvokesBackingFuture() {
        when(backing.handle(handleBiFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.handle(handleBiFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void handleAsyncInvokesBackingFuture() {
        when(backing.handleAsync(handleBiFunction)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.handleAsync(handleBiFunction);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void handleAsyncWithExecutorInvokesBackingFuture() {
        when(backing.handleAsync(handleBiFunction, executor)).thenReturn(stringBackingResult);

        CompletionStage<String> actualResult = reply.handleAsync(handleBiFunction, executor);

        assertThat(actualResult, is(stringBackingResult));
    }

    @Test
    public void whenCompleteInvokesBackingFuture() {
        when(backing.whenComplete(completeBiConsumer)).thenReturn(intBackingResult);

        CompletionStage<Integer> actualResult = reply.whenComplete(completeBiConsumer);

        assertThat(actualResult, is(intBackingResult));
    }

    @Test
    public void whenCompleteAsyncInvokesBackingFuture() {
        when(backing.whenCompleteAsync(completeBiConsumer)).thenReturn(intBackingResult);

        CompletionStage<Integer> actualResult = reply.whenCompleteAsync(completeBiConsumer);

        assertThat(actualResult, is(intBackingResult));
    }

    @Test
    public void whenCompleteAsyncWithExecutorInvokesBackingFuture() {
        when(backing.whenCompleteAsync(completeBiConsumer, executor)).thenReturn(intBackingResult);

        CompletionStage<Integer> actualResult = reply.whenCompleteAsync(completeBiConsumer, executor);

        assertThat(actualResult, is(intBackingResult));
    }

    @Test
    public void exceptionallyInvokesBackingFuture() {
        // Suppress justification: mock only ever used in compatible way for declared type
        @SuppressWarnings("unchecked")
        Function<Throwable, Integer> exceptionFunction = mock(Function.class);
        when(backing.exceptionally(exceptionFunction)).thenReturn(intBackingResult);

        CompletionStage<Integer> actualResult = reply.exceptionally(exceptionFunction);

        assertThat(actualResult, is(intBackingResult));
    }

    @Test
    public void toCompletableFutureReturnsCopyOfBackingFuture() {
        // Suppress justification: mock only ever used in compatible way for declared type
        @SuppressWarnings("unchecked")
        CompletableFuture<Integer> copy = mock(CompletableFuture.class);
        when(backing.copy()).thenReturn(copy);

        CompletionStage<Integer> actualResult = reply.toCompletableFuture();

        assertThat(actualResult, is(copy));
    }

    @Test
    public void isDoneInvokesBackingFuture() {
        CompletableFuture<Integer> notDoneBacking = new CompletableFuture<>();
        CompletableFuture<Integer> doneBacking = CompletableFuture.completedFuture(5);

        Reply<Integer> notDone = Reply.ofBackingForCall(notDoneBacking, firstCaller, node);
        Reply<Integer> done = Reply.ofBackingForCall(doneBacking, firstCaller, node);

        assertFalse(notDone.isDone());
        assertTrue(done.isDone());
    }

    @Test
    public void getInvokesBackingFuture() throws InterruptedException, ExecutionException {
        when(backing.get()).thenReturn(42);

        Integer actualResult = reply.get();

        assertThat(actualResult, is(42));
    }

    @Test
    public void getWithTimeoutInvokesBackingFuture() throws InterruptedException, ExecutionException, TimeoutException {
        when(backing.get(23, TimeUnit.HOURS)).thenReturn(42);

        Integer actualResult = reply.get(23, TimeUnit.HOURS);

        assertThat(actualResult, is(42));
    }

    @Test
    public void getNowInvokesBackingFuture() {
        when(backing.getNow(45)).thenReturn(42);

        Integer actualResult = reply.getNow(45);

        assertThat(actualResult, is(42));
    }

    @Test
    public void getNumberOfDependents() {
        when(backing.getNumberOfDependents()).thenReturn(98);

        Integer actualResult = reply.getNumberOfDependents();

        assertThat(actualResult, is(98));
    }

    @Test
    public void isCompletedExceptionallyInvokesBackingFuture() {
        CompletableFuture<Integer> exceptionalBacking = CompletableFuture.failedFuture(new IllegalArgumentException());
        CompletableFuture<Integer> successfulBacking = CompletableFuture.completedFuture(5);

        Reply<Integer> exceptional = Reply.ofBackingForCall(exceptionalBacking, firstCaller, node);
        Reply<Integer> successful = Reply.ofBackingForCall(successfulBacking, firstCaller, node);

        assertTrue(exceptional.isCompletedExceptionally());
        assertFalse(successful.isCompletedExceptionally());
    }

    @Test
    public void joinInvokesBackingFuture() {
        when(backing.join()).thenReturn(42);

        Integer actualResult = reply.join();

        assertThat(actualResult, is(42));
    }

    @Test
    public void allOfBackingReturnsCompletedFutureForEmptyReplyArray() {
        CompletableFuture<Void> result = Reply.allOfBacking();

        assertTrue(result.isDone());
    }

    @Test
    public void allOfBackingReturnsCompletedFutureMimicingCompletionStatusOfSingleReply() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        CompletableFuture<Void> allResult = Reply.allOfBacking(reply);
        assertFalse(allResult.isDone());

        backing.complete(32);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfBackingReturnsCompletedFutureMimicingCompletionStatusOfSingleCompletableFuture() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();

        CompletableFuture<Void> allResult = Reply.allOfBacking(backing);
        assertFalse(allResult.isDone());

        backing.complete(32);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfBackingReturnsCompletedFutureThatOnlyCompletesWhenAllReplysComplete() {
        CompletableFuture<Integer> backing1 = new CompletableFuture<>();
        Reply<Integer> reply1 = Reply.ofBackingForCall(backing1, firstCaller, node);
        CompletableFuture<Integer> backing2 = new CompletableFuture<>();

        CompletableFuture<Void> allResult = Reply.allOfBacking(reply1, backing2);
        assertFalse(allResult.isDone());

        backing1.complete(32);
        assertFalse(allResult.isDone());

        backing2.complete(55);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfBackingWithCollectionReturnsCompletedFutureForEmptyReplyArray() {
        CompletableFuture<Void> result = Reply.allOfBacking(List.of());

        assertTrue(result.isDone());
    }

    @Test
    public void allOfBackingWithCollectionReturnsCompletedFutureThatOnlyCompletesWhenAllReplysComplete() {
        CompletableFuture<Integer> backing1 = new CompletableFuture<>();
        Reply<Integer> reply1 = Reply.ofBackingForCall(backing1, firstCaller, node);
        CompletableFuture<Integer> backing2 = new CompletableFuture<>();

        CompletableFuture<Void> allResult = Reply.allOfBacking(List.of(reply1, backing2));
        assertFalse(allResult.isDone());

        backing1.complete(32);
        assertFalse(allResult.isDone());

        backing2.complete(55);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfProbablyCompleteBackingReturnsCompletedStageForEmptyReplyArray() {
        CompletableFuture<Void> result = Reply.allOfProbablyCompleteBacking(List.of()).toCompletableFuture();

        assertTrue(result.isDone());
    }

    @Test
    public void allOfProbablyCompleteBackingReturnsCompletedFutureMimicingCompletionStatusOfSingleReply() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        CompletableFuture<Void> allResult = Reply.allOfProbablyCompleteBacking(List.of(reply)).toCompletableFuture();
        assertFalse(allResult.isDone());

        backing.complete(32);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfProbablyCompleteBackingReturnsCompletedFutureThatOnlyCompletesWhenAllReplysComplete() {
        CompletableFuture<Integer> backing1 = new CompletableFuture<>();
        Reply<Integer> reply1 = Reply.ofBackingForCall(backing1, firstCaller, node);
        CompletableFuture<Integer> backing2 = new CompletableFuture<>();
        Reply<Integer> reply2 = Reply.ofBackingForCall(backing2, firstCaller, node);

        CompletableFuture<Void> allResult = Reply.allOfProbablyCompleteBacking(List.of(reply1, reply2))
                .toCompletableFuture();
        assertFalse(allResult.isDone());

        backing1.complete(32);
        assertFalse(allResult.isDone());

        backing2.complete(55);
        assertTrue(allResult.isDone());
    }

    @Test
    public void allOfProbablyCompleteBackingDoesntGetConfusedByAlreadyCompleteReplies() {
        CompletableFuture<Integer> completeBacking = CompletableFuture.completedFuture(34);
        Reply<Integer> completeReply = Reply.ofBackingForCall(completeBacking, firstCaller, node);
        CompletableFuture<Integer> incompleteBacking = new CompletableFuture<>();
        Reply<Integer> incompleteReply = Reply.ofBackingForCall(incompleteBacking, firstCaller, node);

        CompletableFuture<Void> allResult = Reply.allOfProbablyCompleteBacking(List.of(completeReply, incompleteReply))
                .toCompletableFuture();

        assertFalse(allResult.isDone());

        incompleteBacking.complete(55);
        assertTrue(allResult.isDone());
    }

    @Test
    public void anyOfBackingReturnsIncompletedFutureForEmptyReplyArray() {
        CompletableFuture<Object> result = Reply.anyOfBacking();

        assertFalse(result.isDone());
    }

    @Test
    public void anyOfBackingReturnsCompletedFutureMimicingSingleReply() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        CompletableFuture<Object> anyResult = Reply.anyOfBacking(reply);
        assertFalse(anyResult.isDone());

        backing.complete(32);
        assertTrue(anyResult.isDone());
        assertThat(anyResult.join(), is(32));
    }

    @Test
    public void anyOfBackingReturnsCompletedFutureMimicingSingleCompletableFuture() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();

        CompletableFuture<Object> anyResult = Reply.anyOfBacking(backing);
        assertFalse(anyResult.isDone());

        backing.complete(32);
        assertTrue(anyResult.isDone());
        assertThat(anyResult.join(), is(32));
    }

    @Test
    public void anyOfBackingReturnsCompletedFutureThatMimicsFirstCompletedReply() {
        CompletableFuture<Integer> backing1 = new CompletableFuture<>();
        Reply<Integer> reply1 = Reply.ofBackingForCall(backing1, firstCaller, node);
        CompletableFuture<Integer> backing2 = new CompletableFuture<>();

        CompletableFuture<Object> anyResult = Reply.anyOfBacking(reply1, backing2);
        assertFalse(anyResult.isDone());

        backing1.complete(32);
        assertTrue(anyResult.isDone());
        assertThat(anyResult.join(), is(32));

        backing2.complete(55);
        assertTrue(anyResult.isDone());
        assertThat(anyResult.join(), is(32));
    }

    @Test
    public void toStringIncludesSuccessfulNonNullBacking() {
        CompletableFuture<Integer> backing = CompletableFuture.completedFuture(534852);
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        String replyResult = reply.toString();

        assertThat(replyResult, containsString(backing.toString()));
        assertThat(replyResult, containsString("534852"));
    }

    @Test
    public void toStringIncludesSuccessfulNullBacking() {
        CompletableFuture<Integer> backing = CompletableFuture.completedFuture(null);
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        String replyResult = reply.toString();

        assertThat(replyResult, containsString(backing.toString()));
        assertThat(replyResult, containsString("null"));
    }

    @Test
    public void toStringIncludesFailedBacking() {
        Throwable throwable = new Throwable("failure");
        CompletableFuture<Integer> backing = CompletableFuture.failedFuture(throwable);
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        String replyResult = reply.toString();

        assertThat(replyResult, containsString(backing.toString()));
        assertThat(replyResult, containsString(throwable.toString()));
    }

    @Test
    public void toStringIncludesIncompleteBacking() {
        CompletableFuture<Integer> backing = new CompletableFuture<>();
        Reply<Integer> reply = Reply.ofBackingForCall(backing, firstCaller, node);

        String replyResult = reply.toString();

        assertThat(replyResult, containsString(backing.toString()));
        assertThat(replyResult, containsString("Not completed"));
    }
}
