package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.CallObservers.ObservationType.BEHAVIOR;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.CUSTOM_CANCEL_ACTION;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.EVERY_CALL;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.FIRST_CALL;
import static io.github.graydavid.aggra.core.NodeMocks.anyDependencyCallingDevice;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the observation logic in {@link Node#call(Caller, Memory)}. You can find other Node-related tests under
 * Node_*Test classes.
 */
public class Node_CallObservationTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Storage storage = new ConcurrentHashMapStorage();
    private final TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(55),
            () -> storage);
    private final GraphCall<?> graphCall = mock(GraphCall.class);

    @Test
    public void allowsFirstCallObservationForCallsThatDontReturnExceptions() {
        // Create an incomplete primed dependency to make sure that observations start before priming
        CompletableFuture<Integer> primedDependencyResponse = new CompletableFuture<>();
        Behavior<TestMemory, Integer> primedDependencyBehavior = NodeMocks.behavior();
        when(primedDependencyBehavior.run(anyDependencyCallingDevice())).thenReturn(primedDependencyResponse);
        Node<TestMemory, Integer> primedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("primed-dependency"))
                .role(Role.of("primed-dependency"))
                .build(primedDependencyBehavior);

        // Create an incomplete primed dependency to make sure that observations stop after dependency lifetime
        CompletableFuture<Integer> unprimedDependencyResponse = new CompletableFuture<>();
        Behavior<TestMemory, Integer> unprimedDependencyBehavior = NodeMocks.behavior();
        when(unprimedDependencyBehavior.run(anyDependencyCallingDevice())).thenReturn(unprimedDependencyResponse);
        Node<TestMemory, Integer> unprimedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("unprimed-dependency"))
                .role(Role.of("unprimed-dependency"))
                .build(unprimedDependencyBehavior);

        // Create a node to invoke both behaviors but waiting on neither
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(primedDependency);
        SameMemoryDependency<TestMemory, Integer> consumeUnprimed = nodeBuilder
                .sameMemoryUnprimedDependency(unprimedDependency);
        CompletableFuture<Integer> behaviorResponse = CompletableFuture.completedFuture(54);
        // Mockito from command line throws NPE with anonymous classes and can't spy on lambdas, so create a proxy
        Runnable behaviorRunProxy = mock(Runnable.class);
        Behavior<TestMemory, Integer> behavior = device -> {
            behaviorRunProxy.run();
            // Call unprimed dependency, but don't wait on it
            device.call(consumeUnprimed);
            return behaviorResponse;
        };
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node")).role(Role.of("node")).build(behavior);

        // Create an observerBeforeFirstCall
        ObserverBeforeStart<Object> observerBeforeFirstCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeFirstCall.observe(any(), any(), any(), any())).thenReturn(NodeMocks.observerAfterStop());
        when(observerBeforeFirstCall.observe(FIRST_CALL, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeFirstCall(observerBeforeFirstCall).build();

        node.call(caller, memory, graphCall, callObserver);

        InOrder inOrder1 = inOrder(primedDependencyBehavior, observerBeforeFirstCall, afterFirstStopObserver);
        inOrder1.verify(observerBeforeFirstCall).observe(FIRST_CALL, caller, node, memory);
        inOrder1.verify(primedDependencyBehavior).run(anyDependencyCallingDevice());
        inOrder1.verify(afterFirstStopObserver, never()).observe(behaviorResponse.join(), null);
        primedDependencyResponse.complete(13);

        InOrder inOrder2 = inOrder(behaviorRunProxy, unprimedDependencyBehavior, afterFirstStopObserver);
        inOrder2.verify(behaviorRunProxy).run();
        inOrder2.verify(unprimedDependencyBehavior).run(anyDependencyCallingDevice());
        inOrder2.verify(afterFirstStopObserver, never()).observe(behaviorResponse.join(), null);
        unprimedDependencyResponse.complete(13);

        verify(afterFirstStopObserver).observe(behaviorResponse.join(), null);
    }

    @Test
    public void allowsFirstCallObservationForCallsThatReturnExceptions() {
        IllegalStateException exception = new IllegalStateException();
        CompletableFuture<Integer> behaviorResponse = CompletableFuture.failedFuture(exception);
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        when(behavior.run(anyDependencyCallingDevice())).thenReturn(behaviorResponse);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(behavior);
        ObserverBeforeStart<Object> observerBeforeFirstCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeFirstCall.observe(FIRST_CALL, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeFirstCall(observerBeforeFirstCall).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), sameInstance(exception));
        InOrder inOrder = inOrder(behavior, observerBeforeFirstCall, afterFirstStopObserver);
        inOrder.verify(observerBeforeFirstCall).observe(FIRST_CALL, caller, node, memory);
        inOrder.verify(behavior).run(anyDependencyCallingDevice());
        inOrder.verify(afterFirstStopObserver).observe(null, exception);
    }

    // Note: We'll rely on Node_CallTest#runsUnderlyingCallMethodsOnlyOnceInOrderWhenExecutedFromMultipleThreads to
    // infer that synchronization issues aren't present here as well, since the first-call observation logic uses the
    // same underlying mechanism to ensure only one call is made
    @Test
    public void observesOnlyFirstCallWithFirstCallObserver() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(54));
        ObserverBeforeStart<Object> observerBeforeEveryCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeEveryCall.observe(FIRST_CALL, caller, node, memory)).thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeFirstCall(observerBeforeEveryCall).build();

        node.call(caller, memory, graphCall, callObserver);
        node.call(caller, memory, graphCall, callObserver);
        node.call(caller, memory, graphCall, callObserver);

        verify(observerBeforeEveryCall, times(1)).observe(FIRST_CALL, caller, node, memory);
    }

    @Test
    public void allowsBehaviorObservationForCallsThatDontReturnExceptions() {
        // Create an incomplete primed dependency to make sure that observations start after priming
        CompletableFuture<Integer> primedDependencyResponse = new CompletableFuture<>();
        Behavior<TestMemory, Integer> primedDependencyBehavior = NodeMocks.behavior();
        when(primedDependencyBehavior.run(anyDependencyCallingDevice())).thenReturn(primedDependencyResponse);
        Node<TestMemory, Integer> primedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("primed-dependency"))
                .role(Role.of("primed-dependency"))
                .build(primedDependencyBehavior);

        // Create an incomplete primed dependency to make sure that observations stop before dependency lifetime
        CompletableFuture<Integer> unprimedDependencyResponse = new CompletableFuture<>();
        Behavior<TestMemory, Integer> unprimedDependencyBehavior = NodeMocks.behavior();
        when(unprimedDependencyBehavior.run(anyDependencyCallingDevice())).thenReturn(unprimedDependencyResponse);
        Node<TestMemory, Integer> unprimedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("unprimed-dependency"))
                .role(Role.of("unprimed-dependency"))
                .build(unprimedDependencyBehavior);

        // Create a node to invoke both behaviors but waiting on neither
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(primedDependency);
        SameMemoryDependency<TestMemory, Integer> consumeUnprimed = nodeBuilder
                .sameMemoryUnprimedDependency(unprimedDependency);
        CompletableFuture<Integer> behaviorResponse = CompletableFuture.completedFuture(54);
        // Mockito from command line throws NPE with anonymous classes and can't spy on lambdas, so create a proxy
        Runnable behaviorRunProxy = mock(Runnable.class);
        Behavior<TestMemory, Integer> behavior = device -> {
            behaviorRunProxy.run();
            // Call unprimed dependency, but don't wait on it
            device.call(consumeUnprimed);
            return behaviorResponse;
        };
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node")).role(Role.of("node")).build(behavior);

        // Create an observerBeforeBehavior
        ObserverBeforeStart<Object> observerBeforeBehavior = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeBehavior.observe(any(), any(), any(), any())).thenReturn(NodeMocks.observerAfterStop());
        when(observerBeforeBehavior.observe(BEHAVIOR, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeBehavior(observerBeforeBehavior).build();

        node.call(caller, memory, graphCall, callObserver);

        InOrder inOrder1 = inOrder(primedDependencyBehavior, observerBeforeBehavior, afterFirstStopObserver);
        inOrder1.verify(primedDependencyBehavior).run(anyDependencyCallingDevice());
        inOrder1.verify(observerBeforeBehavior, never()).observe(BEHAVIOR, caller, node, memory);
        primedDependencyResponse.complete(13);

        InOrder inOrder2 = inOrder(behaviorRunProxy, unprimedDependencyBehavior, observerBeforeBehavior,
                afterFirstStopObserver);
        inOrder2.verify(observerBeforeBehavior).observe(BEHAVIOR, caller, node, memory);
        inOrder2.verify(behaviorRunProxy).run();
        inOrder2.verify(unprimedDependencyBehavior).run(anyDependencyCallingDevice());
        inOrder2.verify(afterFirstStopObserver).observe(behaviorResponse.join(), null);
    }

    @Test
    public void allowsBehaviorObservationForBehaviorsThatReturnExceptions() {
        IllegalStateException exception = new IllegalStateException();
        CompletableFuture<Integer> behaviorResponse = CompletableFuture.failedFuture(exception);
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        when(behavior.run(anyDependencyCallingDevice())).thenReturn(behaviorResponse);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(behavior);
        ObserverBeforeStart<Object> observerBeforeBehavior = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeBehavior.observe(BEHAVIOR, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeBehavior(observerBeforeBehavior).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), sameInstance(exception));
        InOrder inOrder = inOrder(behavior, observerBeforeBehavior, afterFirstStopObserver);
        inOrder.verify(observerBeforeBehavior).observe(BEHAVIOR, caller, node, memory);
        inOrder.verify(behavior).run(anyDependencyCallingDevice());
        inOrder.verify(afterFirstStopObserver).observe(null, exception);
    }

    @Test
    public void allowsBehaviorObservationForBehaviorsThatReturnNull() {
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        when(behavior.run(anyDependencyCallingDevice())).thenReturn(null);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(behavior);
        ObserverBeforeStart<Object> observerBeforeBehavior = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeBehavior.observe(BEHAVIOR, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeBehavior(observerBeforeBehavior).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);

        Throwable encounteredException = reply.getFirstNonContainerExceptionNow().get();
        assertThat(encounteredException, instanceOf(MisbehaviorException.class));
        InOrder inOrder = inOrder(behavior, observerBeforeBehavior, afterFirstStopObserver);
        inOrder.verify(observerBeforeBehavior).observe(BEHAVIOR, caller, node, memory);
        inOrder.verify(behavior).run(anyDependencyCallingDevice());
        inOrder.verify(afterFirstStopObserver).observe(null, encounteredException);
    }

    @Test
    public void doesNoBehaviorObservationWhenBehaviorNotExecuted() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.failedFuture(new Throwable()));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                // This is the key. It makes sure the failed primed dependencies cause behavior not to be run
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));
        ObserverBeforeStart<Object> observerBeforeBehavior = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterFirstStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeBehavior.observe(BEHAVIOR, node, dependency, memory))
                .thenReturn(NodeMocks.observerAfterStop());
        when(observerBeforeBehavior.observe(BEHAVIOR, caller, node, memory)).thenReturn(afterFirstStopObserver);
        Observer callObserver = Observer.builder().observerBeforeBehavior(observerBeforeBehavior).build();

        node.call(caller, memory, graphCall, callObserver);

        verify(observerBeforeBehavior, never()).observe(BEHAVIOR, caller, node, memory);
    }

    @Test
    public void allowsCustomCancelActionsObservationForCallsThatDontThrowExceptions() {
        CustomCancelAction action = mock(CustomCancelAction.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestData.TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(new CompletableFuture<>(), action);
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        ObserverBeforeStart<Object> observerBeforeAction = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeAction.observe(CUSTOM_CANCEL_ACTION, caller, node, memory))
                .thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeCustomCancelAction(observerBeforeAction).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);
        reply.triggerCancelSignal();

        InOrder inOrder = inOrder(action, observerBeforeAction, afterEveryStopObserver);
        inOrder.verify(observerBeforeAction).observe(CUSTOM_CANCEL_ACTION, caller, node, memory);
        inOrder.verify(action).run(false);
        inOrder.verify(afterEveryStopObserver).observe(null, null);
    }

    @Test
    public void allowsCustomCancelActionObservationForCallsThatThrowExceptions() {
        Error error = new Error();
        CustomCancelAction action = mock(CustomCancelAction.class);
        doThrow(error).when(action).run(false);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestData.TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(new CompletableFuture<>(), action);
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        ObserverBeforeStart<Object> observerBeforeAction = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeAction.observe(CUSTOM_CANCEL_ACTION, caller, node, memory))
                .thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeCustomCancelAction(observerBeforeAction).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);

        verifyNoInteractions(observerBeforeAction);
        reply.triggerCancelSignal();

        InOrder inOrder = inOrder(action, observerBeforeAction, afterEveryStopObserver);
        inOrder.verify(observerBeforeAction).observe(CUSTOM_CANCEL_ACTION, caller, node, memory);
        inOrder.verify(action).run(false);
        inOrder.verify(afterEveryStopObserver).observe(null, error);
    }

    @Test
    public void doesNoCustomCancelActionObservationWhenActionNotCalled() {
        CustomCancelAction action = mock(CustomCancelAction.class);
        CompletableFuture<Integer> response = new CompletableFuture<>();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestData.TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(response, action);
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        ObserverBeforeStart<Object> observerBeforeAction = NodeMocks.observerBeforeStart();
        Observer callObserver = Observer.builder().observerBeforeCustomCancelAction(observerBeforeAction).build();

        node.call(caller, memory, graphCall, callObserver);

        verifyNoInteractions(observerBeforeAction);
        response.complete(14);

        verifyNoInteractions(observerBeforeAction);
    }

    @Test
    public void allowsEveryCallObservationForCallsThatDontThrowExceptions() {
        CompletableFuture<Integer> behaviorResponse = CompletableFuture.completedFuture(54);
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        when(behavior.run(anyDependencyCallingDevice())).thenReturn(behaviorResponse);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(behavior);
        ObserverBeforeStart<Reply<?>> observerBeforeEveryCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Reply<?>> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeEveryCall.observe(EVERY_CALL, caller, node, memory)).thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeEveryCall(observerBeforeEveryCall).build();

        Reply<Integer> reply = node.call(caller, memory, graphCall, callObserver);

        InOrder inOrder = inOrder(behavior, observerBeforeEveryCall, afterEveryStopObserver);
        inOrder.verify(observerBeforeEveryCall).observe(EVERY_CALL, caller, node, memory);
        inOrder.verify(behavior).run(anyDependencyCallingDevice());
        ArgumentCaptor<Reply<?>> replyCaptor = ArgumentCaptor.forClass(Reply.class);
        inOrder.verify(afterEveryStopObserver).observe(replyCaptor.capture(), eq(null));
        assertThat(replyCaptor.getValue(), is(reply));
    }

    @Test
    public void allowsEveryCallObservationForCallsThatThrowExceptions() {
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(behavior);
        IllegalStateException exception = new IllegalStateException();
        TestMemory badMemory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(15),
                () -> storageThrowing(exception));
        ObserverBeforeStart<Reply<?>> observerBeforeEveryCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Reply<?>> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeEveryCall.observe(EVERY_CALL, caller, node, badMemory)).thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeEveryCall(observerBeforeEveryCall).build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> node.call(caller, badMemory, graphCall, callObserver));

        assertThat(thrown, sameInstance(exception));
        verifyNoInteractions(behavior);
        InOrder inOrder = inOrder(observerBeforeEveryCall, afterEveryStopObserver);
        inOrder.verify(observerBeforeEveryCall).observe(EVERY_CALL, caller, node, badMemory);
        inOrder.verify(afterEveryStopObserver).observe(null, exception);
    }

    private static Storage storageThrowing(RuntimeException exception) {
        return new Storage() {
            @Override
            public <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
                throw exception;
            }
        };
    }

    @Test
    public void observesEveryCallWithEveryCallObserver() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(54));
        ObserverBeforeStart<Reply<?>> observerBeforeEveryCall = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Reply<?>> afterEveryStopObserver = NodeMocks.observerAfterStop();
        when(observerBeforeEveryCall.observe(EVERY_CALL, caller, node, memory)).thenReturn(afterEveryStopObserver);
        Observer callObserver = Observer.builder().observerBeforeEveryCall(observerBeforeEveryCall).build();

        node.call(caller, memory, graphCall, callObserver);
        node.call(caller, memory, graphCall, callObserver);
        node.call(caller, memory, graphCall, callObserver);

        verify(observerBeforeEveryCall, times(3)).observe(EVERY_CALL, caller, node, memory);
    }
}
