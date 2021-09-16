package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static io.github.graydavid.aggra.core.TestData.nodeReturningValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the GraphCandidate-specific logic in GraphValidators. You can find other GraphValidators-related tests under
 * GraphValidators_*Test classes.
 */
public class GraphValidators_GraphCandidateTest {
    @Test
    public void fromRootsThrowsExceptionGivenNullRole() {
        assertThrows(NullPointerException.class, () -> GraphCandidate.fromRoots(null));
    }

    @Test
    public void accessorsReturnExpectedResults() {
        Node<TestMemory, Integer> root1 = NodeMocks.node();
        Node<TestMemory, Integer> root2 = NodeMocks.node();
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(root1, root2));

        assertThat(candidate.getRootNodes(), containsInAnyOrder(root1, root2));
    }

    @Test
    public void toStringContainsDescriptionOfRootsOnly() {
        Node<TestMemory, Integer> child = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("exist"))
                .role(Role.of("child"))
                .build(device -> CompletableFuture.completedFuture(1));
        Node.CommunalBuilder<TestMemory> root1Builder = Node.communalBuilder(TestMemory.class);
        root1Builder.primedDependency(child);
        Node<TestMemory, Integer> root1 = root1Builder.type(Type.generic("exist"))
                .role(Role.of("root1"))
                .build(device -> CompletableFuture.completedFuture(1));
        Node<TestMemory, Integer> root2 = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("exist"))
                .role(Role.of("root2"))
                .build(device -> CompletableFuture.completedFuture(2));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(root1, root2));

        String toString = candidate.toString();

        assertThat(toString,
                allOf(containsString(root1.getRole().toString()), containsString(root2.getRole().toString())));
        assertThat(toString, not(containsString(child.getRole().toString())));
    }

    @Test
    public void getConsumingEdgesOfThrowsExceptionGivenNodeNotInCandidate() {
        Node<TestMemory, Integer> nodeNotInGraph = NodeMocks.node();
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> candidate.getConsumingEdgesOf(nodeNotInGraph));

        assertThat(thrown.getMessage(),
                allOf(containsString(nodeNotInGraph.toString()), containsString("not part of this graph")));
    }

    @Test
    public void containsNoNodesOrEdgesForEmptyGraph() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());

        assertThat(candidate.getAllNodes(), empty());
        assertThat(candidate.getAllEdges(), empty());
    }

    @Test
    public void containsOnlyNodesForCandidateWithOneNode() {
        Node<TestMemory, Integer> node = nodeReturningValue(1);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        assertThat(candidate.getConsumingEdgesOf(node), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(node));
        assertThat(candidate.getAllEdges(), empty());
    }

    @Test
    public void containsOnlyNodesForCandidateWithMultipleUnconnectedNodes() {
        Node<TestMemory, Integer> node1 = nodeReturningValue(1);
        Node<TestMemory, Integer> node2 = nodeReturningValue(2);
        Node<TestMemory, Integer> node3 = nodeReturningValue(3);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node1, node2, node3));

        assertThat(candidate.getConsumingEdgesOf(node1), empty());
        assertThat(candidate.getConsumingEdgesOf(node2), empty());
        assertThat(candidate.getConsumingEdgesOf(node3), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(node1, node2, node3));
        assertThat(candidate.getAllEdges(), empty());
    }

    @Test
    public void containsRightNodesAndEdgesForOneConnection() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(1);
        Node<TestMemory, Integer> consumer = nodeAddingOneTo(dependency);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));

        assertThat(candidate.getConsumingEdgesOf(dependency),
                containsInAnyOrder(primedSameMemoryEdge(consumer, dependency)));
        assertThat(candidate.getConsumingEdgesOf(consumer), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(consumer, dependency));
        assertThat(candidate.getAllEdges(), containsInAnyOrder(primedSameMemoryEdge(consumer, dependency)));
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

    private static Edge primedSameMemoryEdge(Node<TestMemory, Integer> consumer, Node<TestMemory, Integer> dependency) {
        return new Edge(consumer, newSameMemoryDependency(dependency, PrimingMode.PRIMED));
    }

    @Test
    public void containsRightNodesAndEdgesForLongSingleConnection() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(1);
        Node<TestMemory, Integer> consumer = nodeAddingOneTo(dependency);
        Node<TestMemory, Integer> consumerOfConsumer = nodeAddingOneTo(consumer);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumerOfConsumer));

        assertThat(candidate.getConsumingEdgesOf(dependency),
                containsInAnyOrder(primedSameMemoryEdge(consumer, dependency)));
        assertThat(candidate.getConsumingEdgesOf(consumer),
                containsInAnyOrder(primedSameMemoryEdge(consumerOfConsumer, consumer)));
        assertThat(candidate.getConsumingEdgesOf(consumerOfConsumer), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(consumerOfConsumer, consumer, dependency));
        assertThat(candidate.getAllEdges(), containsInAnyOrder(primedSameMemoryEdge(consumer, dependency),
                primedSameMemoryEdge(consumerOfConsumer, consumer)));
    }


    @Test
    public void containsRightNodesAndEdgesForMultipleConsumersOfSameNode() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(1);
        Node<TestMemory, Integer> consumer1 = nodeAddingOneTo(dependency);
        Node<TestMemory, Integer> consumer2 = nodeAddingOneTo(dependency);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer1, consumer2));

        assertThat(candidate.getConsumingEdgesOf(dependency), containsInAnyOrder(
                primedSameMemoryEdge(consumer1, dependency), primedSameMemoryEdge(consumer2, dependency)));
        assertThat(candidate.getConsumingEdgesOf(consumer1), empty());
        assertThat(candidate.getConsumingEdgesOf(consumer2), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(consumer1, consumer2, dependency));
        assertThat(candidate.getAllEdges(), containsInAnyOrder(primedSameMemoryEdge(consumer1, dependency),
                primedSameMemoryEdge(consumer2, dependency)));
    }

    @Test
    public void containsRightNodesAndEdgesForConsumerOfMultipleNodes() {
        Node<TestMemory, Integer> dependency1 = nodeReturningValue(1);
        Node<TestMemory, Integer> dependency2 = nodeReturningValue(2);
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("consumer"));
        SameMemoryDependency<TestMemory, Integer> consumeDependency1 = consumerBuilder.primedDependency(dependency1);
        SameMemoryDependency<TestMemory, Integer> consumeDependency2 = consumerBuilder.primedDependency(dependency2);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> {
            Reply<Integer> dependency1Result = device.call(consumeDependency1);
            Reply<Integer> dependency2Result = device.call(consumeDependency2);
            return CompletableFuture.completedFuture(dependency1Result.join() + dependency2Result.join());
        });
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));

        assertThat(candidate.getConsumingEdgesOf(dependency1),
                containsInAnyOrder(primedSameMemoryEdge(consumer, dependency1)));
        assertThat(candidate.getConsumingEdgesOf(dependency2),
                containsInAnyOrder(primedSameMemoryEdge(consumer, dependency2)));
        assertThat(candidate.getConsumingEdgesOf(consumer), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(consumer, dependency1, dependency2));
        assertThat(candidate.getAllEdges(), containsInAnyOrder(primedSameMemoryEdge(consumer, dependency1),
                primedSameMemoryEdge(consumer, dependency2)));
    }

    @Test
    public void containsRightNodesAndEdgesForDependencyNodeEncounteredThroughMultipleParentPaths() {
        Node<TestMemory, Integer> leaf = nodeReturningValue(1);
        Node<TestMemory, Integer> fromMultiplePaths = nodeAddingOneTo(leaf);
        Node<TestMemory, Integer> throughPath1 = nodeAddingOneTo(fromMultiplePaths);
        Node<TestMemory, Integer> throughPath2 = nodeAddingOneTo(fromMultiplePaths);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(throughPath1, throughPath2));

        assertThat(candidate.getConsumingEdgesOf(fromMultiplePaths),
                containsInAnyOrder(primedSameMemoryEdge(throughPath1, fromMultiplePaths),
                        primedSameMemoryEdge(throughPath2, fromMultiplePaths)));
        assertThat(candidate.getConsumingEdgesOf(leaf),
                containsInAnyOrder(primedSameMemoryEdge(fromMultiplePaths, leaf)));
        assertThat(candidate.getConsumingEdgesOf(throughPath1), empty());
        assertThat(candidate.getConsumingEdgesOf(throughPath2), empty());
        assertThat(candidate.getAllNodes(), containsInAnyOrder(throughPath1, throughPath2, fromMultiplePaths, leaf));
        assertThat(candidate.getAllEdges(), containsInAnyOrder(primedSameMemoryEdge(throughPath1, fromMultiplePaths),
                primedSameMemoryEdge(throughPath2, fromMultiplePaths), primedSameMemoryEdge(fromMultiplePaths, leaf)));
    }

    @Test
    public void collectionAccessorsReturnUnmodifiableDataStructures() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(1);
        Node<TestMemory, Integer> consumer = nodeAddingOneTo(dependency);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));
        Edge anEdge = new Edge(consumer, newSameMemoryDependency(dependency, PrimingMode.PRIMED));

        assertThrows(UnsupportedOperationException.class, () -> candidate.getConsumingEdgesOf(dependency).add(anEdge));
        assertThrows(UnsupportedOperationException.class, () -> candidate.getAllNodes().add(consumer));
        assertThrows(UnsupportedOperationException.class, () -> candidate.getAllEdges().add(anEdge));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalThrowsExceptionGivenNodeNotInCandidate() {
        Node<TestMemory, Integer> nodeNotInGraph = NodeMocks.node();
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> candidate.ignoringWillTriggerReplyCancelSignal(nodeNotInGraph));

        assertThat(thrown.getMessage(),
                allOf(containsString(nodeNotInGraph.toString()), containsString("not part of this graph")));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsTrueWithSingleConsumerOfSameMemoryDependency() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(12));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        assertTrue(candidate.ignoringWillTriggerReplyCancelSignal(dependency));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsTrueWithSingleConsumerOfNewMemoryDependency() {
        Node<TestChildMemory, Integer> dependency = Node.communalBuilder(TestChildMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.newMemoryDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(12));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        assertTrue(candidate.ignoringWillTriggerReplyCancelSignal(dependency));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsTrueWithRootNodeWithNoOtherConsumers() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(11));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        assertTrue(candidate.ignoringWillTriggerReplyCancelSignal(node));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsFalseWithSingleConsumerOfSameMemoryDependencyWhenDependencyIsAlsoARootNode() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(12));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node, dependency));

        assertFalse(candidate.ignoringWillTriggerReplyCancelSignal(dependency));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsFalseWithSingleConsumerOfAncestorMemoryDependency() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestChildMemory> nodeBuilder = Node.communalBuilder(TestChildMemory.class);
        nodeBuilder.ancestorMemoryDependency(dependency);
        Node<TestChildMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(12));
        GraphCandidate<TestChildMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        assertFalse(candidate.ignoringWillTriggerReplyCancelSignal(dependency));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalReturnsFalseWithMultipleConsumersOfOtherwiseCompatibleDependencies() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(11));
        Node.CommunalBuilder<TestMemory> consumer1Builder = Node.communalBuilder(TestMemory.class);
        consumer1Builder.sameMemoryUnprimedDependency(dependency);

        Node<TestMemory, Integer> consumer1 = consumer1Builder.type(Type.generic("consumer1"))
                .role(Role.of("consumer1"))
                .build(device -> CompletableFuture.completedFuture(12));

        Node.CommunalBuilder<TestMemory> consumer2Builder = Node.communalBuilder(TestMemory.class);
        consumer2Builder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer2 = consumer2Builder.type(Type.generic("consumer2"))
                .role(Role.of("consumer2"))
                .build(device -> CompletableFuture.completedFuture(22));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer1, consumer2));

        assertFalse(candidate.ignoringWillTriggerReplyCancelSignal(dependency));
    }
}
