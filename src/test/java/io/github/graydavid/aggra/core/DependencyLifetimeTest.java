package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.DependencyLifetime.GRAPH;
import static io.github.graydavid.aggra.core.DependencyLifetime.NODE_FOR_ALL;
import static io.github.graydavid.aggra.core.DependencyLifetime.NODE_FOR_DIRECT;
import static io.github.graydavid.aggra.core.TestData.nodeBackedBy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.FinalState;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class DependencyLifetimeTest {
    // Suppress justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    private final Consumer<Set<Reply<?>>> backingCompleter = mock(Consumer.class);
    private final Runnable nodeForAllSignalCompleter = mock(Runnable.class);

    @Test
    public void waitsForAllDependenciesReturnsTrueForNodeForAll() {
        assertTrue(NODE_FOR_ALL.waitsForAllDependencies());
    }

    @Test
    public void waitsForAllDependenciesReturnsFalseForNonNodeForAll() {
        assertFalse(NODE_FOR_DIRECT.waitsForAllDependencies());
        assertFalse(GRAPH.waitsForAllDependencies());
    }

    @Test
    public void waitsForDirectDependenciesReturnsFalseForGraph() {
        assertFalse(GRAPH.waitsForDirectDependencies());
    }

    @Test
    public void waitsForDirectDependenciesReturnsTrueForNonGraph() {
        assertTrue(NODE_FOR_ALL.waitsForDirectDependencies());
        assertTrue(NODE_FOR_DIRECT.waitsForDirectDependencies());
    }

    @Test
    public void minimumLifetimeWithDependenciesReturnsNodeForAllForAllLifetimeWithAnyTypesOfDependencies() {
        assertThat(NODE_FOR_ALL.minimumLifetimeWithDependencies(Collections.emptySet()), is(NODE_FOR_ALL));
        assertThat(NODE_FOR_ALL.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL)), is(NODE_FOR_ALL));
        assertThat(NODE_FOR_ALL.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL)), is(NODE_FOR_ALL));
        assertThat(NODE_FOR_ALL.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL)), is(NODE_FOR_ALL));
    }

    @Test
    public void minimumLifetimeWithDependenciesReturnsNodeForAllForDirectLifetimeWithAllNodeForAllDependencies() {
        assertThat(NODE_FOR_DIRECT.minimumLifetimeWithDependencies(Collections.emptySet()), is(NODE_FOR_ALL));
        assertThat(NODE_FOR_DIRECT.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL)), is(NODE_FOR_ALL));
    }

    @Test
    public void minimumLifetimeWithDependenciesReturnsNodeForDirectForDirectLifetimeWithAnyNonNodeForAllDependency() {
        assertThat(NODE_FOR_DIRECT.minimumLifetimeWithDependencies(List.of(NODE_FOR_DIRECT)), is(NODE_FOR_DIRECT));
        assertThat(NODE_FOR_DIRECT.minimumLifetimeWithDependencies(List.of(GRAPH)), is(NODE_FOR_DIRECT));
        assertThat(NODE_FOR_DIRECT.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL, GRAPH)), is(NODE_FOR_DIRECT));
    }

    @Test
    public void minimumLifetimeWithDependenciesReturnsNodeForGraphWithNoDependencies() {
        assertThat(GRAPH.minimumLifetimeWithDependencies(Collections.emptySet()), is(NODE_FOR_ALL));
    }

    @Test
    public void minimumLifetimeWithDependenciesReturnsGraphForGraphLifetimeWithAnyDependenciesEvenTheMinimum() {
        assertThat(GRAPH.minimumLifetimeWithDependencies(List.of(NODE_FOR_ALL)), is(GRAPH));
    }

    @Test
    public void calculateReplyNodeForAllSignalReturnsBackingFutureForNodeForAll() {
        CompletableFuture<?> backing = new CompletableFuture<>();

        CompletableFuture<?> nodeForAllSignal = NODE_FOR_ALL.calculateReplyNodeForAllSignal(backing);

        assertThat(nodeForAllSignal, is(backing));
    }

    @Test
    public void calculateReplyNodeForAllSignalReturnsNewFutureForNodeForDirect() {
        CompletableFuture<?> backing = new CompletableFuture<>();

        CompletableFuture<?> nodeForAllSignal = NODE_FOR_DIRECT.calculateReplyNodeForAllSignal(backing);

        assertThat(nodeForAllSignal, not(backing));
    }

    @Test
    public void calculateReplyNodeForAllSignalReturnsNewFutureForGraph() {
        CompletableFuture<?> backing = new CompletableFuture<>();

        CompletableFuture<?> nodeForAllSignal = GRAPH.calculateReplyNodeForAllSignal(backing);

        assertThat(nodeForAllSignal, not(backing));
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureImmediatelyWithNoDependenciesAndNeverTouchesNodeForAllSignalForNodeForAll() {
        NODE_FOR_ALL.enforceLifetimeForReply(FinalState.empty(), backingCompleter, nodeForAllSignalCompleter);

        verify(backingCompleter).accept(Set.of());
        verifyNoInteractions(nodeForAllSignalCompleter);
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureImmediatelyWithAlreadyCompletedDependenciesAndNeverTouchesNodeForAllSignalForNodeForAll() {
        Node<TestMemory, Integer> directDependency = nodeBackedBy(CompletableFuture.completedFuture(21));
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_ALL.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertTrue(directDependencyReply.isDone());
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verifyNoInteractions(nodeForAllSignalCompleter);
    }

    @Test
    public void enforceLifetimeForReplyWaitsUntilAllDirectDependenciesAreCompleteBeforeCompletingBackingFutureAndNeverTouchesNodeForAllSignalForNodeForAll() {
        CompletableFuture<Integer> directResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> directDependency = nodeBackedBy(directResponse);
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_ALL.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertFalse(directDependencyReply.isDone());
        verifyNoInteractions(backingCompleter, nodeForAllSignalCompleter);

        directResponse.complete(13);
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verifyNoInteractions(nodeForAllSignalCompleter);
    }

    @Test
    public void enforceLifetimeForReplyWaitsUntilAllTransitiveDependenciesAreCompleteBeforeCompletingBackingFutureAndNeverTouchesNodeForAllSignalForNodeForAll() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveDependency = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> directDependencyBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = directDependencyBuilder
                .sameMemoryUnprimedDependency(transitiveDependency);
        Node<TestMemory, Integer> directDependency = directDependencyBuilder.type(Type.generic("test"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call transitive dependency but don't wait on it
                    device.call(consumeTransitive);
                    return CompletableFuture.completedFuture(12);
                });
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_ALL.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertTrue(directDependencyReply.isDone());
        verifyNoInteractions(backingCompleter, nodeForAllSignalCompleter);

        transitiveResponse.complete(13);
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verifyNoInteractions(nodeForAllSignalCompleter);
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureAndNodeForAllSignalImmediatelyWithNoDependenciesForNodeForDirect() {
        NODE_FOR_DIRECT.enforceLifetimeForReply(FinalState.empty(), backingCompleter, nodeForAllSignalCompleter);

        verify(backingCompleter).accept(Set.of());
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureAndNodeForAllSignalImmediatelyWithAlreadyCompletedDependenciesForNodeForDirect() {
        Node<TestMemory, Integer> directDependency = nodeBackedBy(CompletableFuture.completedFuture(21));
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_DIRECT.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertTrue(directDependencyReply.isDone());
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyWaitsUntilExclusivelyDirectDependenciesAreCompleteBeforeCompletingBackingFutureForNodeForDirect() {
        CompletableFuture<Integer> directResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> directDependency = nodeBackedBy(directResponse);
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_DIRECT.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertFalse(directDependencyReply.isDone());
        verifyNoInteractions(backingCompleter, nodeForAllSignalCompleter);

        directResponse.complete(13);
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyWaitsUntilDirectDependenciesAreCompleteBeforeCompletingBackingFutureAndUntilAllTransitiveDependenciesAreCompleteBeforeCompletingNodeForAllSignalForNodeForDirect() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveDependency = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> directDependencyBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = directDependencyBuilder
                .sameMemoryUnprimedDependency(transitiveDependency);
        CompletableFuture<Integer> directResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> directDependency = directDependencyBuilder.type(Type.generic("test"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call transitive dependency but don't wait on it
                    device.call(consumeTransitive);
                    return directResponse;
                });
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        NODE_FOR_DIRECT.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertFalse(directDependencyReply.isDone());
        verifyNoInteractions(backingCompleter, nodeForAllSignalCompleter);

        directResponse.complete(12);
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verifyNoInteractions(nodeForAllSignalCompleter);

        transitiveResponse.complete(13);
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureAndNodeForAllSignalImmediatelyWithNoDependenciesForGraph() {
        GRAPH.enforceLifetimeForReply(FinalState.empty(), backingCompleter, nodeForAllSignalCompleter);

        verify(backingCompleter).accept(Set.of());
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureAndNodeForAllSignalImmediatelyWithAlreadyCompletedDependenciesForGraph() {
        Node<TestMemory, Integer> directDependency = nodeBackedBy(CompletableFuture.completedFuture(21));
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        GRAPH.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertTrue(directDependencyReply.isDone());
        verify(backingCompleter).accept(Set.of(directDependencyReply));
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureImmediatelyWithIncompleteDirectDependenciesAndWaitsUntilDependenciesCompleteForNodeForAllSignalForGraph() {
        CompletableFuture<Integer> directResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> directDependency = nodeBackedBy(directResponse);
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        GRAPH.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertFalse(directDependencyReply.isDone());
        verify(backingCompleter).accept(Set.of());
        verifyNoInteractions(nodeForAllSignalCompleter);

        directResponse.complete(13);
        verify(nodeForAllSignalCompleter).run();
    }

    @Test
    public void enforceLifetimeForReplyCompletesBackingFutureImmediatelyWithIncompleteTransitiveDependenciesAndWaitsUntilDependenciesCompleteForNodeForAllSignalForGraph() {
        CompletableFuture<Integer> transitiveResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> transitiveDependency = nodeBackedBy(transitiveResponse);
        Node.CommunalBuilder<TestMemory> directDependencyBuilder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeTransitive = directDependencyBuilder
                .sameMemoryUnprimedDependency(transitiveDependency);
        CompletableFuture<Integer> directResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> directDependency = directDependencyBuilder.type(Type.generic("test"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call transitive dependency but don't wait on it
                    device.call(consumeTransitive);
                    return directResponse;
                });
        Reply<Integer> directDependencyReply = TestData.callNodeInNewTestMemoryGraph(directDependency);
        DependencyCallingDevices.FinalState deviceFinalState = FinalState.of(Set.of(directDependencyReply));

        GRAPH.enforceLifetimeForReply(deviceFinalState, backingCompleter, nodeForAllSignalCompleter);

        assertFalse(directDependencyReply.isDone());
        verify(backingCompleter).accept(Set.of());
        verifyNoInteractions(nodeForAllSignalCompleter);

        directResponse.complete(12);
        verifyNoInteractions(nodeForAllSignalCompleter);

        transitiveResponse.complete(13);
        verify(nodeForAllSignalCompleter).run();
    }
}
