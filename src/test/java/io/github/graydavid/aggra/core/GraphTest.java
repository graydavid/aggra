package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.TestData.arbitraryBehaviorWithCustomCancelAction;
import static io.github.graydavid.aggra.core.TestData.nodeReturningValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class GraphTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(29);

    @Test
    public void fromRootsThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> Graph.fromRoots(null, Set.of(), List.of()));
        assertThrows(NullPointerException.class, () -> Graph.fromRoots(Role.of("graph"), null, List.of()));
        assertThrows(NullPointerException.class, () -> Graph.fromRoots(Role.of("graph"), Set.of(), null));

        assertThrows(NullPointerException.class, () -> Graph.fromRoots(null, Set.of()));
        assertThrows(NullPointerException.class, () -> Graph.fromRoots(Role.of("graph"), null));
    }

    @Test
    public void fromCandidateThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class,
                () -> Graph.fromCandidate(null, GraphCandidate.fromRoots(Set.of()), List.of()));
        assertThrows(NullPointerException.class, () -> Graph.fromCandidate(Role.of("graph"), null, List.of()));
        assertThrows(NullPointerException.class,
                () -> Graph.fromCandidate(Role.of("graph"), GraphCandidate.fromRoots(Set.of()), null));
    }

    @Test
    public void fromCandidateRunsAllValidatorsAgainstCandidate() {
        ForNodeGraphValidatorFactory nodeValidatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("leaf"))
                .graphValidatorFactory(nodeValidatorFactory)
                .build(device -> CompletableFuture.completedFuture(5));
        GraphValidator nodeValidator = mock(GraphValidator.class);
        when(nodeValidatorFactory.create(node)).thenReturn(nodeValidator);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));
        GraphValidator extraValidator = mock(GraphValidator.class);

        Graph.fromCandidate(Role.of("graph"), candidate, List.of(extraValidator));

        verify(nodeValidator).validate(candidate);
        verify(extraValidator).validate(candidate);
    }

    @Test
    public void fromRootsRunsAllValidatorsAgainstCandidate() {
        ForNodeGraphValidatorFactory nodeValidatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("leaf"))
                .graphValidatorFactory(nodeValidatorFactory)
                .build(device -> CompletableFuture.completedFuture(5));
        GraphValidator nodeValidator = mock(GraphValidator.class);
        when(nodeValidatorFactory.create(node)).thenReturn(nodeValidator);
        GraphValidator extraValidator = mock(GraphValidator.class);

        Graph.fromRoots(Role.of("graph"), Set.of(node), List.of(extraValidator));

        verify(nodeValidator).validate(anyGraphCandidate());
        verify(extraValidator).validate(anyGraphCandidate());
    }

    @Test
    public void addsExtraValidatorToDetectAncestralMemoryRelationshipCycles() {
        Node<TestMemory, Integer> leafNodeInParentMemory = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestChildMemory> parentCallingNodeInChildMemoryBuilder = Node
                .communalBuilder(TestChildMemory.class);
        parentCallingNodeInChildMemoryBuilder.ancestorMemoryDependency(leafNodeInParentMemory);
        Node<TestChildMemory, Integer> parentCallingNodeInChildMemory = parentCallingNodeInChildMemoryBuilder
                .type(Type.generic("parent-calling"))
                .role(Role.of("in-child"))
                .build(device -> CompletableFuture.completedFuture(7));
        Node.CommunalBuilder<TestMemory> childCallingNodeInParentMemoryBuilder = Node.communalBuilder(TestMemory.class);
        childCallingNodeInParentMemoryBuilder.ancestorMemoryDependency(parentCallingNodeInChildMemory);
        Node<TestMemory, Integer> childCallingNodeInParentMemory = childCallingNodeInParentMemoryBuilder
                .type(Type.generic("child-calling"))
                .role(Role.of("in-parent"))
                .build(device -> CompletableFuture.completedFuture(7));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Graph.fromRoots(Role.of("graph"), Set.of(childCallingNodeInParentMemory), List.of()));

        assertThat(thrown.getMessage(), anyOf(containsString("TestMemory->TestChildMemory->TestMemory"),
                containsString("TestChildMemory->TestMemory->TestChildMemory")));
    }

    // Suppress justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    public static GraphCandidate<TestMemory> anyGraphCandidate() {
        return any(GraphCandidate.class);
    }

    @Test
    public void fromRootsNoExtraRunsAllValidatorsAgainstCandidate() {
        ForNodeGraphValidatorFactory nodeValidatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("leaf"))
                .graphValidatorFactory(nodeValidatorFactory)
                .build(device -> CompletableFuture.completedFuture(5));
        GraphValidator nodeValidator = mock(GraphValidator.class);
        when(nodeValidatorFactory.create(node)).thenReturn(nodeValidator);

        Graph.fromRoots(Role.of("graph"), Set.of(node));

        verify(nodeValidator).validate(anyGraphCandidate());
    }

    @Test
    public void accessorsReturnExpectedResults() {
        Role role = Role.of("graph");
        GraphCandidate<TestMemory> candidate = mockCandidate();
        Set<Node<TestMemory, ?>> rootNodes = new HashSet<>();
        when(candidate.getRootNodes()).thenReturn(rootNodes);
        Set<Node<?, ?>> allNodes = new HashSet<>();
        when(candidate.getAllNodes()).thenReturn(allNodes);
        Node<?, ?> consumedNode = NodeMocks.node();
        Set<Edge> consumingEdgesOfNode = new HashSet<>();
        when(candidate.getConsumingEdgesOf(consumedNode)).thenReturn(consumingEdgesOfNode);
        Set<Edge> allEdges = new HashSet<>();
        when(candidate.getAllEdges()).thenReturn(allEdges);

        Graph<TestMemory> graph = Graph.fromCandidate(role, candidate, List.of());

        assertThat(graph.getRole(), is(Role.of("graph")));
        assertThat(graph.getRootNodes(), sameInstance(rootNodes));
        assertThat(graph.getAllNodes(), sameInstance(allNodes));
        assertThat(graph.getConsumingEdgesOf(consumedNode), sameInstance(consumingEdgesOfNode));
        assertThat(graph.getAllEdges(), sameInstance(allEdges));
    }

    // Suppress justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    private GraphCandidate<TestMemory> mockCandidate() {
        return mock(GraphCandidate.class);
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalDelegatesToCandidateWhenTrue() {
        Role role = Role.of("graph");
        Node<TestMemory, Integer> node = nodeReturningValue(12);
        GraphCandidate<TestMemory> candidate = mockCandidate();
        when(candidate.ignoringWillTriggerReplyCancelSignal(node)).thenReturn(true);

        Graph<TestMemory> graph = Graph.fromCandidate(role, candidate, List.of());

        assertTrue(graph.ignoringWillTriggerReplyCancelSignal(node));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalDelegatesToCandidateWhenFalse() {
        Role role = Role.of("graph");
        Node<TestMemory, Integer> node = nodeReturningValue(12);
        GraphCandidate<TestMemory> candidate = mockCandidate();
        when(candidate.ignoringWillTriggerReplyCancelSignal(node)).thenReturn(false);

        Graph<TestMemory> graph = Graph.fromCandidate(role, candidate, List.of());

        assertFalse(graph.ignoringWillTriggerReplyCancelSignal(node));
    }

    @Test
    public void allRootNodesWaitForAllDependenciesReturnsTrueForEmptyGraph() {
        assertTrue(Graph.fromRoots(Role.of("graph"), Set.of()).allRootNodesWaitForAllDependencies());
    }

    @Test
    public void allRootNodesWaitForAllDependenciesReturnsTrueWhenMinimumLifetimeDoesForAllRoots() {
        Node<TestMemory, ?> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(14));

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));

        assertFalse(node.getDeclaredDependencyLifetime().waitsForAllDependencies());
        assertTrue(node.getMinimumDependencyLifetime().waitsForAllDependencies());
        assertTrue(graph.allRootNodesWaitForAllDependencies());
    }

    @Test
    public void allRootNodesWaitForAllDependenciesReturnsFalseWhenJustOneDoesnt() {
        Node<TestMemory, ?> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(14));
        Node.CommunalBuilder<TestMemory> noWaitBuilder = Node.communalBuilder(TestMemory.class);
        noWaitBuilder.primedDependency(dependency);
        Node<TestMemory, ?> noWait = noWaitBuilder.type(Type.generic("no-wait"))
                .role(Role.of("no-wait"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(15));
        Node<TestMemory, ?> waits = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("waits"))
                .role(Role.of("waits"))
                .build(device -> CompletableFuture.completedFuture(16));

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(noWait, waits));

        assertFalse(noWait.getMinimumDependencyLifetime().waitsForAllDependencies());
        assertFalse(graph.allRootNodesWaitForAllDependencies());
    }

    @Test
    public void shadowSupportsActiveCancelHooksReturnsFalseForEmptyGraph() {
        assertFalse(Graph.fromRoots(Role.of("graph"), Set.of()).shadowSupportsActiveCancelHooks());
    }

    @Test
    public void shadowSupportsActiveCancelHooksReturnsFalseWhenAllRootsDo() {
        Node<TestMemory, ?> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(14));

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(node));

        assertFalse(node.shadowSupportsActiveCancelHooks());
        assertFalse(graph.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void shadowSupportsActiveCancelHooksReturnsTrueWhenJustOneDoes() {
        Node<TestMemory, ?> noSupport = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("no-support"))
                .role(Role.of("no-support"))
                .build(device -> CompletableFuture.completedFuture(15));
        Node<TestMemory, ?> support = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("support"))
                .role(Role.of("support"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(noSupport, support));

        assertTrue(support.shadowSupportsActiveCancelHooks());
        assertTrue(graph.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void openCancellableCallThrowsExceptionGivenFactoryThatProvidesNullMemory() {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of());

        assertThrows(NullPointerException.class, () -> graph.openCancellableCall(scope -> {
            return null;
        }, Observer.doNothing()));
    }

    @Test
    public void openCancellableCallThrowsExceptionGivenNullObserver() {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of());

        assertThrows(NullPointerException.class,
                () -> graph.openCancellableCall(scope -> new TestMemory(scope, graphInput), null));
    }
}
