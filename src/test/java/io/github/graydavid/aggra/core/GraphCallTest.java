package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.TestData.arbitraryBehaviorWithCustomCancelAction;
import static io.github.graydavid.aggra.core.TestData.nodeBackedBy;
import static io.github.graydavid.aggra.core.TestData.nodeReturningValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.GraphCall.AbandonedAfterTimeoutReplyException;
import io.github.graydavid.aggra.core.GraphCall.StateAndReplyHandler;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceInputFactory;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

public class GraphCallTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(29);

    @Test
    public void factoryConstructorThrowsExceptionGivenNullArguments() {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of());

        assertThrows(NullPointerException.class,
                () -> GraphCall.Factory.<Integer, TestMemory>from(null, TestMemory::new));
        assertThrows(NullPointerException.class, () -> GraphCall.Factory.from(graph, null));
    }

    @Test
    public void factoryOpenCallCreatesNewMemoryEachTime() {
        Node<TestMemory, Integer> returnNumCalls = nodeReturningNumTimesCalled();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(returnNumCalls));
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);

        assertThat(callFactory.openCancellableCall(graphInput, Observer.doNothing()).call(returnNumCalls).join(),
                is(1));
        assertThat(callFactory.openCancellableCall(29, Observer.doNothing()).call(returnNumCalls).join(), is(2));

        assertThat(callFactory.openCancellableCall(graphInput, Observer.doNothing()).call(returnNumCalls).join(),
                is(3));
        assertThat(callFactory.openCancellableCall(29, Observer.doNothing()).call(returnNumCalls).join(), is(4));
    }

    private static Node<TestMemory, Integer> nodeReturningNumTimesCalled() {
        AtomicInteger numCalls = new AtomicInteger(0);
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("leaf"))
                .build(device -> CompletableFuture.completedFuture(numCalls.incrementAndGet()));
    }

    @Test
    public void factoryOpenCancellableCallCreatesCancellableCall() {
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                // Although declared as GRAPH, the minimum lifetime is NODE_FOR_ALL, which does wait for all
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(55));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);

        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(graphInput, Observer.doNothing());

        assertFalse(graphCall.isCancelSignalTriggered());

        graphCall.triggerCancelSignal();
        assertTrue(graphCall.isCancelSignalTriggered());
    }

    @Test
    public void factoryOpenCancellableCallReusesSameMemoryAcrossCalls() {
        Node<TestMemory, Integer> returnNumCalls = nodeReturningNumTimesCalled();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(returnNumCalls));
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);

        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(graphInput, Observer.doNothing());

        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
    }

    @Test
    public void factoryOpenCancellableCallWithRawInputDelegatesToFutureBasedCall() {
        Node<TestMemory, Integer> node = nodeReturningValue(23);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);

        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(99, Observer.doNothing());

        assertFalse(graphCall.isCancelSignalTriggered());
        assertThat(graphCall.call(node).join(), is(23));
        graphCall.triggerCancelSignal();

        assertTrue(graphCall.isCancelSignalTriggered());
    }

    @Test
    public void factoryOpenCallPassesInputToCreatedMemory() {
        Node<TestMemory, Integer> getInput = Node.inputBuilder(TestMemory.class).role(Role.of("get-input")).build();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(getInput));
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);

        assertThat(callFactory.openCancellableCall(graphInput, Observer.doNothing()).call(getInput).join(),
                is(graphInput.join()));
        assertThat(callFactory.openCancellableCall(graphInput, Observer.doNothing()).call(getInput).join(),
                is(graphInput.join()));
    }

    @Test
    public void factoryOpenCancellableCallThrowsExceptionIfMemoryFactoryProducesMemoryWithDifferentInput() {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of());
        CompletableFuture<Integer> differentInput = CompletableFuture.completedFuture(92);
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                (scope, input) -> new TestMemory(scope, differentInput));

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> callFactory.openCancellableCall(graphInput, Observer.doNothing()));

        assertThat(thrown.getMessage(), containsString("Expected Memory with input"));
    }

    @Test
    public void factoryOpenCancellableCallCallPassesObserverToRootNodeCall() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        // javac has trouble inferring type arguments, so can't use diamond operator on right-hand side
        GraphCall.Factory<Integer, TestMemory> callFactory = GraphCall.Factory.<Integer, TestMemory>from(graph,
                TestMemory::new);
        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(graphInput, observer);

        graphCall.call(soleNode);

        assertThat(calledNodes, contains(soleNode));
    }

    @Test
    public void noInputFactoryConstructorThrowsExceptionGivenNullArguments() {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of());

        assertThrows(NullPointerException.class,
                () -> GraphCall.NoInputFactory.from(null, scope -> new TestMemory(scope, graphInput)));
        assertThrows(NullPointerException.class, () -> GraphCall.NoInputFactory.from(graph, null));
    }

    @Test
    public void noInputFactoryOpenCallCreatesNewMemoryEachTime() {
        Node<TestMemory, Integer> returnNumCalls = nodeReturningNumTimesCalled();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(returnNumCalls));
        GraphCall.NoInputFactory<TestMemory> callFactory = GraphCall.NoInputFactory.from(graph,
                scope -> new TestMemory(scope, graphInput));

        assertThat(callFactory.openCancellableCall(Observer.doNothing()).call(returnNumCalls).join(), is(1));
        assertThat(callFactory.openCancellableCall(Observer.doNothing()).call(returnNumCalls).join(), is(2));
        assertThat(callFactory.openCancellableCall(Observer.doNothing()).call(returnNumCalls).join(), is(3));
        assertThat(callFactory.openCancellableCall(Observer.doNothing()).call(returnNumCalls).join(), is(4));
    }

    @Test
    public void noInputFactoryOpenCancellableCallCreatesCancellableCall() {
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                // Although declared as GRAPH, the minimum lifetime is NODE_FOR_ALL, which does wait for all
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(55));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall.NoInputFactory<TestMemory> callFactory = GraphCall.NoInputFactory.from(graph,
                scope -> new TestMemory(scope, graphInput));

        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(Observer.doNothing());

        assertFalse(graphCall.isCancelSignalTriggered());

        graphCall.triggerCancelSignal();
        assertTrue(graphCall.isCancelSignalTriggered());
    }

    @Test
    public void noInputFactoryOpenCancellableCallCallReusesSameMemoryAcrossCalls() {
        Node<TestMemory, Integer> returnNumCalls = nodeReturningNumTimesCalled();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(returnNumCalls));
        GraphCall.NoInputFactory<TestMemory> callFactory = GraphCall.NoInputFactory.from(graph,
                scope -> new TestMemory(scope, graphInput));
        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(Observer.doNothing());

        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
    }

    @Test
    public void noInputFactoryOpenCancellableCallCallPassesObserverToRootNodeCall() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        GraphCall.NoInputFactory<TestMemory> callFactory = GraphCall.NoInputFactory.from(graph,
                scope -> new TestMemory(scope, graphInput));
        GraphCall<TestMemory> graphCall = callFactory.openCancellableCall(observer);

        graphCall.call(soleNode);

        assertThat(calledNodes, contains(soleNode));
    }

    @Test
    public void createCancellableCallThrowsExceptionWhenMemoryFactoryReturnsMemoryWithDifferentScopeThanProvided() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        MemoryScope differentScope = mock(MemoryScope.class);
        MemoryNoSourceInputFactory<TestMemory> createWithDifferentScope = scope -> new TestMemory(differentScope,
                graphInput);

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> GraphCall.createCancellableCall(graph, createWithDifferentScope, Observer.doNothing()));

        assertThat(thrown.getMessage(), allOf(containsString("Expected Memory"), containsString("to have scope")));
    }

    @Test
    public void createCancellableCallCreatesMemoryScopeBasedOnSingleRootNode() {
        Node<TestMemory, Integer> nonResponsive = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("non-responsive"))
                .role(Role.of("non-responsive"))
                .build(device -> CompletableFuture.completedFuture(55));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(nonResponsive));
        MemoryNoSourceInputFactory<TestMemory> testingFactory = scope -> {
            // This is white-box testing MemoryScope. Because the node is non-responsive to active hooks,
            // the MemoryScope should be as well
            assertTrue(scope.isResponsiveToCancelSignal());
            assertFalse(scope.supportsActiveCancelHooks());
            return new TestMemory(scope, graphInput);
        };

        GraphCall.createCancellableCall(graph, testingFactory, Observer.doNothing());
    }

    @Test
    public void staticFactoriesCreateMemoryScopeBasedOnMultipleRootNodes() {
        Node<TestMemory, Integer> nonResponsive = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("non-responsive"))
                .role(Role.of("non-responsive"))
                .build(device -> CompletableFuture.completedFuture(55));
        Node.CommunalBuilder<TestMemory> activeNodeBuilder = Node.communalBuilder(TestMemory.class);
        activeNodeBuilder.primedDependency(nonResponsive);
        Node<TestMemory, Integer> activeNode = activeNodeBuilder.type(Type.generic("active"))
                .role(Role.of("active"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(nonResponsive, activeNode));
        MemoryNoSourceInputFactory<TestMemory> testingFactory = scope -> {
            // This is white-box testing MemoryScope. Because there is one Node responsive to active hooks,
            // even though one isn't, the MemoryScope should be as well
            assertTrue(scope.isResponsiveToCancelSignal());
            assertTrue(scope.supportsActiveCancelHooks());
            return new TestMemory(scope, graphInput);
        };

        GraphCall.createCancellableCall(graph, testingFactory, Observer.doNothing());
    }

    @Test
    public void createCancellableCallCreatesCancellableCall() {
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                // Although declared as GRAPH, the minimum lifetime is NODE_FOR_ALL, which does wait for all
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(55));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));

        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        assertFalse(graphCall.isCancelSignalTriggered());

        graphCall.triggerCancelSignal();
        assertTrue(graphCall.isCancelSignalTriggered());
    }

    @Test
    public void callThrowsExceptionGivenNonRootNode() {
        Node<TestMemory, Integer> leaf = nodeReturningValue(1);
        Node<TestMemory, Integer> root = nodeAddingOneTo(leaf);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class, () -> graphCall.call(leaf));

        assertThat(thrown.getMessage(), allOf(containsString(leaf.toString()), containsString("without modeling")));
    }

    private static Node<TestMemory, Integer> nodeAddingOneTo(Node<TestMemory, Integer> dependency) {
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("consumer"));
        SameMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder.primedDependency(dependency);
        return nodeBuilder.build(device -> {
            Reply<Integer> dependencyResult = device.call(consumeDependency);
            return CompletableFuture.completedFuture(dependencyResult.join() + 1);
        });
    }

    @Test
    public void callReusesSameMemoryAcrossCalls() {
        Node<TestMemory, Integer> returnNumCalls = nodeReturningNumTimesCalled();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(returnNumCalls));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
        assertThat(graphCall.call(returnNumCalls).join(), is(1));
    }

    @Test
    public void callPassesObserverToRootNodeCall() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                observer);

        graphCall.call(soleNode).join();

        assertThat(calledNodes, contains(soleNode));
    }

    @Test
    public void closureAndAbandonmentStatesIndicateTheirIdentities() {
        Node<TestMemory, Integer> node = nodeReturningValue(43);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        GraphCall.AbandonedState abandonedState = graphCall.abandon();
        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertTrue(abandonedState.isAbandoned());
        assertFalse(abandonedState.isFinal());
        assertFalse(finalState.isAbandoned());
        assertTrue(finalState.isFinal());
    }

    @Test
    public void throwsExceptionWhenUsageDetectedAfterClosure() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(soleNode);

        graphCall.weaklyClose();

        MisbehaviorException callException = assertThrows(MisbehaviorException.class, () -> graphCall.call(soleNode));
        assertThat(callException.getMessage(), containsString("Cannot call a root node once a graph call is closed"));
        MisbehaviorException closeException = assertThrows(MisbehaviorException.class, () -> graphCall.weaklyClose());
        assertThat(closeException.getMessage(), containsString("A graph call can only be closed once"));
        MisbehaviorException finalCallAndCloseException = assertThrows(MisbehaviorException.class,
                () -> graphCall.finalCallAndWeaklyClose(soleNode, mockStateAndReplyHandler()));
        assertThat(finalCallAndCloseException.getMessage(),
                containsString("Cannot call a root node once a graph call is closed"));
        MisbehaviorException closeOrAbandonException = assertThrows(MisbehaviorException.class,
                () -> graphCall.weaklyCloseOrAbandonOnTimeout(1, TimeUnit.MINUTES));
        assertThat(closeOrAbandonException.getMessage(), containsString("A graph call can only be closed once"));
        MisbehaviorException finalCallAndCloseOrAbandonException = assertThrows(MisbehaviorException.class,
                () -> graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(soleNode, 1, TimeUnit.MINUTES,
                        mockStateAndReplyHandler()));
        assertThat(finalCallAndCloseOrAbandonException.getMessage(),
                containsString("Cannot call a root node once a graph call is closed"));
    }

    @Test
    public void closureDoesntCompleteUntilAllRootNodeCallsComplete() {
        CompletableFuture<Integer> node1Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node1 = nodeBackedBy(node1Response);
        CompletableFuture<Integer> node2Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node2 = nodeBackedBy(node2Response);
        CompletableFuture<Integer> node3Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node3 = nodeBackedBy(node3Response);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node1, node2, node3));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        // Only call the first two nodes and then close
        graphCall.call(node1);
        graphCall.call(node2);
        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        node1Response.complete(1);
        assertFalse(finalState.isDone());
        node2Response.complete(2);
        assertTrue(finalState.isDone());
        assertThat(finalState.join().getUnhandledExceptions(), empty());
    }

    @Test
    public void closureCancelsGraphCallWhenRootNodesBackingComplete() {
        // Create a transitive waiting dependency. This will create a gap between root and other node completion.
        CompletableFuture<Integer> waitingDependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> waitingDependency = nodeBackedBy(waitingDependencyResponse);

        // Create a waiting root. This will allow us to control when the scope cancellation should take place.
        CompletableFuture<Integer> waitingRootResponse = new CompletableFuture<>();
        Node.CommunalBuilder<TestMemory> waitingRootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeWaitingDependency = waitingRootBuilder
                .sameMemoryUnprimedDependency(waitingDependency);
        Node<TestMemory, Integer> waitingRoot = waitingRootBuilder.type(Type.generic("waiting-root"))
                .role(Role.of("waiting-root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call waiting dependency, but don't wait for it
                    device.call(consumeWaitingDependency);
                    return waitingRootResponse;
                });

        // Create the GraphCall and make a single Node call.
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(waitingRoot));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(waitingRoot);

        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        // Verify some base assertions
        assertFalse(graphCall.isCancelSignalTriggered());
        assertFalse(reply.isDone());
        assertFalse(finalState.isDone());

        // Completing the root (while leaving the dependency still waiting) should cause the scope to become cancelled
        waitingRootResponse.complete(55);
        assertTrue(graphCall.isCancelSignalTriggered());
        assertThat(reply.join(), is(55));
        assertFalse(finalState.isDone());
    }

    @Test
    public void closureDoesntCompleteUntilAllTransitiveNodeCallsComplete() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveNode = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> rootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = rootBuilder
                .sameMemoryUnprimedDependency(transitiveNode);
        Node<TestMemory, Integer> rootNode = rootBuilder.type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait on the transitive to complete
                    device.call(consumeTransitive);
                    return CompletableFuture.completedFuture(93);
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(rootNode));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        graphCall.call(rootNode);
        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        transitiveResponse.complete(1);
        assertTrue(finalState.isDone());
        assertThat(finalState.join().getUnhandledExceptions(), empty());
    }

    @Test
    public void closureDoesntIncludeNonIgnoredRepliesInFinalStateEvenIfTheyFailed() {
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(new IllegalArgumentException()));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(node);

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getIgnoredReplies(), empty());
        assertThat(finalState.getUnhandledExceptions(), empty());
    }

    @Test
    public void closureIncludesSuccessfulImplicitlyIgnoredRepliesInFinalState() {
        Node<TestMemory, Integer> node = nodeReturningValue(23);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.implicitlyIgnore(List.of(reply));

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getIgnoredReplies(), contains(reply));
        assertThat(finalState.getUnhandledExceptions(), empty());
    }

    @Test
    public void closureIncludesFailedImplicitlyIgnoredRepliesInFinalState() {
        IllegalArgumentException failure = new IllegalArgumentException();
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(failure));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.implicitlyIgnore(List.of(reply));

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getIgnoredReplies(), contains(reply));
        assertThat(finalState.getUnhandledExceptions(), hasSize(1));
        Throwable ignoredFailure = finalState.getUnhandledExceptions().iterator().next();
        assertThat(Reply.getFirstNonContainerException(ignoredFailure), is(Optional.of(failure)));
    }

    @Test
    public void closureIncludesSuccessfulExplicitlyIgnoredRepliesInFinalState() {
        Node<TestMemory, Integer> node = nodeReturningValue(23);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.explicitlyIgnore(reply);

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getIgnoredReplies(), contains(reply));
        assertThat(finalState.getUnhandledExceptions(), empty());
    }

    @Test
    public void closureIncludesFailedExplicitlyIgnoredRepliesInFinalState() {
        IllegalArgumentException failure = new IllegalArgumentException();
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(failure));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.explicitlyIgnore(reply);

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getIgnoredReplies(), contains(reply));
        assertThat(finalState.getUnhandledExceptions(), hasSize(1));
        Throwable ignoredFailure = finalState.getUnhandledExceptions().iterator().next();
        assertThat(Reply.getFirstNonContainerException(ignoredFailure), is(Optional.of(failure)));
    }

    @Test
    public void closureReturnsImmutableFinalState() {
        IllegalArgumentException failure = new IllegalArgumentException();
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(failure));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.implicitlyIgnore(List.of(reply));

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThrows(UnsupportedOperationException.class, () -> finalState.getIgnoredReplies().add(reply));
        assertThrows(UnsupportedOperationException.class,
                () -> finalState.getUnhandledExceptions().add(new Throwable()));
    }

    @Test
    public void suppressesObservationExceptionsAndExposesThemInFinalState() {
        Error beforeFirstFailure = new Error();
        Error beforeEveryFailure = new Error();
        Observer observer = new Observer() {
            @Override
            public ObserverAfterStop<Object> observeBeforeFirstCall(Caller caller, Node<?, ?> node, Memory<?> memory) {
                throw beforeFirstFailure;
            }

            @Override
            public ObserverAfterStop<? super Reply<?>> observeBeforeEveryCall(Caller caller, Node<?, ?> node,
                    Memory<?> memory) {
                throw beforeEveryFailure;
            }
        };
        Node<TestMemory, Integer> node = nodeReturningValue(5);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                observer);

        Reply<Integer> reply = graphCall.call(node);
        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(reply.join(), is(5));
        Collection<Throwable> unhandledCauses = finalState.getUnhandledExceptions()
                .stream()
                .map(Throwable::getCause)
                .collect(Collectors.toList());
        assertThat(unhandledCauses, containsInAnyOrder(beforeFirstFailure, beforeEveryFailure));
    }

    @Test
    public void exposesExplicitlyAddedUnhandledExceptionInFinalState() {
        Node<TestMemory, Integer> node = nodeReturningValue(5);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        IllegalArgumentException failure = new IllegalArgumentException();
        graphCall.addUnhandledException(failure);

        GraphCall.FinalState finalState = graphCall.weaklyClose().join();

        assertThat(finalState.getUnhandledExceptions(), contains(failure));
    }

    @Test
    public void allowsAdditionOfUnhandledExceptionAfterWeaklyCloseCalled() {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        Node<TestMemory, Integer> node = nodeBackedBy(response);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(node);
        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        IllegalArgumentException failure = new IllegalArgumentException();
        graphCall.addUnhandledException(failure);
        response.complete(13);

        assertTrue(finalState.isDone());
        assertThat(finalState.join().getUnhandledExceptions(), contains(failure));
    }

    @Test
    public void finalCallAndWeaklyCloseThrowsExceptionGivenNullHandler() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        assertThrows(NullPointerException.class, () -> graphCall.finalCallAndWeaklyClose(node, null));
    }

    @Test
    public void finalCallAndWeaklyClosePassesFinalStateAndReplyToHandlerAndReturnsReply() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        StateAndReplyHandler<GraphCall.FinalState, Integer> handler = mockStateAndReplyHandler();

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyClose(node, handler);

        assertTrue(handledReply.isDone());
        assertThat(handledReply.join(), is(reply));
        verify(handler).handle(notNull(), isNull(), eq(reply));
    }

    // Suppress justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    private static <S extends GraphCall.State, T> StateAndReplyHandler<S, T> mockStateAndReplyHandler() {
        return mock(StateAndReplyHandler.class);
    }

    @Test
    public void finalCallAndWeaklyCloseResponseCompletesAfterAllNodesComplete() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveNode = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> rootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = rootBuilder
                .sameMemoryUnprimedDependency(transitiveNode);
        Node<TestMemory, Integer> rootNode = rootBuilder.type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait on the transitive to complete
                    device.call(consumeTransitive);
                    return CompletableFuture.completedFuture(93);
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(rootNode));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        StateAndReplyHandler<GraphCall.FinalState, Integer> handler = mockStateAndReplyHandler();
        Reply<Integer> reply = graphCall.call(rootNode);

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyClose(rootNode, handler);

        assertTrue(reply.isDone());
        assertFalse(handledReply.isDone());
        verifyNoInteractions(handler);
        transitiveResponse.complete(14);

        assertTrue(handledReply.isDone());
    }

    @Test
    public void finalCallAndWeaklyCloseReturnsExceptionalResponseIfHandlerThrows() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Error error = new Error();
        StateAndReplyHandler<GraphCall.FinalState, Integer> handler = mockStateAndReplyHandler();
        doThrow(error).when(handler).handle(any(), any(), any());

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyClose(node, handler);

        CompletionException thrown = assertThrows(CompletionException.class, handledReply::join);
        assertThat(thrown.getCause(), is(error));
    }

    @Test
    public void allowsUsageAfterAbandonmentAndAbandonmentAtAnyTime() {
        Node<TestMemory, Integer> soleNode = nodeReturningValue(52);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(soleNode));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(soleNode);

        graphCall.abandon();

        graphCall.call(soleNode);
        graphCall.weaklyClose();
        graphCall.abandon();
    }

    @Test
    public void abandonCancelsGraphCallImmediately() {
        // Create a transitive waiting dependency. This will create a gap between root and other node completion.
        CompletableFuture<Integer> waitingDependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> waitingDependency = nodeBackedBy(waitingDependencyResponse);

        // Create a waiting root. This will help us make sure that cancellation happens ASAP
        CompletableFuture<Integer> waitingRootResponse = new CompletableFuture<>();
        Node.CommunalBuilder<TestMemory> waitingRootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeWaitingDependency = waitingRootBuilder
                .sameMemoryUnprimedDependency(waitingDependency);
        Node<TestMemory, Integer> waitingRoot = waitingRootBuilder.type(Type.generic("waiting-root"))
                .role(Role.of("waiting-root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call waiting dependency, but don't wait for it
                    device.call(consumeWaitingDependency);
                    return waitingRootResponse;
                });

        // Create the GraphCall and make a single Node call.
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(waitingRoot));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(waitingRoot);

        // Verify some base assertions
        assertFalse(graphCall.isCancelSignalTriggered());
        assertFalse(reply.isDone());

        graphCall.abandon();

        assertTrue(graphCall.isCancelSignalTriggered());
        assertFalse(reply.isDone());
    }

    @Test
    public void abandonDoesntIncludeNonIgnoredRepliesInFinalStateEvenIfTheyFailed() {
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(new IllegalArgumentException()));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(node);

        GraphCall.AbandonedState abandonedState = graphCall.abandon();

        assertThat(abandonedState.getIgnoredReplies(), empty());
        assertThat(abandonedState.getUnhandledExceptions(), empty());
    }

    @Test
    public void abandonIncludesSuccessfulIgnoredRepliesInAbandonedState() {
        Node<TestMemory, Integer> node = nodeReturningValue(23);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.explicitlyIgnore(reply);

        GraphCall.AbandonedState abandonedState = graphCall.abandon();

        assertThat(abandonedState.getIgnoredReplies(), contains(reply));
        assertThat(abandonedState.getUnhandledExceptions(), empty());
    }

    @Test
    public void abandonIncludesFailedIgnoredRepliesInAbandonedState() {
        IllegalArgumentException failure = new IllegalArgumentException();
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(failure));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.explicitlyIgnore(reply);

        GraphCall.AbandonedState abandonedState = graphCall.abandon();

        assertThat(abandonedState.getIgnoredReplies(), contains(reply));
        assertThat(abandonedState.getUnhandledExceptions(), hasSize(1));
        Throwable ignoredFailure = abandonedState.getUnhandledExceptions().iterator().next();
        assertThat(Reply.getFirstNonContainerException(ignoredFailure), is(Optional.of(failure)));
    }

    @Test
    public void abandonIncludesIncompleteImplicitlyIgnoredRepliesInAbandonedState() {
        Node<TestMemory, Integer> neverFinshes = nodeBackedBy(new CompletableFuture<>());
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(neverFinshes));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> neverFinishesReply = graphCall.call(neverFinshes);
        graphCall.implicitlyIgnore(List.of(neverFinishesReply));

        GraphCall.AbandonedState abandonedState = graphCall.abandon();

        assertThat(abandonedState.getIgnoredReplies(), contains(neverFinishesReply));
        assertThat(abandonedState.getUnhandledExceptions(), empty());
    }

    @Test
    public void abandonReturnsImmutableAbandonedState() {
        IllegalArgumentException failure = new IllegalArgumentException();
        Node<TestMemory, Integer> node = nodeBackedBy(CompletableFuture.failedFuture(failure));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        graphCall.implicitlyIgnore(List.of(reply));

        GraphCall.AbandonedState abandonedState = graphCall.abandon();

        assertThrows(UnsupportedOperationException.class, () -> abandonedState.getIgnoredReplies().add(reply));
        assertThrows(UnsupportedOperationException.class,
                () -> abandonedState.getUnhandledExceptions().add(new Throwable()));
    }

    @Test
    public void weaklyCloseOrAbandonOnTimeoutWithSuccessfulClosureDoesntCompleteUntilAllRootNodeCallsComplete() {
        CompletableFuture<Integer> node1Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node1 = nodeBackedBy(node1Response);
        CompletableFuture<Integer> node2Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node2 = nodeBackedBy(node2Response);
        CompletableFuture<Integer> node3Response = new CompletableFuture<>();
        Node<TestMemory, Integer> node3 = nodeBackedBy(node3Response);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node1, node2, node3));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        // Only call the first two nodes and then close
        graphCall.call(node1);
        graphCall.call(node2);
        CompletableFuture<GraphCall.State> state = graphCall.weaklyCloseOrAbandonOnTimeout(1, TimeUnit.MINUTES);

        assertFalse(state.isDone());
        node1Response.complete(1);
        assertFalse(state.isDone());
        node2Response.complete(2);
        assertTrue(state.isDone());
        assertThat(state.join(), instanceOf(GraphCall.FinalState.class));
        assertThat(state.join().getUnhandledExceptions(), empty());
        MisbehaviorException closeException = assertThrows(MisbehaviorException.class, () -> graphCall.weaklyClose());
        assertThat(closeException.getMessage(), containsString("A graph call can only be closed once"));
    }

    @Test
    public void weaklyCloseOrAbandonOnTimeoutAbandonsCallWhenTimeoutExpires() {
        // Create a transitive waiting dependency. This will create a gap between root and other node completion.
        CompletableFuture<Integer> waitingDependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> waitingDependency = nodeBackedBy(waitingDependencyResponse);

        // Create a waiting root. This will help us make sure that cancellation happens ASAP
        CompletableFuture<Integer> waitingRootResponse = new CompletableFuture<>();
        Node.CommunalBuilder<TestMemory> waitingRootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeWaitingDependency = waitingRootBuilder
                .sameMemoryUnprimedDependency(waitingDependency);
        Node<TestMemory, Integer> waitingRoot = waitingRootBuilder.type(Type.generic("waiting-root"))
                .role(Role.of("waiting-root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call waiting dependency, but don't wait for it
                    device.call(consumeWaitingDependency);
                    return waitingRootResponse;
                });

        // Create the GraphCall and make a single Node call.
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(waitingRoot));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(waitingRoot);

        // Verify some base assertions
        assertFalse(graphCall.isCancelSignalTriggered());
        assertFalse(reply.isDone());

        CompletableFuture<GraphCall.State> stateFuture = graphCall.weaklyCloseOrAbandonOnTimeout(1,
                TimeUnit.MILLISECONDS);

        GraphCall.State state = stateFuture.join();
        assertThat(state, instanceOf(GraphCall.AbandonedState.class));
        assertThat(state.getUnhandledExceptions(), empty());
        assertTrue(graphCall.isCancelSignalTriggered());
        assertFalse(reply.isDone());
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutThrowsExceptionGivenNullTimeUnitOrHandler() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        assertThrows(NullPointerException.class,
                () -> graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(node, 1, TimeUnit.MINUTES, null));
        assertThrows(NullPointerException.class,
                () -> graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(node, 1, null, mockStateAndReplyHandler()));
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutPassesFinalStateAndReplyToHandlerAndReturnsReplyAfterSuccessfulClosure() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);
        StateAndReplyHandler<GraphCall.State, Integer> handler = mockStateAndReplyHandler();

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(node, 1,
                TimeUnit.MINUTES, handler);

        assertTrue(handledReply.isDone());
        assertThat(handledReply.join(), is(reply));
        verify(handler).handle(any(GraphCall.FinalState.class), isNull(), eq(reply));
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutCompletesAfterAllNodesCompleteOnSuccessfulClosure() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveNode = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> rootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = rootBuilder
                .sameMemoryUnprimedDependency(transitiveNode);
        Node<TestMemory, Integer> rootNode = rootBuilder.type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait on the transitive to complete
                    device.call(consumeTransitive);
                    return CompletableFuture.completedFuture(93);
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(rootNode));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        StateAndReplyHandler<GraphCall.State, Integer> handler = mockStateAndReplyHandler();
        Reply<Integer> reply = graphCall.call(rootNode);

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(rootNode,
                1, TimeUnit.MINUTES, handler);

        assertTrue(reply.isDone());
        assertFalse(handledReply.isDone());
        verifyNoInteractions(handler);
        transitiveResponse.complete(14);

        assertTrue(handledReply.isDone());
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutPassesAbandonedStateAndReplyToHandlerAndReturnsReplyAfterAbandonmentOnTimeoutExpiryAndReplyIsDone() {
        Node<TestMemory, Integer> neverFinishesDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("never-finishes-dependency"))
                .role(Role.of("never-finishes-dependency"))
                .build(device -> new CompletableFuture<>());

        // Create a root that finishes but calls a dependency that doesn't finish, in order to make closing fail
        Node.CommunalBuilder<TestMemory> rootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeNeverFinishes = rootBuilder
                .sameMemoryUnprimedDependency(neverFinishesDependency);
        Node<TestMemory, Integer> root = rootBuilder.type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call dependency but don't wait on it
                    device.call(consumeNeverFinishes);
                    return CompletableFuture.completedFuture(55);
                });

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> rootReply = graphCall.call(root);
        StateAndReplyHandler<GraphCall.State, Integer> handler = mockStateAndReplyHandler();

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(root, 1,
                TimeUnit.MILLISECONDS, handler);

        assertTrue(rootReply.isDone());
        Reply<Integer> resultingReply = handledReply.join();
        assertThat(resultingReply, is(rootReply));
        verify(handler).handle(any(GraphCall.AbandonedState.class), isNull(), eq(rootReply));
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutPassesAbandonedStateAndReplyToHandlerAndReturnsExceptionAfterAbandonmentOnTimeoutExpiryAndReplyIsNotDone() {
        Node<TestMemory, Integer> neverFinishes = nodeBackedBy(new CompletableFuture<>());
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(neverFinishes));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> neverFinishesReply = graphCall.call(neverFinishes);
        StateAndReplyHandler<GraphCall.State, Integer> handler = mockStateAndReplyHandler();

        CompletableFuture<Reply<Integer>> handledReply = graphCall
                .finalCallAndWeaklyCloseOrAbandonOnTimeout(neverFinishes, 1, TimeUnit.MILLISECONDS, handler);

        CompletionException thrown = assertThrows(CompletionException.class, () -> handledReply.join());
        assertThat(thrown.getCause(), instanceOf(AbandonedAfterTimeoutReplyException.class));
        assertThat(((AbandonedAfterTimeoutReplyException) thrown.getCause()).getReply(), is(neverFinishesReply));
    }

    @Test
    public void finalCallAndWeaklyCloseOrAbandonOnTimeoutReturnsExceptionalResponseIfHandlerThrows() {
        Node<TestMemory, Integer> node = nodeReturningValue(13);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Error error = new Error();
        StateAndReplyHandler<GraphCall.State, Integer> handler = mockStateAndReplyHandler();
        doThrow(error).when(handler).handle(any(), any(), any());

        CompletableFuture<Reply<Integer>> handledReply = graphCall.finalCallAndWeaklyCloseOrAbandonOnTimeout(node, 1,
                TimeUnit.MINUTES, handler);

        CompletionException thrown = assertThrows(CompletionException.class, handledReply::join);
        assertThat(thrown.getCause(), is(error));
    }

    @Test
    public void explicitIgnoreLeadsToCancellationInSituationsCompatibleWithGraphsIgnoringWillTriggerReplyCancelSignal() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(11));
        assertTrue(dependency.getCancelMode().supportsReplySignalPassiveHook());
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder
                .sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Reply<Integer>> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> {
                    Reply<Integer> dependencyReply = device.call(consumeDependency);
                    device.ignore(dependencyReply); // Causes explicit ignoring
                    return CompletableFuture.completedFuture(dependencyReply);
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(node).join();

        assertTrue(dependencyReply.isCancelSignalTriggered());
    }

    @Test
    public void explicitIgnoreDoesNotLeadToCancellationInSituationsCompatibleWithGraphsIgnoreWillTriggerReplyCancellationSignal() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestChildMemory> nodeBuilder = Node.communalBuilder(TestChildMemory.class);
        AncestorMemoryDependency<TestMemory, Integer> consumeDependency = nodeBuilder
                .ancestorMemoryDependency(dependency);
        Node<TestChildMemory, Reply<Integer>> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> {
                    Reply<Integer> dependencyReply = device.accessAncestorMemoryAndCall(TestChildMemory::getTestMemory,
                            consumeDependency);
                    device.ignore(dependencyReply); // Causes explicit ignoring
                    return CompletableFuture.completedFuture(dependencyReply);
                });
        Graph<TestChildMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestChildMemory> graphCall = graph.openCancellableCall(scope -> new TestChildMemory(scope, graphInput,
                new TestMemory(scope, CompletableFuture.completedFuture(19))), Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(node).join();

        assertFalse(dependencyReply.isCancelSignalTriggered());
    }

    @Test
    public void implicitIgnoreTriesToEscalateToCancellationForReplies() {
        Node<TestMemory, Integer> neverFinishesDependency1 = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("neverFinishesDependency1"))
                .role(Role.of("neverFinishesDependency1"))
                .buildWithCompositeCancelSignal((device, signal) -> new CompletableFuture<>());

        Node<TestMemory, Integer> neverFinishesDependency2 = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("neverFinishesDependency2"))
                .role(Role.of("neverFinishesDependency2"))
                .buildWithCompositeCancelSignal((device, signal) -> new CompletableFuture<>());
        assertTrue(neverFinishesDependency2.getCancelMode().supportsReplySignalPassiveHook());

        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency1 = nodeBuilder
                .sameMemoryUnprimedDependency(neverFinishesDependency1);
        SameMemoryDependency<TestMemory, Integer> consumeDependency2 = nodeBuilder
                .sameMemoryUnprimedDependency(neverFinishesDependency2);
        Node<TestMemory, List<Reply<Integer>>> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait on dependencies, causing them to be implicitly ignored
                    Reply<Integer> dependencyReply1 = device.call(consumeDependency1);
                    Reply<Integer> dependencyReply2 = device.call(consumeDependency2);
                    return CompletableFuture.completedFuture(List.of(dependencyReply1, dependencyReply2));
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        List<Reply<Integer>> dependencyReplies = graphCall.call(node).join();

        assertThat(dependencyReplies, hasSize(2));
        assertTrue(dependencyReplies.get(0).isCancelSignalTriggered());
        assertTrue(dependencyReplies.get(1).isCancelSignalTriggered());
    }

    @Test
    public void implicitIgnoreFollowsSameRulesForEscalatingToCancellationAsExplicitIgnore() {
        Node<TestMemory, Integer> neverFinishesDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("neverFinishesDependency"))
                .role(Role.of("neverFinishesDependency"))
                .buildWithCompositeCancelSignal((device, signal) -> new CompletableFuture<>());

        Node.CommunalBuilder<TestMemory> consumer1Builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = consumer1Builder
                .sameMemoryUnprimedDependency(neverFinishesDependency);
        Node<TestMemory, Reply<Integer>> consumer1 = consumer1Builder.type(Type.generic("consumer1"))
                .role(Role.of("consumer1"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait on dependency, causing it to be implicitly ignored
                    Reply<Integer> dependencyReply = device.call(consumeDependency);
                    return CompletableFuture.completedFuture(dependencyReply);
                });

        Node.CommunalBuilder<TestMemory> consumer2Builder = Node.communalBuilder(TestMemory.class);
        consumer2Builder.sameMemoryUnprimedDependency(neverFinishesDependency);
        Node<TestMemory, Integer> consumer2 = consumer2Builder.type(Type.generic("consumer2"))
                .role(Role.of("consumer2"))
                .build(device -> CompletableFuture.completedFuture(22));

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(consumer1, consumer2));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(consumer1).join();

        assertFalse(dependencyReply.isCancelSignalTriggered());
    }

    @Test
    public void ignoreTriesToEscalateToCancellationForReplies() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(11));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> reply = graphCall.call(node);
        graphCall.ignore(reply);

        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void ignoreFollowsSameRulesForEscalatingToCancellationAsExplicitIgnore() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(12));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node, dependency));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(dependency);

        assertFalse(dependencyReply.isCancelSignalTriggered());
    }

    @Test
    public void ignoreThrowsExceptionGivenNonRootNodeReply() {
        Node<TestMemory, Integer> leaf = nodeReturningValue(1);
        Node.CommunalBuilder<TestMemory> rootBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeLeaf = rootBuilder.primedDependency(leaf);
        Node<TestMemory, Reply<Integer>> root = rootBuilder.type(Type.generic("root"))
                .role(Role.of("return-leaf-reply"))
                .build(device -> CompletableFuture.completedFuture(device.call(consumeLeaf)));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> leafReply = graphCall.call(root).join();

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class, () -> graphCall.ignore(leafReply));

        assertThat(thrown.getMessage(), allOf(containsString(leafReply.toString()), containsString("Tried to ignore"),
                containsString("without first calling it as a root node")));
    }

    @Test
    public void ignoreCanBeCalledAfterWeaklyClose() {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCompositeCancelSignal((device, signal) -> response);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(node);

        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        graphCall.ignore(reply); // No exception thrown
        response.complete(12);

        assertTrue(finalState.isDone());
        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void triggerCancelSignalCanBeCalledAfterWeaklyClose() {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                .build(device -> response);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        graphCall.call(root);

        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        graphCall.triggerCancelSignal(); // No exception thrown
        response.complete(15);

        assertTrue(finalState.isDone());
        assertTrue(graphCall.isCancelSignalTriggered());
    }

    @Test
    public void triggerCancelSignalSupportsActiveHooks() {
        AtomicBoolean actionCalled = new AtomicBoolean(false);
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .buildWithCustomCancelAction(new BehaviorWithCustomCancelAction<TestData.TestMemory, Integer>() {
                    @Override
                    public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                            CompositeCancelSignal signal) {
                        return new CustomCancelActionBehaviorResponse<>(new CompletableFuture<>(), mayInterrupt -> {
                            actionCalled.set(true);
                        });
                    }

                    @Override
                    public boolean cancelActionMayInterruptIfRunning() {
                        return false;
                    }
                });
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());
        Reply<Integer> reply = graphCall.call(root);

        graphCall.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        assertTrue(actionCalled.get());
    }

    @Test
    public void triggerCancelSignalSupportsPassiveHooksInRootMemoryScopes() {
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> new CompletableFuture<>());
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        graphCall.triggerCancelSignal();
        Reply<Integer> reply = graphCall.call(root);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), instanceOf(CancellationException.class));
    }

    @Test
    public void triggerCancelSignalSupportsPassiveHooksInNonRootResponsiveToPassiveMemoryScopes() {
        CountDownLatch readyToCheckChildMemoryCancelled = new CountDownLatch(1);
        CountDownLatch shouldCheckChildMemoryCancelled = new CountDownLatch(1);
        Node<TestChildMemory, Boolean> checkChildMemoryCancelledFromChild = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("child"))
                .role(Role.of("check-cancelled-from-child"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.supplyAsync(() -> {
                    readyToCheckChildMemoryCancelled.countDown();
                    Try.runUnchecked(() -> shouldCheckChildMemoryCancelled.await(5, TimeUnit.SECONDS));
                    return signal.read();
                }));
        Node.CommunalBuilder<TestMemory> checkChildMemoryCancelledFromRootBuilder = Node
                .communalBuilder(TestMemory.class);
        NewMemoryDependency<TestChildMemory, Boolean> consumeChild = checkChildMemoryCancelledFromRootBuilder
                .newMemoryDependency(checkChildMemoryCancelledFromChild);
        Node<TestMemory, Boolean> checkChildMemoryCancelledFromRoot = checkChildMemoryCancelledFromRootBuilder
                .type(Type.generic("root"))
                .role(Role.of("check-cancelled-from-root"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> device.createMemoryNoInputAndCall(
                        (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(13), parent),
                        consumeChild));
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(checkChildMemoryCancelledFromRoot));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Boolean> isChildMemoryCancelled = graphCall.call(checkChildMemoryCancelledFromRoot);
        Try.runUnchecked(() -> readyToCheckChildMemoryCancelled.await(5, TimeUnit.SECONDS));
        assertFalse(isChildMemoryCancelled.isDone());

        graphCall.triggerCancelSignal();
        shouldCheckChildMemoryCancelled.countDown();

        assertTrue(isChildMemoryCancelled.join());
    }

    @Test
    public void toStringPrintsSimplClassNameAndHexString() {
        Node<TestMemory, Integer> root = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("root"))
                .role(Role.of("root"))
                .build(device -> new CompletableFuture<>());
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(root));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        String toString = graphCall.toString();

        assertThat(toString, startsWith("GraphCall"));
        assertThat(toString, containsString(Integer.toHexString(graphCall.hashCode())));
    }
}
