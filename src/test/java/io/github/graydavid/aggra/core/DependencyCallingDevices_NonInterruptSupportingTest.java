package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.TestData.nodeBackedBy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestGrandchildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the non-interrupt-supporting DependencyCallingDevices in {@link DependencyCallingDevices}. You can find other
 * DependencyCallingDevices-related tests under DependencyCallingDevices_*Test classes.
 */
public class DependencyCallingDevices_NonInterruptSupportingTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Storage storage = new ConcurrentHashMapStorage();
    private final TestMemory memory = new TestMemory(passiveHooksRootMemoryScope(),
            CompletableFuture.completedFuture(55), () -> storage);
    private final GraphCall<?> graphCall = mock(GraphCall.class);
    private final Observer observer = new Observer() {};

    private static MemoryScope passiveHooksRootMemoryScope() {
        Node<TestMemory, Integer> nonResponsive = TestData.nodeReturningValue(5);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(nonResponsive));
        return MemoryScope.createForGraph(graph);
    }

    @Test
    public void throwsExceptionWhenNodeTriesToCallUnmodeledSameMemoryDependency() {
        Node<TestMemory, Integer> unmodeledDependencyNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependencyNode"))
                .role(Role.of("dependencyNode"))
                .build(device -> CompletableFuture.completedFuture(null));
        SameMemoryDependency<TestMemory, Integer> modeledElsewhere = Node.communalBuilder(TestMemory.class)
                .primedDependency(unmodeledDependencyNode);
        Node<TestMemory, Integer> callingNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("mainNode"))
                .role(Role.of("mainNode"))
                .build(device -> device.call(modeledElsewhere));

        Throwable thrown = assertThrows(Throwable.class,
                () -> callingNode.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callingNode));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), allOf(containsString(unmodeledDependencyNode.getSynopsis()),
                containsString(callingNode.getSynopsis()), containsString("without modeling")));
    }

    @Test
    public void allowsCallOfSameMemoryDependencyNodes() {
        Node<TestMemory, Integer> dependencyToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependencyToPrime"))
                .build(device -> CompletableFuture.completedFuture(2));
        Node<TestMemory, Integer> dependencyNotToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependencyNotToPrime"))
                .build(device -> CompletableFuture.completedFuture(3));
        Node.CommunalBuilder<TestMemory> addDependenciesBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        SameMemoryDependency<TestMemory, Integer> consumePrime = addDependenciesBuilder
                .primedDependency(dependencyToPrime);
        SameMemoryDependency<TestMemory, Integer> consumeUnprime = addDependenciesBuilder
                .primedDependency(dependencyNotToPrime);
        Node<TestMemory, Integer> addDependencies = addDependenciesBuilder.build(device -> {
            Integer waitForResult = device.call(consumePrime).join();
            Integer dontWaitForResult = device.call(consumeUnprime).join();
            return CompletableFuture.completedFuture(waitForResult + dontWaitForResult);
        });

        Reply<Integer> sum = addDependencies.call(caller, memory, graphCall, observer);

        assertThat(sum.join(), is(2 + 3));
    }

    @Test
    public void canUseAllDependenciesCreatedForSameNode() {
        Node<TestMemory, Integer> dependencyNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependencyToPrime"))
                .build(device -> CompletableFuture.completedFuture(2));
        Node.CommunalBuilder<TestMemory> consumeDependenciesBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        SameMemoryDependency<TestMemory, Integer> consumeDependency = consumeDependenciesBuilder
                .primedDependency(dependencyNode);
        SameMemoryDependency<TestMemory, Integer> consumeDependencyAgain = consumeDependenciesBuilder
                .primedDependency(dependencyNode);
        SameMemoryDependency<TestMemory, Integer> consumeDependencyYetAgain = consumeDependenciesBuilder
                .primedDependency(dependencyNode);
        Node<TestMemory, Integer> addDependencies = consumeDependenciesBuilder.build(device -> {
            Integer first = device.call(consumeDependency).join();
            Integer second = device.call(consumeDependencyAgain).join();
            Integer third = device.call(consumeDependencyYetAgain).join();
            return CompletableFuture.completedFuture(first + second + third);
        });

        Reply<Integer> sum = addDependencies.call(caller, memory, graphCall, observer);

        assertThat(sum.join(), is(2 + 2 + 2));
    }

    @Test
    public void callsDependenciesWithConsumerNodeAsCallerAndPropagatedGraphCallAndObserver() {
        Node<TestMemory, ?> dependencyNotToPrime = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("consumer"))
                .role(Role.of("consumer"));
        SameMemoryDependency<TestMemory, ?> consumeNotToPrime = consumerBuilder
                .sameMemoryUnprimedDependency(dependencyNotToPrime);
        Node<TestMemory, ?> consumer = consumerBuilder.build(device -> {
            device.call(consumeNotToPrime);
            return CompletableFuture.completedFuture(null);
        });

        consumer.call(caller, memory, graphCall, observer);

        verify(dependencyNotToPrime).call(consumer, memory, graphCall, observer);
    }

    @Test
    public void throwsExceptionWhenNodeTriesToCallUnmodeledSameMemoryDependencyWithExecutorVariant() {
        Node<TestMemory, Integer> unmodeledDependencyNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependencyNode"))
                .role(Role.of("dependencyNode"))
                .build(device -> CompletableFuture.completedFuture(null));
        SameMemoryDependency<TestMemory, Integer> modeledElsewhere = Node.communalBuilder(TestMemory.class)
                .sameMemoryUnprimedDependency(unmodeledDependencyNode);
        Node<TestMemory, Integer> callingNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("mainNode"))
                .role(Role.of("mainNode"))
                .build(device -> device.call(modeledElsewhere, Runnable::run));

        Throwable thrown = assertThrows(Throwable.class,
                () -> callingNode.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callingNode));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), allOf(containsString(unmodeledDependencyNode.getSynopsis()),
                containsString(callingNode.getSynopsis()), containsString("without modeling")));
    }

    @Test
    public void allowsCallOfSameMemoryDependencyNodesWithExecutorVariant() {
        Node<TestMemory, Integer> dependencyToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependencyToPrime"))
                .build(device -> CompletableFuture.completedFuture(2));
        Node<TestMemory, Integer> dependencyNotToPrime = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependencyNotToPrime"))
                .build(device -> CompletableFuture.completedFuture(3));
        Node.CommunalBuilder<TestMemory> addDependenciesBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        SameMemoryDependency<TestMemory, Integer> consumePrime = addDependenciesBuilder
                .primedDependency(dependencyToPrime);
        SameMemoryDependency<TestMemory, Integer> consumeUnprime = addDependenciesBuilder
                .primedDependency(dependencyNotToPrime);
        Node<TestMemory, Integer> addDependencies = addDependenciesBuilder.build(device -> {
            Integer waitForResult = device.call(consumePrime, Runnable::run).join();
            Integer dontWaitForResult = device.call(consumeUnprime, Runnable::run).join();
            return CompletableFuture.completedFuture(waitForResult + dontWaitForResult);
        });

        Reply<Integer> sum = addDependencies.call(caller, memory, graphCall, observer);

        assertThat(sum.join(), is(2 + 3));
    }

    @Test
    public void usesProvidedFirstCallExecutorInExecutorSpecifyingSameMemoryDependencyCallVariant() {
        Node<TestMemory, ?> dependencyNotToPrime = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, ?> consumeNotToPrime = consumerBuilder
                .sameMemoryUnprimedDependency(dependencyNotToPrime);
        Executor firstCallExecutor = mock(Executor.class);
        Node<TestMemory, ?> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .build(device -> {
                    device.call(consumeNotToPrime, firstCallExecutor);
                    return CompletableFuture.completedFuture(null);
                });

        consumer.call(caller, memory, graphCall, observer);

        verify(dependencyNotToPrime).call(consumer, memory, graphCall, observer, firstCallExecutor);
    }

    @Test
    public void throwsExceptionWhenNodeTriesToCallUnmodeledDependencyInNewMemory() {
        Node<TestChildMemory, Integer> unmodeledChildDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("unmodeled"))
                .role(Role.of("unmodeled"))
                .build(device -> CompletableFuture.completedFuture(null));
        NewMemoryDependency<TestChildMemory, Integer> modeledElsewhere = Node.communalBuilder(TestMemory.class)
                .newMemoryDependency(unmodeledChildDependency);
        Node<TestMemory, Integer> callChildDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> {
                    return device.createMemoryAndCall(TestChildMemory::new, CompletableFuture.completedFuture(11),
                            modeledElsewhere);
                });

        Throwable thrown = assertThrows(Throwable.class,
                () -> callChildDependency.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callChildDependency));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), allOf(containsString(unmodeledChildDependency.getSynopsis()),
                containsString(callChildDependency.getSynopsis()), containsString("without modeling")));
    }

    @Test
    public void throwsExceptionWhenMemoryFactoryReturnsMemoryWithDifferentInputThanProvided() {
        CompletableFuture<Integer> childInputProvidedToFactory = CompletableFuture.completedFuture(11);
        CompletableFuture<Integer> differentChildInput = CompletableFuture.completedFuture(29);
        Node<TestChildMemory, Integer> childDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("child"))
                .role(Role.of("child"))
                .build(device -> CompletableFuture.completedFuture(null));
        Node.CommunalBuilder<TestMemory> callChildDependencyBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        NewMemoryDependency<TestChildMemory, Integer> consumeChild = callChildDependencyBuilder
                .newMemoryDependency(childDependency);
        Node<TestMemory, Integer> callChildDependency = callChildDependencyBuilder.build(device -> {
            // javac has trouble inferring type variables
            return device.<Integer, TestChildMemory, Integer>createMemoryAndCall(
                    (scope, ignoreChildInput, parent) -> new TestChildMemory(scope, differentChildInput, memory),
                    childInputProvidedToFactory, consumeChild);
        });

        Throwable thrown = assertThrows(Throwable.class,
                () -> callChildDependency.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callChildDependency));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), containsString("Expected Memory with input"));
    }

    @Test
    public void throwsExceptionWhenMemoryFactoryReturnsMemoryWithDifferentScopeThanProvided() {
        MemoryScope differentChildScope = mock(MemoryScope.class);
        Node<TestChildMemory, Integer> childDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("child"))
                .role(Role.of("child"))
                .build(device -> CompletableFuture.completedFuture(null));
        Node.CommunalBuilder<TestMemory> callChildDependencyBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        NewMemoryDependency<TestChildMemory, Integer> consumeChild = callChildDependencyBuilder
                .newMemoryDependency(childDependency);
        Node<TestMemory, Integer> callChildDependency = callChildDependencyBuilder.build(device -> {
            // javac has trouble inferring type variables
            return device.<Integer, TestChildMemory, Integer>createMemoryAndCall(
                    (ignoreScope, childInput, parent) -> new TestChildMemory(differentChildScope, childInput, memory),
                    CompletableFuture.completedFuture(11), consumeChild);
        });

        Throwable thrown = assertThrows(Throwable.class,
                () -> callChildDependency.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callChildDependency));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(),
                allOf(containsString("Expected Memory"), containsString("to have scope")));
    }

    @Test
    public void createsMemoryScopeBasedOnNodeInNew() {
        Node<TestChildMemory, Integer> childDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("child"))
                .role(Role.of("child"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(55));
        Node.CommunalBuilder<TestMemory> callChildDependencyBuilder = Node.communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Integer> consumeChild = callChildDependencyBuilder
                .newMemoryDependency(childDependency);
        Node<TestMemory, Integer> callChildDependency = callChildDependencyBuilder.type(Type.generic("main"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // javac has trouble inferring type variables
                    return device
                            .<Integer, TestChildMemory, Integer>createMemoryAndCall((scope, childInput, parent) -> {
                                // This is white-box testing MemoryScope. Because the childNode is NODE_FOR_ALL, the
                                // MemoryScope
                                // should be nonresponsive to cancel signals. If it were created from callChildNode
                                // instead,
                                // which is GRAPH, then it would be responsive, and the test would fail.
                                assertFalse(scope.isResponsiveToCancelSignal());
                                return new TestChildMemory(scope, childInput, memory);
                            }, CompletableFuture.completedFuture(11), consumeChild);
                });

        Reply<Integer> reply = callChildDependency.call(caller, memory, graphCall, observer);

        assertThat(reply.join(), is(55));
    }

    @Test
    public void cancelsMemoryScopeWhenNodeInNewIsCompleteForResponsiveToCancelScopes() {
        // Create a transitive waiting dependency in a child memory. This will force the direct waiting dependency in
        // the child memory to have a responsive MemoryScope.
        CompletableFuture<Integer> waitingTransitiveResponse = new CompletableFuture<>();
        Node<TestChildMemory, Integer> waitingTransitive = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("waiting-transitive"))
                .role(Role.of("waiting-transitive"))
                .build(device -> waitingTransitiveResponse);

        // Create a direct waiting dependency in the child memory. This will allow us to control when the scope
        // cancellation should take place.
        CompletableFuture<Integer> waitingDirectResponse = new CompletableFuture<>();
        Node.CommunalBuilder<TestChildMemory> waitingDirectBuilder = Node.communalBuilder(TestChildMemory.class);
        SameMemoryDependency<TestChildMemory, Integer> consumeWaitingTransitive = waitingDirectBuilder
                .sameMemoryUnprimedDependency(waitingTransitive);
        Node<TestChildMemory, Integer> waitingDirect = waitingDirectBuilder.type(Type.generic("waiting-direct"))
                .role(Role.of("waiting-direct"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call waiting transitive, but don't wait for it
                    device.call(consumeWaitingTransitive);
                    return waitingDirectResponse;
                });

        // Create a calling node in the parent memory. This will allow us to create child memories and test them.
        Node.CommunalBuilder<TestMemory> callWaitingBuilder = Node.communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Integer> consumeWaiting = callWaitingBuilder
                .newMemoryDependency(waitingDirect);
        AtomicReference<TestChildMemory> childMemory = new AtomicReference<>();
        Node<TestMemory, Integer> callWaiting = callWaitingBuilder.type(Type.generic("main"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // call waiting-direct, but don't wait on it...
                    // (Note: javac has trouble inferring type variables)
                    device.<Integer, TestChildMemory, Integer>createMemoryAndCall((scope, input, parent) -> {
                        childMemory.set(new TestChildMemory(scope, input, parent));
                        return childMemory.get();
                    }, CompletableFuture.completedFuture(11), consumeWaiting);

                    // ... instead return something already complete
                    return CompletableFuture.completedFuture(55);
                });

        Reply<Integer> reply = callWaiting.call(caller, memory, graphCall, observer);

        // Verify: main reply is done, because it doesn't wait for "waitingDirect"...
        assertTrue(reply.isDone());
        assertThat(reply.join(), is(55));
        // ... and MemoryScope is responsive to cancel, because waiting-direct won't wait for waiting-transitive...
        assertTrue(childMemory.get().getScope().isResponsiveToCancelSignal());
        // ... but even though it's responsive, it's still not cancelled, because waiting-direct hasn't finished
        assertFalse(childMemory.get().getScope().isCancelSignalTriggered());

        // When we complete waitingDirect (and make it complete exceptionally, to test the most extreme case), that
        // should trigger the cancellation of scope, even though waitingTransitive still isn't done.
        waitingDirectResponse.completeExceptionally(new IllegalArgumentException());
        assertTrue(childMemory.get().getScope().isCancelSignalTriggered());
    }

    @Test
    public void addsActiveHookSupportingMemoryScopesForNewMemorysToGraphCallForTracking() {
        AtomicBoolean childCancelActionCalled = new AtomicBoolean(false);
        Node<TestChildMemory, Integer> neverFinishChild = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestChildMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(
                            DependencyCallingDevice<TestChildMemory> device, CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(new CompletableFuture<>(), mayInterrupt -> {
                            childCancelActionCalled.set(true);
                        });
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        Node.CommunalBuilder<TestMemory> callChildBuilder = Node.communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Integer> consumeChild = callChildBuilder
                .newMemoryDependency(neverFinishChild);
        Node<TestMemory, Integer> callChild = callChildBuilder.type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> {
                    // call waiting-direct, but don't wait on it...
                    // (Note: javac has trouble inferring type variables)
                    device.<Integer, TestChildMemory, Integer>createMemoryAndCall(TestChildMemory::new,
                            CompletableFuture.completedFuture(11), consumeChild);

                    // And return an incomplete future; otherwise, the child will get ignored and cancelled early
                    return new CompletableFuture<>();
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(callChild));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(
                scope -> new TestMemory(scope, CompletableFuture.completedFuture(56)), Observer.doNothing());

        graphCall.call(callChild);

        assertFalse(childCancelActionCalled.get());
        graphCall.triggerCancelSignal();

        assertTrue(childCancelActionCalled.get());
    }

    @Test
    public void allowsCreationOfNewMemorysAndCallsThere() {
        Node<TestChildMemory, Integer> childDependency = Node.inputBuilder(TestChildMemory.class)
                .role(Role.of("child-dependency"))
                .build();
        Node.CommunalBuilder<TestMemory> callChildDependencyBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"));
        NewMemoryDependency<TestChildMemory, Integer> consumeChild = callChildDependencyBuilder
                .newMemoryDependency(childDependency);
        Node<TestMemory, Integer> callChildDependency = callChildDependencyBuilder.build(device -> {
            Integer firstCall = device
                    .createMemoryAndCall(TestChildMemory::new, CompletableFuture.completedFuture(10), consumeChild)
                    .join();
            Integer secondCall = device
                    .createMemoryAndCall(TestChildMemory::new, CompletableFuture.completedFuture(12), consumeChild)
                    .join();
            return CompletableFuture.completedFuture(firstCall + secondCall);

        });

        Reply<Integer> subResult = callChildDependency.call(caller, memory, graphCall, observer);

        assertThat(subResult.join(), is(10 + 12));
    }

    @Test
    public void createsMemoryAndCallsDependenciesWithConsumerNodeAsCallerAndPropagatedAndObserver() {
        Node<TestChildMemory, ?> childDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("child"))
                .role(Role.of("child"))
                .build(device -> CompletableFuture.completedFuture(15));
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("consumer"))
                .role(Role.of("consumer"));
        NewMemoryDependency<TestChildMemory, ?> consumeChild = consumerBuilder.newMemoryDependency(childDependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> {
            device.createMemoryAndCall(TestChildMemory::new, CompletableFuture.completedFuture(45), consumeChild);
            return CompletableFuture.completedFuture(null);
        });
        // Mockito has problems spying on childDependency (NullPointerException, likely due to Node's final nature), so
        // let's use ObservedCalls instead
        Set<ObservedCall> observedCalls = new HashSet<>();
        Observer observer = observerObservingEveryCallIn(observedCalls);

        consumer.call(caller, memory, graphCall, observer).join();

        ObservedCall observedChildCall = observedCalls.stream()
                .filter(observed -> observed.node == childDependency)
                .findFirst()
                .get();
        assertThat(observedChildCall.caller, is(consumer));
    }

    private static Observer observerObservingEveryCallIn(Set<ObservedCall> observedCalls) {
        ObserverBeforeStart<Reply<?>> everyCallObserver = (type, caller, node, memory) -> {
            observedCalls.add(new ObservedCall(caller, node));
            return ObserverAfterStop.doNothing();
        };
        return Observer.builder().observerBeforeEveryCall(everyCallObserver).build();
    }

    private static class ObservedCall {
        private final Caller caller;
        private final Node<?, ?> node;

        public ObservedCall(Caller caller, Node<?, ?> node) {
            this.caller = caller;
            this.node = node;
        }
    }

    @Test
    public void throwsExceptionWhenNodeTriesToCallUnmodeledDependencyInAncestorMemory() {
        Node<TestMemory, Integer> unmodeledParentNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("unmodeled"))
                .role(Role.of("unmodeled"))
                .build(device -> CompletableFuture.completedFuture(null));
        AncestorMemoryDependency<TestMemory, Integer> modeledElsewhere = Node.communalBuilder(TestChildMemory.class)
                .ancestorMemoryDependency(unmodeledParentNode);
        Node<TestChildMemory, Integer> callParentNode = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> {
                    return device.accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, modeledElsewhere);
                });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(11), memory);

        Throwable thrown = assertThrows(Throwable.class,
                () -> callParentNode.call(caller, childMemory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(callParentNode));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), allOf(containsString(unmodeledParentNode.getSynopsis()),
                containsString(callParentNode.getSynopsis()), containsString("without modeling")));
    }

    @Test
    public void allowsCallToNodeInParentAncestorMemory() {
        Node<TestMemory, Integer> parentNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(2));
        Node.CommunalBuilder<TestChildMemory> callNodeInParentBuilder = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"));
        AncestorMemoryDependency<TestMemory, Integer> consumeParent = callNodeInParentBuilder
                .ancestorMemoryDependency(parentNode);
        Node<TestChildMemory, Integer> callNodeInParent = callNodeInParentBuilder.build(device -> {
            return device.accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, consumeParent);
        });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(11), memory);

        Reply<Integer> subResult = callNodeInParent.call(caller, childMemory, graphCall, observer);

        assertThat(subResult.join(), is(2));
    }

    @Test
    public void allowsCallToNodeInGrandparentAncestorMemory() {
        Node<TestMemory, Integer> grandparentNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(2));
        Node.CommunalBuilder<TestGrandchildMemory> callNodeInGrandparentBuilder = Node
                .communalBuilder(TestGrandchildMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"));
        AncestorMemoryDependency<TestMemory, Integer> consumeGrapndparent = callNodeInGrandparentBuilder
                .ancestorMemoryDependency(grandparentNode);
        Node<TestGrandchildMemory, Integer> callNodeInGrandparent = callNodeInGrandparentBuilder.build(device -> {
            return device.accessAncestorMemoryAndCall(gc -> gc.getTestChildMemory().getTestMemory(),
                    consumeGrapndparent);
        });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(11), memory);
        TestGrandchildMemory grandchildMemory = new TestGrandchildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(12), childMemory);

        Reply<Integer> subResult = callNodeInGrandparent.call(caller, grandchildMemory, graphCall, observer);

        assertThat(subResult.join(), is(2));
    }

    @Test
    public void accessesAncestorMemoryAndCallsDependenciesWithConsumerNodeAsCallerAndPropagatedGraphCallAndObserver() {
        Node<TestMemory, ?> parentDependency = NodeMocks.node();
        Node.CommunalBuilder<TestChildMemory> consumerBuilder = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("consumer"))
                .role(Role.of("consumer"));
        AncestorMemoryDependency<TestMemory, ?> consumeParent = consumerBuilder
                .ancestorMemoryDependency(parentDependency);
        Node<TestChildMemory, Void> consumer = consumerBuilder.build(device -> {
            device.accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, consumeParent);
            return CompletableFuture.completedFuture(null);
        });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(55), memory);

        consumer.call(caller, childMemory, graphCall, observer);

        verify(parentDependency).call(Mockito.eq(consumer), any(TestMemory.class), eq(graphCall), eq(observer));
    }

    @Test
    public void throwsExceptionWhenNodeTriesToIgnoreUnmodeledDependencyReply() {
        Node<TestMemory, Integer> unmodeledNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("unmodeled"))
                .role(Role.of("unmodeled"))
                .build(device -> CompletableFuture.completedFuture(null));
        Reply<Integer> unmodeledReply = unmodeledNode.call(caller, memory, graphCall, observer);
        Node<TestMemory, Integer> ignoreUnmodeled = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> {
                    device.ignore(unmodeledReply);
                    return CompletableFuture.completedFuture(null);
                });

        Throwable thrown = assertThrows(Throwable.class,
                () -> ignoreUnmodeled.call(caller, memory, graphCall, observer).join());

        assertThat(thrown.getCause(), instanceOf(CallException.class));
        CallException callException = (CallException) thrown.getCause();
        assertThat(callException.getCaller(), is(caller));
        assertThat(callException.getFailedNode(), is(ignoreUnmodeled));
        assertThat(thrown.getCause().getCause(), instanceOf(MisbehaviorException.class));
        MisbehaviorException misbehaviorException = (MisbehaviorException) thrown.getCause().getCause();
        assertThat(misbehaviorException.getMessage(), allOf(containsString(unmodeledReply.toString()),
                containsString("tried to ignore"), containsString("without first calling it as a dependency")));
    }

    @Test
    public void explicitlyIgnoresIgnoredReplysInGraphCall() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(null));
        Node.CommunalBuilder<TestMemory> ignoreBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = ignoreBuilder
                .sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> ignore = ignoreBuilder.type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> {
                    Reply<Integer> dependencyReply = device.call(consumeDependency);
                    device.ignore(dependencyReply);
                    return CompletableFuture.completedFuture(null);
                });

        ignore.call(caller, memory, graphCall, observer).join();

        Reply<Integer> dependencyReply = dependency.call(caller, memory, graphCall, observer);
        verify(graphCall).explicitlyIgnore(dependencyReply);
        verify(graphCall, never()).implicitlyIgnore(Set.of(dependencyReply));
    }

    @Test
    public void throwsExceptionMakingAnyDependencyCallsOrIgnoresIfDetectsHasBeenClosed() {
        Node<TestMemory, Integer> ancestor = NodeMocks.node();
        Node<TestGrandchildMemory, Integer> child = NodeMocks.node();
        Node<TestChildMemory, Integer> same = NodeMocks.node();
        Node.CommunalBuilder<TestChildMemory> builder = Node.communalBuilder(TestChildMemory.class);
        AncestorMemoryDependency<TestMemory, Integer> consumeAncestor = builder.ancestorMemoryDependency(ancestor);
        NewMemoryDependency<TestGrandchildMemory, Integer> consumeChild = builder.newMemoryDependency(child);
        SameMemoryDependency<TestChildMemory, Integer> consumeSame = builder.sameMemoryUnprimedDependency(same);
        AtomicReference<DependencyCallingDevice<TestChildMemory>> captureChildDevice = new AtomicReference<>();
        Node<TestChildMemory, Integer> node = builder.type(Type.generic("run-device"))
                .role(Role.of("run-device"))
                .build(device -> {
                    captureChildDevice.set(device);
                    return CompletableFuture.completedFuture(55);
                });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(55), memory);

        Reply<Integer> reply = node.call(caller, childMemory, graphCall, observer);
        reply.join();

        assertThrowsClosedExceptionWhileCalling(() -> captureChildDevice.get()
                .accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, consumeAncestor));
        assertThrowsClosedExceptionWhileCalling(() -> captureChildDevice.get()
                .createMemoryAndCall(TestGrandchildMemory::new, CompletableFuture.completedFuture(33), consumeChild));
        assertThrowsClosedExceptionWhileCalling(() -> captureChildDevice.get()
                .createMemoryNoInputAndCall((scope, parent) -> new TestGrandchildMemory(scope,
                        CompletableFuture.completedFuture(33), parent), consumeChild));
        assertThrowsClosedExceptionWhileCalling(() -> captureChildDevice.get().call(consumeSame));
        assertThrowsClosedExceptionWhileCalling(() -> captureChildDevice.get().ignore(reply));
    }

    private void assertThrowsClosedExceptionWhileCalling(Runnable runnable) {
        MisbehaviorException thrown = assertThrows(MisbehaviorException.class, runnable::run);
        assertThat(thrown.getMessage(), containsString("DependencyCallingDevice has been closed"));
    }

    @Test
    public void testThrowsExceptionIfDetectsReclosure() {
        AtomicReference<DependencyCallingDevice<TestMemory>> captureDevice = new AtomicReference<>();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("run-device"))
                .role(Role.of("run-device"))
                .build(device -> {
                    captureDevice.set(device);
                    return CompletableFuture.completedFuture(55);
                });

        node.call(caller, memory, graphCall, observer).join();

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class, () -> captureDevice.get().weaklyClose());
        assertThat(thrown.getMessage(), containsString("A DependencyCallingDevice can only be closed once"));
    }

    @Test
    public void keepsTrackOfAncestorReplies() {
        CompletableFuture<Integer> ancestorResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> ancestor = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("wait-for-outside"))
                .role(Role.of("test-ancestor"))
                .build(device -> ancestorResponse);
        Node.CommunalBuilder<TestChildMemory> builder = Node.communalBuilder(TestChildMemory.class);
        AncestorMemoryDependency<TestMemory, Integer> consumeAncestor = builder.ancestorMemoryDependency(ancestor);
        Node<TestChildMemory, Integer> node = builder.type(Type.generic("call-ancestor"))
                .role(Role.of("call-ancestor"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> {
                    // call ancestor but don't wait for response
                    device.accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, consumeAncestor);
                    return CompletableFuture.completedFuture(30);
                });
        TestChildMemory childMemory = new TestChildMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(55), memory);

        Reply<Integer> reply = node.call(caller, childMemory, graphCall, observer);

        // Reply has the waiting logic inside it, but DependencyCallingDevice tells it what it can wait on
        assertFalse(reply.isDone());
        ancestorResponse.complete(98);
        assertTrue(reply.isDone());
    }

    @Test
    public void keepsTrackOfNewMemoryReplies() {
        CompletableFuture<Integer> newResponse = new CompletableFuture<>();
        Node<TestChildMemory, Integer> newDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("wait-for-outside"))
                .role(Role.of("test-new"))
                .build(device -> newResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Integer> consumeNew = builder.newMemoryDependency(newDependency);
        Node<TestMemory, Integer> node = builder.type(Type.generic("call-new"))
                .role(Role.of("call-new"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> {
                    // call new but don't wait for response
                    device.createMemoryAndCall(TestChildMemory::new, CompletableFuture.completedFuture(54), consumeNew);
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        // Reply has the waiting logic inside it, but DependencyCallingDevice tells it what it can wait on
        assertFalse(reply.isDone());
        newResponse.complete(98);
        assertTrue(reply.isDone());
    }

    @Test
    public void keepsTrackOfNewNoInputMemoryReplies() {
        CompletableFuture<Integer> newResponse = new CompletableFuture<>();
        Node<TestChildMemory, Integer> newDependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("wait-for-outside"))
                .role(Role.of("test-new"))
                .build(device -> newResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Integer> consumeNew = builder.newMemoryDependency(newDependency);
        Node<TestMemory, Integer> node = builder.type(Type.generic("call-new"))
                .role(Role.of("call-new"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> {
                    // call new-no-input but don't wait for response
                    device.createMemoryNoInputAndCall((scope, parent) -> new TestChildMemory(scope,
                            CompletableFuture.completedFuture(54), parent), consumeNew);
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        // Reply has the waiting logic inside it, but DependencyCallingDevice tells it what it can wait on
        assertFalse(reply.isDone());
        newResponse.complete(98);
        assertTrue(reply.isDone());
    }

    @Test
    public void keepsTrackOfSameMemoryUnprimedReplies() {
        CompletableFuture<Integer> sameResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> same = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("wait-for-outside"))
                .role(Role.of("test-same"))
                .build(device -> sameResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeSame = builder.sameMemoryUnprimedDependency(same);
        Node<TestMemory, Integer> node = builder.type(Type.generic("call-same"))
                .role(Role.of("call-same"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> {
                    // call same but don't wait for response
                    device.call(consumeSame);
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        // Reply has the waiting logic inside it, but DependencyCallingDevice tells it what it can wait on
        assertFalse(reply.isDone());
        sameResponse.complete(98);
        assertTrue(reply.isDone());
    }

    @Test
    public void keepsTrackOfSameMemoryPrimedReplies() {
        IllegalStateException sameException = new IllegalStateException();
        Node<TestMemory, Integer> same = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("wait-for-outside"))
                .role(Role.of("test-same"))
                .build(device -> CompletableFuture.failedFuture(sameException));
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        builder.primedDependency(same);
        IllegalArgumentException nodeException = new IllegalArgumentException();
        Node<TestMemory, Integer> node = builder.type(Type.generic("call-same"))
                .role(Role.of("call-same"))
                .exceptionStrategy(ExceptionStrategy.SUPPRESS_DEPENDENCY_FAILURES)
                .build(device -> {
                    throw nodeException;
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);

        // ExceptionStrategy has the addSuppressed logic in it, but DependencyCallingDevice tells it what it
        // can suppress
        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(nodeException)));
        Throwable suppressed[] = reply.getCallExceptionNow().get().getSuppressed();
        assertThat(suppressed.length, is(1));
        Throwable singleSuppressed = suppressed[0];
        assertTrue(Reply.maybeProducedByReply(singleSuppressed));
        assertThat(Reply.getFirstNonContainerException(singleSuppressed), is(Optional.of(sameException)));
    }

    @Test
    public void implicitlyIgnoresNothingWhenClosedGivenNoDependencies() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> {
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> reply = node.call(caller, memory, graphCall, observer);
        reply.join();

        verify(graphCall).implicitlyIgnore(Set.of());
        verify(graphCall, never()).explicitlyIgnore(any());
    }

    @Test
    public void implicitlyIgnoresIncompleteDependenciesWhenClosed() {
        CompletableFuture<Integer> incompleteResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> incomplete = nodeBackedBy(incompleteResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeIncomplete = builder.sameMemoryUnprimedDependency(incomplete);
        Node<TestMemory, Integer> node = builder.type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // call incomplete but don't wait for response
                    device.call(consumeIncomplete);
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> nodeReply = node.call(caller, memory, graphCall, observer);
        nodeReply.join();

        Reply<Integer> incompleteReply = incomplete.call(caller, memory, graphCall, observer);
        verify(graphCall).implicitlyIgnore(Set.of(incompleteReply));
        verify(graphCall, never()).explicitlyIgnore(any());
    }

    @Test
    public void doesntIgnoreCompleteDependenciesWhenClosed() {
        CompletableFuture<Integer> completeResponse = CompletableFuture.completedFuture(14);
        Node<TestMemory, Integer> complete = nodeBackedBy(completeResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeComplete = builder.sameMemoryUnprimedDependency(complete);
        Node<TestMemory, Integer> node = builder.type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // call complete but don't wait for response
                    device.call(consumeComplete);
                    return CompletableFuture.completedFuture(30);
                });

        Reply<Integer> nodeReply = node.call(caller, memory, graphCall, observer);
        nodeReply.join();

        verify(graphCall, times(2)).implicitlyIgnore(Set.of()); // There are two calls: for "complete" and for "node"
        verify(graphCall, never()).explicitlyIgnore(any());
    }

    @Test
    public void finalStateEmptyReturnsEmptyCollections() {
        DependencyCallingDevices.FinalState finalState = DependencyCallingDevices.FinalState.empty();

        assertThat(finalState.getAllDependencyReplies(), empty());
        assertThat(finalState.getIncompleteDependencyReplies(), empty());
        assertThat(finalState.getCompleteDependencyReplies(), empty());
    }

    @Test
    public void finalStateReturnsUnmodifiableCollections() {
        Node<TestMemory, Integer> incompleteNode = nodeBackedBy(new CompletableFuture<>());
        Reply<Integer> incompleteReply = incompleteNode.call(caller, memory, graphCall, observer);
        Node<TestMemory, Integer> completeNode = nodeBackedBy(CompletableFuture.completedFuture(14));
        Reply<Integer> completeReply = completeNode.call(caller, memory, graphCall, observer);
        DependencyCallingDevices.FinalState finalState = DependencyCallingDevices.FinalState
                .of(Set.of(completeReply, incompleteReply));

        assertThrows(UnsupportedOperationException.class,
                () -> finalState.getAllDependencyReplies().add(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> finalState.getIncompleteDependencyReplies().add(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> finalState.getCompleteDependencyReplies().add(NodeMocks.reply()));
    }

    @Test
    public void finalStateReturnsAllIncompleteAndCompleteReplies() {
        Node<TestMemory, Integer> incompleteNode = nodeBackedBy(new CompletableFuture<>());
        Reply<Integer> incompleteReply = incompleteNode.call(caller, memory, graphCall, observer);
        Node<TestMemory, Integer> completeNode = nodeBackedBy(CompletableFuture.completedFuture(14));
        Reply<Integer> completeReply = completeNode.call(caller, memory, graphCall, observer);
        DependencyCallingDevices.FinalState finalState = DependencyCallingDevices.FinalState
                .of(Set.of(completeReply, incompleteReply));

        assertThat(finalState.getAllDependencyReplies(), containsInAnyOrder(completeReply, incompleteReply));
        assertThat(finalState.getIncompleteDependencyReplies(), containsInAnyOrder(incompleteReply));
        assertThat(finalState.getCompleteDependencyReplies(), containsInAnyOrder(completeReply));
    }
}
