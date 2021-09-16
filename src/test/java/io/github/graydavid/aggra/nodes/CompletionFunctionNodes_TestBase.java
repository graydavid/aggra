package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import com.google.common.base.Throwables;
import com.google.common.collect.Streams;

import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.PrimingFailureStrategy;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.SpecifyFunctionAndDependencies;
import io.github.graydavid.onemoretry.Try;

/**
 * A common base class for testing CompletionFunctionNodes. You can find other CompletionFunctionNodes-related tests
 * under CompletionFunctionNodes_*Test classes.
 */
public abstract class CompletionFunctionNodes_TestBase {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);
    private final Executor asynchronousExecutor = Executors.newCachedThreadPool();

    protected abstract Type completionFunctionType();

    protected abstract SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role);

    protected Collection<Node<?, ?>> getExtraDependencies() {
        return List.of();
    }

    /**
     * Should run an assertion appropriate to the lingering- or jumping-variant about whether the consumerThread should
     * match the completionThread.
     */
    protected abstract void assertConsumingConsistentWithCompletionThread(Thread consumingThread,
            Thread completionThread);

    @Test
    public void goesToExpectedThreadForSuccessfulResponse() {
        /*
         * Step 1: Set up a completion node to run a Future in a different thread and return which thread it was run on.
         * Use a CountDownLatch to make sure that the consumer will be waiting on the completion node before the
         * completion node completes; otherwise, if the completion node completes first, then the consumer may be run on
         * the testing thread, which would give a false negative for the overall test (in the lingering case).
         */
        CountDownLatch allowCompletionToComplete = new CountDownLatch(1);
        Node<TestMemory, Thread> captureCompletionThread = functionNodesToTest("capture-completion-thread")
                .get(() -> CompletableFuture.supplyAsync(() -> {
                    awaitOrThrow(allowCompletionToComplete, 5, TimeUnit.SECONDS);
                    return Thread.currentThread();
                }, asynchronousExecutor));

        /*
         * Step 2: Call the completion node, chain a consumer on the end to capture which thread the consumer is run in,
         * and then signal that the completion node can continue.
         */
        Reply<Thread> captureCompletionThreadFuture = TestData.callNodeInNewTestMemoryGraph(graphInput,
                captureCompletionThread);
        CompletableFuture<Thread> captureConsumingThreadFuture = captureCompletionThreadFuture
                .thenApply(thread -> Thread.currentThread())
                .toCompletableFuture();
        allowCompletionToComplete.countDown();

        /*
         * Step 3: compare the consuming and completion node threads. They may be the same or different, depending if
         * the completion node was lingering or jumping.
         */
        Thread consumingThread = captureConsumingThreadFuture.join();
        Thread completionThread = captureCompletionThreadFuture.join();
        assertConsumingConsistentWithCompletionThread(consumingThread, completionThread);
    }

    private static void awaitOrThrow(CountDownLatch countDownLatch, long timeout, TimeUnit timeUnit) {
        boolean waitSuccessful = Try.callUnchecked(() -> countDownLatch.await(timeout, timeUnit));
        if (!waitSuccessful) {
            throw new RuntimeException("Wait failed");
        }
    }

    @Test
    public void goesToExpectedThreadForReturnedFailedResponse() {
        /*
         * Step 1: Set up an completion node to run a Future in a different thread and return which thread it was run on
         * (through an exception). Use a CountDownLatch to make sure that the consumer will be waiting on the completion
         * node before the completion node completes; otherwise, if the completion node completes first, then the
         * consumer may be run on the testing thread, which would give a false negative for the overall test (in the
         * lingering case).
         */
        CountDownLatch allowCompletionToComplete = new CountDownLatch(1);
        Node<TestMemory, Thread> captureCompletionThread = functionNodesToTest("capture-completion-thread")
                .get(() -> CompletableFuture.supplyAsync(() -> {
                    awaitOrThrow(allowCompletionToComplete, 5, TimeUnit.SECONDS);
                    throw new ThreadStoringRuntimeException(Thread.currentThread());
                }, asynchronousExecutor));

        /*
         * Step 2: Call the completion node, chain a consumer on the end to capture which thread the consumer is run in,
         * and then signal that the completion node can continue.
         */
        Reply<Thread> captureCompletionThreadFuture = TestData.callNodeInNewTestMemoryGraph(graphInput,
                captureCompletionThread);
        CompletableFuture<Thread> captureConsumingThreadFuture = captureCompletionThreadFuture
                .handle((thread, throwable) -> Thread.currentThread())
                .toCompletableFuture();
        allowCompletionToComplete.countDown();

        /*
         * Step 3: compare the consuming and completion node threads. They may be the same or different, depending if
         * the completion node was lingering or jumping.
         */
        Thread consumingThread = captureConsumingThreadFuture.join();
        assertThrows(Throwable.class, captureCompletionThreadFuture::join);
        Thread completionThread = captureCompletionThreadFuture.getFirstNonContainerExceptionNow()
                .map(ThreadStoringRuntimeException.class::cast)
                .get().thread;
        assertConsumingConsistentWithCompletionThread(consumingThread, completionThread);
    }

    /** Helper exception to store thread when throwing exception. */
    private static class ThreadStoringRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Thread thread;

        public ThreadStoringRuntimeException(Thread thread) {
            this.thread = thread;
        }
    }

    @Test
    public void preservesResultsForReturnedExceptions() {
        testPreservesResultsForReturnedException(new IllegalArgumentException());
        testPreservesResultsForReturnedException(new CompletionException(new IllegalArgumentException()));
        testPreservesResultsForReturnedException(new CancellationException());
        testPreservesResultsForReturnedException(new ExecutionException(new IllegalArgumentException()));
        testPreservesResultsForReturnedException(new Error());
        testPreservesResultsForReturnedException(new Throwable());
    }

    public void testPreservesResultsForReturnedException(Throwable throwable) {
        Node<TestMemory, String> failWithException = functionNodesToTest("fail-non-completion")
                .getValue(CompletableFuture.failedFuture(throwable));

        Reply<String> failureReply = TestData.callNodeInNewTestMemoryGraph(graphInput, failWithException);

        assertThrows(Throwable.class, failureReply::join);
        assertThat(Throwables.getCausalChain(failureReply.getExceptionNow().get()), hasItem(throwable));
        Throwable firstExpectedNonContainer = ((throwable instanceof CompletionException)
                || (throwable instanceof ExecutionException)) ? throwable.getCause() : throwable;
        assertThat(failureReply.getFirstNonContainerExceptionNow().get(), sameInstance(firstExpectedNonContainer));
    }

    @Test
    public void preservesResultsForThrownExceptions() {
        testPreservesResultsForThrownException(new IllegalArgumentException());
        testPreservesResultsForThrownException(new CompletionException(new IllegalArgumentException()));
        testPreservesResultsForThrownException(new CancellationException());
        testPreservesResultsForThrownException(new Error());
    }

    public void testPreservesResultsForThrownException(Throwable throwable) {
        Supplier<CompletableFuture<String>> thrower = () -> {
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }
            throw (RuntimeException) throwable;
        };
        Node<TestMemory, String> failWithException = functionNodesToTest("fail-non-completion").get(thrower);

        Reply<String> failureReply = TestData.callNodeInNewTestMemoryGraph(graphInput, failWithException);

        assertThrows(Throwable.class, failureReply::join);
        assertThat(Throwables.getCausalChain(failureReply.getExceptionNow().get()), hasItem(throwable));
        Throwable firstExpectedNonContainer = ((throwable instanceof CompletionException)
                || (throwable instanceof ExecutionException)) ? throwable.getCause() : throwable;
        assertThat(failureReply.getFirstNonContainerExceptionNow().get(), sameInstance(firstExpectedNonContainer));
    }

    @Test
    public void allowsOptionalPrimingFailureStrategyToBeSet() {
        Node<TestMemory, String> getRawValue = functionNodesToTest("supply-raw-value")
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .getValue(CompletableFuture.completedFuture("raw-value"));

        assertThat(getRawValue.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThat(getRawValue.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
    }

    @Test
    public void allowsOtherOptionalSettingsToBeSet() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, String> getRawValue = functionNodesToTest("supply-raw-value")
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory)
                .getValue(CompletableFuture.completedFuture("raw-value"));

        assertThat(getRawValue.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(getRawValue.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(getRawValue.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, String> getRawValue = functionNodesToTest("supply-raw-value")
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .getValue(CompletableFuture.completedFuture("raw-value"));

        assertThat(getRawValue.getGraphValidatorFactories(), empty());
    }

    @Test
    public void getsRawValues() {
        Node<TestMemory, String> getRawValue = functionNodesToTest("supply-raw-value")
                .getValue(CompletableFuture.completedFuture("raw-value"));

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, getRawValue).join(), is("raw-value"));
        assertThat(getRawValue.getType(), is(completionFunctionType()));
        assertThat(getRawValue.getRole(), is(Role.of("supply-raw-value")));
        assertThat(getRawValue.getDependencies(), containsDependencyNodes());
    }

    protected Matcher<Collection<? extends Dependency<?, ?>>> containsDependencyNodes(Node<?, ?>... dependencyNodes) {
        Set<Dependency<?, ?>> dependencies = Streams
                .concat(Arrays.stream(dependencyNodes), getExtraDependencies().stream())
                .map(node -> newSameMemoryDependency(node, PrimingMode.PRIMED))
                .collect(Collectors.toSet());
        return is(dependencies);
    }

    @Test
    public void getsThroughSupplier() {
        Node<TestMemory, String> getThroughSupplier = functionNodesToTest("supply-through-supplier")
                .get(() -> CompletableFuture.completedFuture("value"));

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, getThroughSupplier).join(), is("value"));
        assertThat(getThroughSupplier.getType(), is(completionFunctionType()));
        assertThat(getThroughSupplier.getRole(), is(Role.of("supply-through-supplier")));
        assertThat(getThroughSupplier.getDependencies(), containsDependencyNodes());
    }

    @Test
    public void appliesOneAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");

        Node<TestMemory, String> apply1AryFunctionToStrings = functionNodesToTest("apply-1-ary-function-to-strings")
                .apply(string1 -> CompletableFuture.completedFuture("applied-" + string1), supplyString1);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply1AryFunctionToStrings).join(),
                is("applied-string-1"));
        assertThat(apply1AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply1AryFunctionToStrings.getRole(), is(Role.of("apply-1-ary-function-to-strings")));
        assertThat(apply1AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1));
    }

    private FunctionNodes.SpecifyFunctionAndDependencies<TestMemory> nonCompletionSyncFunctionNode(String role) {
        return FunctionNodes.synchronous(Role.of(role), TestMemory.class);
    }

    @Test
    public void appliesTwoAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");

        Node<TestMemory, String> apply2AryFunctionToStrings = functionNodesToTest("apply-2-ary-function-to-strings")
                .apply((string1, string2) -> CompletableFuture.completedFuture("applied-" + string1 + '-' + string2),
                        supplyString1, supplyString2);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply2AryFunctionToStrings).join(),
                is("applied-string-1-string-2"));
        assertThat(apply2AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply2AryFunctionToStrings.getRole(), is(Role.of("apply-2-ary-function-to-strings")));
        assertThat(apply2AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2));
    }

    @Test
    public void appliesThreeAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");

        Node<TestMemory, String> apply3AryFunctionToStrings = functionNodesToTest("apply-3-ary-function-to-strings")
                .apply((string1, string2, string3) -> CompletableFuture
                        .completedFuture("applied-" + string1 + '-' + string2 + '-' + string3), supplyString1,
                        supplyString2, supplyString3);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply3AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3"));
        assertThat(apply3AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply3AryFunctionToStrings.getRole(), is(Role.of("apply-3-ary-function-to-strings")));
        assertThat(apply3AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3));
    }

    @Test
    public void appliesFourAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");

        Node<TestMemory, String> apply4AryFunctionToStrings = functionNodesToTest("apply-4-ary-function-to-strings")
                .apply((string1, string2, string3, string4) -> CompletableFuture
                        .completedFuture("applied-" + string1 + '-' + string2 + '-' + string3 + '-' + string4),
                        supplyString1, supplyString2, supplyString3, supplyString4);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply4AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4"));
        assertThat(apply4AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply4AryFunctionToStrings.getRole(), is(Role.of("apply-4-ary-function-to-strings")));
        assertThat(apply4AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4));
    }

    @Test
    public void appliesFiveAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");

        Node<TestMemory, String> apply5AryFunctionToStrings = functionNodesToTest("apply-5-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5) -> CompletableFuture.completedFuture(
                        "applied-" + string1 + '-' + string2 + '-' + string3 + '-' + string4 + '-' + string5),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply5AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5"));
        assertThat(apply5AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply5AryFunctionToStrings.getRole(), is(Role.of("apply-5-ary-function-to-strings")));
        assertThat(apply5AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5));
    }

    @Test
    public void appliesSixAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = nonCompletionSyncFunctionNode("supply-string-6").getValue("string-6");

        Node<TestMemory, String> apply6AryFunctionToStrings = functionNodesToTest("apply-6-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5,
                        string6) -> CompletableFuture.completedFuture("applied-" + string1 + '-' + string2 + '-'
                                + string3 + '-' + string4 + '-' + string5 + '-' + string6),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply6AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6"));
        assertThat(apply6AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply6AryFunctionToStrings.getRole(), is(Role.of("apply-6-ary-function-to-strings")));
        assertThat(apply6AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6));
    }

    @Test
    public void appliesSevenAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = nonCompletionSyncFunctionNode("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = nonCompletionSyncFunctionNode("supply-string-7").getValue("string-7");

        Node<TestMemory, String> apply7AryFunctionToStrings = functionNodesToTest("apply-7-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6,
                        string7) -> CompletableFuture.completedFuture("applied-" + string1 + '-' + string2 + '-'
                                + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-' + string7),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply7AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7"));
        assertThat(apply7AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply7AryFunctionToStrings.getRole(), is(Role.of("apply-7-ary-function-to-strings")));
        assertThat(apply7AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6, supplyString7));
    }

    @Test
    public void appliesEightAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = nonCompletionSyncFunctionNode("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = nonCompletionSyncFunctionNode("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = nonCompletionSyncFunctionNode("supply-string-8").getValue("string-8");

        Node<TestMemory, String> apply8AryFunctionToStrings = functionNodesToTest("apply-8-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7, string8) -> CompletableFuture
                        .completedFuture("applied-" + string1 + '-' + string2 + '-' + string3 + '-' + string4 + '-'
                                + string5 + '-' + string6 + '-' + string7 + '-' + string8),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7, supplyString8);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply8AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8"));
        assertThat(apply8AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply8AryFunctionToStrings.getRole(), is(Role.of("apply-8-ary-function-to-strings")));
        assertThat(apply8AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6, supplyString7, supplyString8));
    }

    @Test
    public void appliesNineAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = nonCompletionSyncFunctionNode("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = nonCompletionSyncFunctionNode("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = nonCompletionSyncFunctionNode("supply-string-8").getValue("string-8");
        Node<TestMemory, String> supplyString9 = nonCompletionSyncFunctionNode("supply-string-9").getValue("string-9");

        Node<TestMemory, String> apply9AryFunctionToStrings = functionNodesToTest(
                "apply-9-ary-function-to-strings").apply(
                        (string1, string2, string3, string4, string5, string6, string7, string8,
                                string9) -> CompletableFuture.completedFuture("applied-" + string1 + '-' + string2 + '-'
                                        + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-' + string7 + '-'
                                        + string8 + '-' + string9),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7, supplyString8, supplyString9);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply9AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8-string-9"));
        assertThat(apply9AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply9AryFunctionToStrings.getRole(), is(Role.of("apply-9-ary-function-to-strings")));
        assertThat(apply9AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5,
                        supplyString6, supplyString7, supplyString8, supplyString9));
    }

    @Test
    public void appliesTenAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = nonCompletionSyncFunctionNode("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = nonCompletionSyncFunctionNode("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = nonCompletionSyncFunctionNode("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = nonCompletionSyncFunctionNode("supply-string-8").getValue("string-8");
        Node<TestMemory, String> supplyString9 = nonCompletionSyncFunctionNode("supply-string-9").getValue("string-9");
        Node<TestMemory, String> supplyString10 = nonCompletionSyncFunctionNode("supply-string-10")
                .getValue("string-10");

        Node<TestMemory, String> apply10AryFunctionToStrings = functionNodesToTest("apply-10-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7, string8, string9,
                        string10) -> CompletableFuture.completedFuture("applied-" + string1 + '-' + string2 + '-'
                                + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-' + string7 + '-'
                                + string8 + '-' + string9 + '-' + string10),
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7, supplyString8, supplyString9, supplyString10);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply10AryFunctionToStrings).join(), is(
                "applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8-string-9-string-10"));
        assertThat(apply10AryFunctionToStrings.getType(), is(completionFunctionType()));
        assertThat(apply10AryFunctionToStrings.getRole(), is(Role.of("apply-10-ary-function-to-strings")));
        assertThat(apply10AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5,
                        supplyString6, supplyString7, supplyString8, supplyString9, supplyString10));
    }

    @Test
    public void appliesNaryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = nonCompletionSyncFunctionNode("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = nonCompletionSyncFunctionNode("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = nonCompletionSyncFunctionNode("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = nonCompletionSyncFunctionNode("supply-string-4").getValue("string-4");

        Node<TestMemory, String> applyNaryFunctionToStrings = functionNodesToTest("apply-nary-function-to-strings")
                .apply(allStrings -> CompletableFuture
                        .completedFuture("applied-" + allStrings.stream().collect(Collectors.joining("-"))),
                        List.of(supplyString1, supplyString2, supplyString3, supplyString4));

        assertThat(applyNaryFunctionToStrings.getRole(), is(Role.of("apply-nary-function-to-strings")));
        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, applyNaryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4"));
        assertThat(applyNaryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4));
    }

    @Test
    public void nAryApplyAllowsDependenciesOfMixedTypes() {
        Node<TestMemory, String> supplyString = nonCompletionSyncFunctionNode("supply-string").getValue("string");
        Node<TestMemory, Integer> supplyInteger = nonCompletionSyncFunctionNode("supply-integer").getValue(212);

        Node<TestMemory, String> applyNaryFunctionToMixedTypes = functionNodesToTest(
                "apply-nary-function-to-mixed-types")
                        .apply(allStrings -> CompletableFuture.completedFuture("applied-"
                                + allStrings.stream().map(Object::toString).collect(Collectors.joining("-"))),
                                List.of(supplyString, supplyInteger));

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, applyNaryFunctionToMixedTypes).join(),
                is("applied-string-212"));
    }
}
