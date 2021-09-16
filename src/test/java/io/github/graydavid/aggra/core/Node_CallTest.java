package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.anyDependencyCallingDevice;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionException;
import io.github.graydavid.aggra.core.Behaviors.InterruptClearingException;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the logic in {@link Node#call(Caller, Memory)} not already covered by Node_Call*Test classes. You can find
 * other Node-related tests under Node_*Test classes.
 */
public class Node_CallTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Storage storage = new ConcurrentHashMapStorage();
    private final TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(55),
            () -> storage);
    private final GraphCall<?> graphCall = mock(GraphCall.class);
    private final Observer observer = new Observer() {};

    // We don't want interrupt status to leak over from test to test
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    public void throwsExceptionGivenNullCaller() {
        TestMemory memory = mock(TestMemory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .build(NodeMocks.behavior());

        assertThrows(NullPointerException.class, () -> node.call(null, memory, graphCall, observer));
        verifyNoInteractions(memory);
    }

    @Test
    public void throwsExceptionGivenSelfAsCaller() {
        TestMemory memory = mock(TestMemory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .build(NodeMocks.behavior());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> node.call(node, memory, graphCall, observer));
        assertThat(thrown.getMessage(), containsString(node.toString()));
        verifyNoInteractions(memory);
    }

    @Test
    public void throwsExceptionGivenNullGraphCall() {
        TestMemory memory = mock(TestMemory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .build(NodeMocks.behavior());

        assertThrows(NullPointerException.class, () -> node.call(caller, memory, null, observer));
        verifyNoInteractions(memory);
    }

    @Test
    public void throwsExceptionGivenNullObserver() {
        TestMemory memory = mock(TestMemory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .build(NodeMocks.behavior());

        assertThrows(NullPointerException.class, () -> node.call(caller, memory, graphCall, null));
        verifyNoInteractions(memory);
    }

    @Test
    public void returnsExceptionGivenBehaviorThatReturnsNullCompletionStage() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .build(device -> null);

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        Throwable encountered = reply.getFirstNonContainerExceptionNow().get();
        assertThat(encountered, instanceOf(MisbehaviorException.class));
        assertThat(encountered.getMessage(), containsString("Behaviors are not allowed to return null Responses"));
    }

    @Test
    public void returnsExceptionGivenBehaviorWithCompositeCancelSignalThatReturnsNullCompletionStage() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .buildWithCompositeCancelSignal((device, signal) -> null);

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        Throwable encountered = reply.getFirstNonContainerExceptionNow().get();
        assertThat(encountered, instanceOf(MisbehaviorException.class));
        assertThat(encountered.getMessage(), containsString("Behaviors are not allowed to return null Responses"));
    }

    @Test
    public void returnsExceptionGivenBehaviorWithCustomCancelActionThatReturnsNullCompletionStage() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("placeholder"))
                .role(Role.of("placeholder"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return null;
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        Throwable encountered = reply.getFirstNonContainerExceptionNow().get();
        assertThat(encountered, instanceOf(MisbehaviorException.class));
        assertThat(encountered.getMessage(), containsString("Behaviors are not allowed to return null Responses"));
    }

    @Test
    public void withWaitForAllContinuePrimingFailureStrategyReturnsNormalResponseIfBehaviorDoesEvenIfDependenciesFail() {
        Node<TestMemory, Integer> primedDependency = exceptionalNode("throw-primed", new IllegalArgumentException());
        Node<TestMemory, Integer> unprimedDependency = exceptionalNode("throw-unprimed", new IllegalStateException());
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumePrimed = builder.primedDependency(primedDependency);
        SameMemoryDependency<TestMemory, Integer> consumeUnprimed = builder
                .sameMemoryUnprimedDependency(unprimedDependency);
        CompletableFuture<Integer> normalResponse = CompletableFuture.completedFuture(41);
        Node<TestMemory, Integer> main = builder.type(Type.generic("main"))
                .role(Role.of("normal"))
                .primingFailureStrategy(PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE,
                        DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> {
                    Try.callCatchRuntime(() -> device.call(consumePrimed).join());
                    Try.callCatchRuntime(() -> device.call(consumeUnprimed).join());
                    return normalResponse;
                });

        Reply<Integer> reply = main.call(caller, memory, graphCall, observer);

        assertThat(Try.callUnchecked(() -> reply.get(1, TimeUnit.SECONDS)), is(normalResponse.join()));
    }

    private static Node<TestMemory, Integer> exceptionalNode(String role, RuntimeException exception) {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("throwing"))
                .role(Role.of(role))
                .build(device -> {
                    throw exception;
                });
    }

    @Test
    public void evenWaitForAllContinuePrimingFailureStrategyDoesntWaitForPrimedDependenciesToFinishIfOneOfThemThrowsExceptionWhenCalled() {
        // Create a node with two primed dependencies
        Node<TestMemory, Integer> neverFinishPrimedDependency1 = TestData.nodeBackedBy(new CompletableFuture<>());
        Node<TestMemory, Integer> neverFinishPrimedDependency2 = TestData.nodeBackedBy(new CompletableFuture<>());
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        builder.primedDependency(neverFinishPrimedDependency1);
        builder.primedDependency(neverFinishPrimedDependency2);
        Node<TestMemory, Integer> main = builder.type(Type.generic("main"))
                .role(Role.of("normal"))
                .primingFailureStrategy(PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE, DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));

        // Create a memory that will throw an exception on the second call to a primed dependency
        AtomicInteger callsToPrimedDependency = new AtomicInteger(0);
        IllegalStateException exception = new IllegalStateException();
        Storage throwOnSecondPrimeCall = new Storage() {
            @Override
            public <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
                if (node == neverFinishPrimedDependency1 || node == neverFinishPrimedDependency2) {
                    callsToPrimedDependency.incrementAndGet();
                }
                if (callsToPrimedDependency.get() > 1) {
                    throw exception;
                }
                return storage.computeIfAbsent(node, replySupplier);
            }
        };
        TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(54),
                () -> throwOnSecondPrimeCall);

        Reply<Integer> reply = main.call(caller, memory, graphCall, observer);

        assertTrue(reply.isDone());
        assertThat(callsToPrimedDependency.get(), is(2));
        assertThat(reply.getFirstNonContainerExceptionNow().get(), is(exception));
    }

    @Test
    public void withFailFastStopPrimingFailureStrategyFailsFastAndStopsIfPrimedDependencyFails() {
        Node<TestMemory, Integer> neverFinishingPrimedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("never-finishing"))
                .role(Role.of("never-finishes"))
                .build(device -> new CompletableFuture<>());
        IllegalArgumentException primeFailure = new IllegalArgumentException();
        Node<TestMemory, Integer> throwingPrimedDependency = exceptionalNode("throw", primeFailure);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        builder.primedDependency(neverFinishingPrimedDependency);
        builder.primedDependency(throwingPrimedDependency);
        Behavior<TestMemory, Integer> mainBehavior = NodeMocks.behavior();
        when(mainBehavior.run(anyDependencyCallingDevice())).thenReturn(CompletableFuture.completedFuture(34));
        Node<TestMemory, Integer> main = builder.type(Type.generic("main"))
                .role(Role.of("normal"))
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .build(mainBehavior);

        Reply<Integer> reply = main.call(caller, memory, graphCall, observer);

        assertTrue(reply.isDone());
        assertThat(reply.getFirstNonContainerExceptionNow().get(), is(primeFailure));
        verifyNoInteractions(mainBehavior);
    }

    @Test
    public void withFailFastStopPrimingFailureStrategyWaitsForAllPrimedDependenciesAndProceedsIfAllPrimedDependenciesSucceed() {
        CompletableFuture<Integer> lateFinishingResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> lateFinishingPrimedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("late-finishing"))
                .role(Role.of("late-finishes"))
                .build(device -> lateFinishingResponse);
        Node<TestMemory, Integer> earlyFinishingPrimedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("early-finishing"))
                .role(Role.of("early-finishing"))
                .build(device -> CompletableFuture.completedFuture(32));
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        builder.primedDependency(lateFinishingPrimedDependency);
        builder.primedDependency(earlyFinishingPrimedDependency);
        Node<TestMemory, Integer> main = builder.type(Type.generic("main"))
                .role(Role.of("normal"))
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(34));

        Reply<Integer> reply = main.call(caller, memory, graphCall, observer);

        assertFalse(reply.isDone());

        lateFinishingResponse.complete(12);
        assertTrue(reply.isDone());
        assertThat(reply.join(), is(34));
    }

    @Test
    public void callsDependenciesToPrimeWithConsumerNodeAsCaller() {
        Node<TestMemory, Integer> dependencyToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(92));
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("consumer"))
                .role(Role.of("consumer"));
        consumerBuilder.primedDependency(dependencyToPrime);
        Node<TestMemory, Void> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(null));
        Map<String, Object> dependencyToPrimeArgs = new HashMap<>();
        ObserverBeforeStart<Object> dependencyToPrimeObserverStart = (type, caller, node, mem) -> {
            if (node == dependencyToPrime) {
                dependencyToPrimeArgs.put("caller", caller);
                dependencyToPrimeArgs.put("memory", memory);
            }
            return ObserverAfterStop.doNothing();
        };
        Observer dependencyToPrimeObserver = Observer.builder()
                .observerBeforeFirstCall(dependencyToPrimeObserverStart)
                .build();

        consumer.call(caller, memory, graphCall, dependencyToPrimeObserver);

        assertThat(dependencyToPrimeArgs.get("caller"), is(consumer));
        assertThat(dependencyToPrimeArgs.get("memory"), is(memory));
    }

    @Test
    public void doesntDirectlyCallDependenciesNotToPrimeOutsideOfBehavior() {
        Node<TestMemory, ?> dependencyNotToPrime = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> dontCallNotToPrimeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("dontCallNotToPrime"));
        dontCallNotToPrimeBuilder.sameMemoryUnprimedDependency(dependencyNotToPrime);
        Node<TestMemory, Integer> dontCallNotToPrime = dontCallNotToPrimeBuilder
                .build(device -> CompletableFuture.completedFuture(5));

        dontCallNotToPrime.call(caller, memory, graphCall, observer);

        verify(dependencyNotToPrime, never()).call(any(), any(), any(), any());
    }

    @Test
    public void callsDependenciesToPrimeBeforeCallingBehavior() {
        CountDownLatch dependencyCalled = new CountDownLatch(1);
        Node<TestMemory, Void> dependencyToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("dependencyToPrime"))
                .build(device -> {
                    dependencyCalled.countDown();
                    return CompletableFuture.completedFuture(null);
                });
        Node.CommunalBuilder<TestMemory> waitForDependencyInBehaviorBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("waitForDependencyInBehavior"));
        waitForDependencyInBehaviorBuilder.primedDependency(dependencyToPrime);
        Node<TestMemory, Boolean> waitForDependencyInBehavior = waitForDependencyInBehaviorBuilder
                .build(device -> CompletableFuture
                        .completedFuture(Try.callUnchecked(() -> dependencyCalled.await(5, TimeUnit.SECONDS))));

        Reply<Boolean> waitedForDependency = waitForDependencyInBehavior.call(caller, memory, graphCall, observer);

        assertTrue(waitedForDependency.join());
    }

    @Test
    public void doesntBlockOnDependenciesToPrimeBeforeBehaviorDuringConsumerCall() {
        CountDownLatch consumerCalled = new CountDownLatch(1);
        Node<TestMemory, Boolean> waitForConsumerToBeCalled = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("dependencyToPrime"))
                .build(device -> {
                    return CompletableFuture
                            .supplyAsync(Try.uncheckedSupplier(() -> consumerCalled.await(5, TimeUnit.SECONDS)));
                });
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("consumer"));
        SameMemoryDependency<TestMemory, Boolean> consumeWaitForConsumerToBeCalled = consumerBuilder
                .primedDependency(waitForConsumerToBeCalled);
        Node<TestMemory, Boolean> consumer = consumerBuilder
                .build(device -> device.call(consumeWaitForConsumerToBeCalled));

        Reply<Boolean> dependencyCanWaitForConsumerToBeCalled = consumer.call(caller, memory, graphCall, observer);
        consumerCalled.countDown();

        assertTrue(dependencyCanWaitForConsumerToBeCalled.join());
    }

    @Test
    public void doesntBlockOnBehaviorResponseDuringCall() {
        CountDownLatch nodeCalled = new CountDownLatch(1);
        Node<TestMemory, Boolean> waitForSelfCall = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("waitForSelfCall"))
                .build(device -> {
                    return CompletableFuture
                            .supplyAsync(Try.uncheckedSupplier(() -> nodeCalled.await(5, TimeUnit.SECONDS)));
                });

        Reply<Boolean> nodeCanWaitForItselfToBeCalled = waitForSelfCall.call(caller, memory, graphCall, observer);
        nodeCalled.countDown();

        assertTrue(Try.callUnchecked(() -> nodeCanWaitForItselfToBeCalled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    public void runsUnderlyingCallMethodsOnlyOnceInOrderWhenExecutedFromMultipleThreads() {
        final int NUM_TRIES = 500; // Multi-threaded code is non-deterministic; so try multiple times
        IntStream.range(0, NUM_TRIES).forEach(this::tryOnceToVerifyCallRunOnlyOnce);
    }

    private void tryOnceToVerifyCallRunOnlyOnce(int trialNum) {
        Behavior<TestMemory, Integer> dependencyBehavior = NodeMocks.behavior();
        Node<TestMemory, Integer> dependencyNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("dependencyNode"))
                .build(dependencyBehavior);
        when(dependencyBehavior.run(anyDependencyCallingDevice())).thenReturn(CompletableFuture.completedFuture(1));
        Behavior<TestMemory, Integer> mainBehavior = NodeMocks.behavior();
        Node.CommunalBuilder<TestMemory> mainNodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("mainNode"));
        mainNodeBuilder.primedDependency(dependencyNode);
        Node<TestMemory, Integer> mainNode = mainNodeBuilder.build(mainBehavior);
        when(mainBehavior.run(anyDependencyCallingDevice())).thenReturn(CompletableFuture.completedFuture(2));

        new ConcurrentLoadGenerator(10, () -> mainNode.call(caller, memory, graphCall, observer)).run();
        Reply<Integer> mainReply = mainNode.call(caller, memory, graphCall, observer);
        Reply<Integer> dependencyReply = dependencyNode.call(caller, memory, graphCall, observer);

        assertThat(mainReply.join(), is(2));
        assertThat(dependencyReply.join(), is(1));
        InOrder inOrder = inOrder(dependencyBehavior, mainBehavior);
        inOrder.verify(dependencyBehavior, times(1)).run(anyDependencyCallingDevice());
        inOrder.verify(mainBehavior, times(1)).run(anyDependencyCallingDevice());
    }

    @Test
    public void withNodeForAllDependencyMaxLifetimeWaitsForDependenciesToFinish() {
        CountDownLatch dependencyWaitForSignal = new CountDownLatch(1);
        Node<TestMemory, Boolean> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture
                        .supplyAsync(Try.uncheckedSupplier(() -> dependencyWaitForSignal.await(5, TimeUnit.SECONDS))));
        Node.CommunalBuilder<TestMemory> mainBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL);
        SameMemoryDependency<TestMemory, Boolean> consumeDependency = mainBuilder
                .sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> main = mainBuilder.build(device -> {
            device.call(consumeDependency);
            return CompletableFuture.completedFuture(50);
        });

        Reply<Integer> result = main.call(caller, memory, graphCall, observer);
        Reply<Boolean> dependencyGotSignal = dependency.call(caller, memory, graphCall, observer);

        assertFalse(result.isDone());
        assertFalse(dependencyGotSignal.isDone());
        dependencyWaitForSignal.countDown();
        assertThat(Try.callUnchecked(() -> result.get(1, TimeUnit.SECONDS)), is(50));
        assertTrue(dependencyGotSignal.join());
    }

    @Test
    public void withGraphDependencyMaxLifetimeDoesntWaitForDependenciesToFinish() {
        CountDownLatch dependencySignal = new CountDownLatch(1);
        Node<TestMemory, Boolean> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture
                        .supplyAsync(Try.uncheckedSupplier(() -> dependencySignal.await(5, TimeUnit.SECONDS))));
        Node.CommunalBuilder<TestMemory> mainBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.GRAPH);
        SameMemoryDependency<TestMemory, Boolean> consumeDependency = mainBuilder
                .sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> main = mainBuilder.build(device -> {
            device.call(consumeDependency);
            return CompletableFuture.completedFuture(50);
        });

        Reply<Integer> result = main.call(caller, memory, graphCall, observer);
        Reply<Boolean> dependencyGotSignal = dependency.call(caller, memory, graphCall, observer);

        assertThat(Try.callUnchecked(() -> result.get(1, TimeUnit.SECONDS)), is(50));
        assertFalse(dependencyGotSignal.isDone());
        dependencySignal.countDown();
        assertTrue(dependencyGotSignal.join());
    }

    private static Node<TestMemory, Boolean> nodeWaitingOn(CountDownLatch waitForSignal) {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("waiting"))
                .role(Role.of("wait"))
                .build(device -> {
                    return CompletableFuture
                            .supplyAsync(Try.uncheckedSupplier(() -> waitForSignal.await(5, TimeUnit.SECONDS)));
                });
    }

    @Test
    public void withFailureInPrimingStillWaitsForSuccessfulPrimedToFinish() {
        CountDownLatch dependencyWaitForSignal = new CountDownLatch(1);
        // We aren't guaranteed priming order, so set up both to wait...
        Node<TestMemory, Boolean> prime1 = nodeWaitingOn(dependencyWaitForSignal);
        Node<TestMemory, Boolean> prime2 = nodeWaitingOn(dependencyWaitForSignal);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        builder.primedDependency(prime1);
        builder.primedDependency(prime2);
        Node<TestMemory, Integer> main = builder.type(Type.generic("main"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> {
                    return CompletableFuture.completedFuture(50);
                });
        // ...but only succeed on the first one
        IllegalArgumentException failure = new IllegalArgumentException("forced-failure");
        Node<TestMemory, Boolean> succeedOn = (main.getPrimedDependencies().iterator().next().getNode() == prime1)
                ? prime1
                : prime2;
        Node<TestMemory, Boolean> failOn = (succeedOn == prime1) ? prime2 : prime1;
        Storage firstSuccess = new Storage() {
            @Override
            public <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
                if (node == failOn) {
                    throw failure;
                }
                return storage.computeIfAbsent(node, replySupplier);
            }
        };
        TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(55),
                () -> firstSuccess);

        Reply<Integer> result = main.call(caller, memory, graphCall, observer);
        Reply<Boolean> dependencyGotSignal = succeedOn.call(caller, memory, graphCall, observer);

        assertFalse(result.isDone());
        assertFalse(dependencyGotSignal.isDone());
        dependencyWaitForSignal.countDown();
        Try.callCatchRuntime(result::join);
        assertThat(result.getFirstNonContainerExceptionNow(), is(Optional.of(failure)));
        assertTrue(dependencyGotSignal.join());
    }

    @Test
    public void usesFirstCallExecutorToRunFirstCallOnlyLogic() {
        CountDownLatch proceedWithBehavior = new CountDownLatch(1);
        Node<TestMemory, Boolean> receivedProceedSignal = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("receivedProceedSignal"))
                .role(Role.of("receivedProceedSignal"))
                .build(device -> CompletableFuture
                        .completedFuture(Try.callUnchecked(() -> proceedWithBehavior.await(5, TimeUnit.SECONDS))));
        Executor firstCallExecutor = Executors.newSingleThreadExecutor();

        Reply<Boolean> reply = receivedProceedSignal.call(caller, memory, graphCall, observer, firstCallExecutor);
        proceedWithBehavior.countDown();

        assertTrue(reply.join());
    }

    @Test
    public void doesntUseFirstCallExecutorWhenFirstCallHasAlreadyHappened() {
        Node<TestMemory, Integer> node = TestData.nodeReturningValue(12);
        Executor firstCallExecutor = mock(Executor.class);

        Reply<Integer> firstCallReply = node.call(caller, memory, graphCall, observer);
        Reply<Integer> secondCallReply = node.call(caller, memory, graphCall, observer, firstCallExecutor);

        assertThat(firstCallReply, is(secondCallReply));
        verifyNoInteractions(firstCallExecutor);
    }

    @Test
    public void checksMemoryScopeCancellationBeforePrimingAndAvoidsPrimingIfTrue() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(15));
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(16));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        MemoryScope scope = memoryScopeForSingleNodeGraph(consumer);
        scope.triggerCancelSignal();
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(13));

        Reply<Integer> reply = consumer.call(caller, memory, graphCall, observer);

        assertThat(calledNodes, not(hasItem(dependency)));
        Throwable encounteredException = reply.getEncounteredExceptionNow().get();
        assertThat(encounteredException, instanceOf(CancellationException.class));
        assertThat(encounteredException.getMessage(), containsString(memory.toString()));
    }

    private static MemoryScope memoryScopeForSingleNodeGraph(Node<TestMemory, ?> node) {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        return MemoryScope.createForGraph(graph);
    }

    @Test
    public void checksReplyCancellationAfterPrimingCompletesAndBeforeBehaviorAndAvoidsBehaviorIfTrue() {
        CountDownLatch delayConsumerPrimingOfDependency = new CountDownLatch(1);
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.supplyAsync(() -> {
                    Try.runUnchecked(() -> delayConsumerPrimingOfDependency.await(5, TimeUnit.SECONDS));
                    return 15;
                }));
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        BehaviorWithCompositeCancelSignal<TestMemory, Integer> consumerBehavior = NodeMocks
                .behaviorWithCompositeCancelSignal();
        when(consumerBehavior.run(NodeMocks.anyDependencyCallingDevice(), any(CompositeCancelSignal.class)))
                .thenReturn(CompletableFuture.completedFuture(16));
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .buildWithCompositeCancelSignal(consumerBehavior);
        Node.CommunalBuilder<TestMemory> cancellorBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeConsumer = cancellorBuilder
                .sameMemoryUnprimedDependency(consumer);
        Node<TestMemory, Integer> cancellor = cancellorBuilder.type(Type.generic("cancellor"))
                .role(Role.of("cancellor"))
                .build(device -> {
                    Reply<Integer> consumerReply = device.call(consumeConsumer);
                    // Cheat and trigger signal directly, since we don't have a real GraphCall to do it through ignore
                    consumerReply.triggerCancelSignal();
                    return CompletableFuture.completedFuture(17);
                });
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        MemoryScope scope = memoryScopeForSingleNodeGraph(consumer);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(13));

        Reply<Integer> cancellorReply = cancellor.call(caller, memory, graphCall, observer);
        Reply<Integer> consumerReply = consumer.call(caller, memory, graphCall, observer);
        String intermediateConsumerReplyToString = consumerReply.toString();
        delayConsumerPrimingOfDependency.countDown();
        cancellorReply.join();

        assertThat(calledNodes, hasItem(dependency));
        verifyNoInteractions(consumerBehavior);
        Throwable encounteredException = consumerReply.getEncounteredExceptionNow().get();
        assertThat(encounteredException, instanceOf(CancellationException.class));
        assertThat(encounteredException.getMessage(), containsString(intermediateConsumerReplyToString));
    }

    @Test
    public void checksMemoryScopeCancellationAfterPrimingCompletesAndBeforeBehaviorAndAvoidsBehaviorIfTrue() {
        CountDownLatch primingStarted = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> {
                    primingStarted.countDown();
                    Try.runUnchecked(() -> cancellationFinished.await(5, TimeUnit.SECONDS));
                    return CompletableFuture.completedFuture(15);
                });
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        when(behavior.run(NodeMocks.anyDependencyCallingDevice())).thenReturn(CompletableFuture.completedFuture(16));
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(behavior);
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        MemoryScope scope = memoryScopeForSingleNodeGraph(consumer);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(13));

        CompletableFuture.runAsync(() -> {
            Try.runUnchecked(() -> primingStarted.await(5, TimeUnit.SECONDS));
            scope.triggerCancelSignal();
            cancellationFinished.countDown();
        });
        Reply<Integer> reply = consumer.call(caller, memory, graphCall, observer);

        assertThat(calledNodes, hasItem(dependency));
        verifyNoInteractions(behavior);
        Throwable encounteredException = reply.getEncounteredExceptionNow().get();
        assertThat(encounteredException, instanceOf(CancellationException.class));
        assertThat(encounteredException.getMessage(), containsString(memory.toString()));
    }

    @Test
    public void supportsBehaviorWithCompositeCancelSignalRunningJustLikeBehavior() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(41));

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        assertThat(reply.join(), is(41));
    }

    @Test
    public void constructsCompositeCancelSignalThatsOffWhenAllComponentsAreOff() {
        Node<TestMemory, Boolean> isSignalOn = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(signal.read()));

        Reply<Boolean> reply = isSignalOn.call(caller, memory, graphCall, observer);

        assertFalse(reply.join());
    }

    @Test
    public void constructsCompositeCancelSignalThatsOnWhenReplyComponentSignalIsOn() {
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        Node<TestMemory, Boolean> isSignalOn = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.supplyAsync(() -> {
                    Try.runUnchecked(() -> cancellationFinished.await(5, TimeUnit.SECONDS));
                    return signal.read();
                }));

        Reply<Boolean> reply = isSignalOn.call(caller, memory, graphCall, observer);
        assertTrue(reply.isResponsiveToCancelSignal());
        reply.triggerCancelSignal();
        cancellationFinished.countDown();

        assertTrue(reply.join());
    }

    @Test
    public void constructsCompositeCancelSignalThatsOnWhenMemoryScopeComponentSignalIsOn() {
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        Node<TestMemory, Integer> dependency = TestData.nodeReturningValue(14);
        Node.CommunalBuilder<TestMemory> isSignalOnBuilder = Node.communalBuilder(TestMemory.class);
        isSignalOnBuilder.primedDependency(dependency);
        Node<TestMemory, Boolean> isSignalOn = isSignalOnBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.supplyAsync(() -> {
                    Try.runUnchecked(() -> cancellationFinished.await(5, TimeUnit.SECONDS));
                    return signal.read();
                }));
        MemoryScope scope = memoryScopeForSingleNodeGraph(isSignalOn);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(13));

        Reply<Boolean> reply = isSignalOn.call(caller, memory, graphCall, observer);
        assertTrue(memory.getScope().isResponsiveToCancelSignal());
        memory.getScope().triggerCancelSignal();
        cancellationFinished.countDown();

        assertTrue(reply.join());
    }

    @Test
    public void supportsBehaviorWithCustomCancelActionRunningJustLikeBehavior() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(41),
                                doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        assertThat(reply.join(), is(41));
    }

    private static CustomCancelAction doNothingCancelAction() {
        return mayInterrupt -> {
        };
    }

    @Test
    public void constructsCompositeCancelSignalForCustomCancelActionThatMimicsThatWithoutWhenOff() {
        Node<TestMemory, Boolean> isSignalOn = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Boolean>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Boolean> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        CompletableFuture<Boolean> readSignal = CompletableFuture.completedFuture(signal.read());
                        return new CustomCancelActionBehaviorResponse<>(readSignal, doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Boolean> reply = isSignalOn.call(caller, memory, graphCall, observer);

        assertFalse(reply.join());
    }

    @Test
    public void constructsCompositeCancelSignalForCustomCancelActionThatMimicsThatWithoutWhenOn() {
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        Node<TestMemory, Boolean> isSignalOn = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Boolean>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Boolean> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        CompletableFuture<Boolean> readSignal = CompletableFuture.supplyAsync(() -> {
                            Try.runUnchecked(() -> cancellationFinished.await(5, TimeUnit.SECONDS));
                            return signal.read();
                        });
                        return new CustomCancelActionBehaviorResponse<>(readSignal, doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Boolean> reply = isSignalOn.call(caller, memory, graphCall, observer);
        assertTrue(reply.supportsCustomCancelAction());
        reply.triggerCancelSignal();
        cancellationFinished.countDown();

        assertTrue(reply.join());
    }

    @Test
    public void setsCustomCancelActionOnReplyWhenNecessary() {
        CustomCancelAction cancelAction = mock(CustomCancelAction.class);
        Node<TestMemory, Integer> neverFinish = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(new CompletableFuture<>(), cancelAction);
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Integer> reply = neverFinish.call(caller, memory, graphCall, observer);
        assertTrue(reply.supportsCustomCancelAction());
        reply.triggerCancelSignal();

        verify(cancelAction).run(false);
    }

    @Test
    public void modifiesCustomCancelActionToSuppressAndAddExceptionsToGraphCall() {
        Integer normalResponse = 34;
        Error error = new Error();
        CustomCancelAction cancelAction = mayInterrupt -> {
            throw error;
        };
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        Node<TestMemory, Integer> neverFinish = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        CompletableFuture<Integer> waitAndGetNormal = CompletableFuture.supplyAsync(() -> {
                            Try.runUnchecked(() -> cancellationFinished.await(5, TimeUnit.SECONDS));
                            return normalResponse;
                        });
                        return new CustomCancelActionBehaviorResponse<>(waitAndGetNormal, cancelAction);
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });

        Reply<Integer> reply = neverFinish.call(caller, memory, graphCall, observer);
        assertTrue(reply.supportsCustomCancelAction());
        reply.triggerCancelSignal();
        cancellationFinished.countDown();

        assertThat(reply.join(), is(normalResponse));
        ArgumentCaptor<CustomCancelActionException> exceptionCaptor = ArgumentCaptor
                .forClass(CustomCancelActionException.class);
        verify(graphCall).addUnhandledException(exceptionCaptor.capture());
        CustomCancelActionException exception = exceptionCaptor.getValue();
        assertThat(exception.getCause(), is(error));
        assertThat(exception.getReply(), is(reply));
        assertThat(exception.getMemory(), is(memory));
    }

    @Test
    public void doesntModifyInterruptsForNonInterruptSupportingCustomCancelActionBehaviors() {
        AtomicBoolean interruptedDuringDependencyCall = new AtomicBoolean();
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> {
                    interruptedDuringDependencyCall.set(Thread.currentThread().isInterrupted());
                    return CompletableFuture.completedFuture(15);
                });
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder
                .sameMemoryUnprimedDependency(dependency);
        AtomicBoolean interruptAfterDependencyCall = new AtomicBoolean();
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        Thread.currentThread().interrupt();
                        device.call(consumeDependency);
                        interruptAfterDependencyCall.set(Thread.currentThread().isInterrupted());
                        return new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(14),
                                doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        MemoryScope scope = memoryScopeForSingleNodeGraph(node);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(55));

        node.call(caller, memory, graphCall, observer);

        assertTrue(interruptedDuringDependencyCall.get());
        assertTrue(interruptAfterDependencyCall.get());
        assertTrue(Thread.currentThread().isInterrupted()); // Value after node finishes
    }

    @Test
    public void isolatesInterruptsForInterruptSupportingCustomCancelActionBehaviors() {
        AtomicBoolean interruptedDuringDependencyCall = new AtomicBoolean();
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> {
                    interruptedDuringDependencyCall.set(Thread.currentThread().isInterrupted());
                    return CompletableFuture.completedFuture(15);
                });
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder
                .sameMemoryUnprimedDependency(dependency);
        AtomicBoolean interruptAfterDependencyCall = new AtomicBoolean();
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        Thread.currentThread().interrupt();
                        device.call(consumeDependency);
                        interruptAfterDependencyCall.set(Thread.currentThread().isInterrupted());
                        return new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(14),
                                doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return true;
                    }
                });
        MemoryScope scope = memoryScopeForSingleNodeGraph(node);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(55));

        node.call(caller, memory, graphCall, observer);

        assertFalse(interruptedDuringDependencyCall.get());
        assertTrue(interruptAfterDependencyCall.get());
        assertFalse(Thread.currentThread().isInterrupted()); // Value after node finishes
    }

    @Test
    public void suppressesInterruptClearingExceptionsForInterruptSupportingCustomCancelActionBehaviors() {
        Error error = new Error();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(14),
                                doNothingCancelAction());
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return true;
                    }

                    @Override
                    public boolean clearCurrentThreadInterrupt() {
                        throw error;
                    }
                });
        MemoryScope scope = memoryScopeForSingleNodeGraph(node);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(55));

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        ArgumentCaptor<InterruptClearingException> exceptionCaptor = ArgumentCaptor
                .forClass(InterruptClearingException.class);
        verify(graphCall).addUnhandledException(exceptionCaptor.capture());
        InterruptClearingException exception = exceptionCaptor.getValue();
        assertThat(exception.getCause(), is(error));
        assertThat(exception.getReply(), is(reply));
        assertThat(exception.getMemory(), is(memory));
    }

    @Test
    public void isolatesInterruptsForCustomCancelActionsAndDependencyCalls() {
        // Multi-threaded code is non-deterministic; so try multiple times. This chosen value isn't perfect in detecting
        // purposefully-introduced bugs, but it's a nice balance of being quick enough to run as a unit test every time.
        final int NUM_TRIES = 500;
        IntStream.range(0, NUM_TRIES)
                .forEach(this::tryOneToVerifyIsolatesInterruptsForCustomCancelActionsAndDependencyCalls);
    }

    private void tryOneToVerifyIsolatesInterruptsForCustomCancelActionsAndDependencyCalls(int trialNum) {
        // Create a dependency to measure if its current thread is interrupted
        AtomicBoolean interruptedDuringDependencyCall = new AtomicBoolean();
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> {
                    interruptedDuringDependencyCall.set(Thread.currentThread().isInterrupted());
                    return CompletableFuture.completedFuture(15);
                });

        // Create a node that will...
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder
                .sameMemoryUnprimedDependency(dependency);
        CountDownLatch beginDependencyCall = new CountDownLatch(1);
        CountDownLatch dependencyCallThreadSet = new CountDownLatch(1);
        AtomicReference<Thread> dependencyCallThread = new AtomicReference<>();
        CompletableFuture<Integer> nodeResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        CompletableFuture.runAsync(() -> {
                            dependencyCallThread.set(Thread.currentThread());
                            dependencyCallThreadSet.countDown();
                            // ...asynchronously wait and then...
                            awaitIgnoringOneInterrupt(beginDependencyCall, 5, TimeUnit.SECONDS);
                            // ...call the dependency and then...
                            device.call(consumeDependency);
                            // ... complete the node response to make sure that the current Thread completes the Reply
                            // which will give us an additional measure that any interrupt is cleared afterwards...
                            nodeResponse.complete(15);
                        });
                        // ... while also creating a cancel action that will interrupt the dependency calling thread...
                        return new CustomCancelActionBehaviorResponse<>(nodeResponse, mayInterrupt -> {
                            dependencyCallThread.get().interrupt();
                        });
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return true;
                    }
                });

        MemoryScope scope = memoryScopeForSingleNodeGraph(node);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(55));

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);
        CompletionStage<Boolean> interruptSetAfterReply = reply
                .thenApply(integer -> Thread.currentThread().isInterrupted());
        Try.runUnchecked(() -> dependencyCallThreadSet.await(5, TimeUnit.SECONDS));
        // ... thereby setting up a race condition between the dependency call and the cancel action interrupting
        new ConcurrentLoadGenerator(List.of(beginDependencyCall::countDown, reply::triggerCancelSignal)).run();

        assertFalse(interruptedDuringDependencyCall.get());
        assertFalse(interruptSetAfterReply.toCompletableFuture().join());
    }

    /**
     * Call await on the latch, ignoring any one interrupt set while ignoring. This is necessary for the previous
     * method, in case the cancel action sends the interrupt while the latch is waiting. In that case, we should start
     * waiting again and then set the Thread's interrupt status to restore it. This method is nowhere near perfect, but
     * it's good enough to support the previous method.
     */
    private void awaitIgnoringOneInterrupt(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException e) {
            try {
                latch.await(timeout, unit);
            } catch (InterruptedException e1) {
            }
            Thread.currentThread().interrupt();
        }
    }
}
