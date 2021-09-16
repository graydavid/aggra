package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.GraphValidators.AncestorMemoryRelationshipsDontCycleGraphValidator.MemoryEdge;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestGrandchildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the ancestorMemoryRelationshipsDontCycle validator from GraphValidators. You can find other
 * GraphValidators-related tests under GraphValidators_*Test classes.
 */
public class GraphValidators_AncestorMemoryRelationshipsDontCycleValidatorTest {
    @Test
    public void passesForEmptyGraph() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    @Test
    public void passesForSingleNodeGraph() {
        Node<TestMemory, Integer> node = TestData.nodeReturningValue(5);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    @Test
    public void passesGivenCyclesInSameMemoryDependencyMemory() {
        Node<TestMemory, Integer> dependency = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .build(device -> CompletableFuture.completedFuture(6));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    @Test
    public void passesGivenCyclesInNewMemoryDependencyMemories() {
        Node<TestMemory, Integer> leafNodeInParentMemory = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestChildMemory> parentCallingNodeInChildMemoryBuilder = Node
                .communalBuilder(TestChildMemory.class);
        parentCallingNodeInChildMemoryBuilder.newMemoryDependency(leafNodeInParentMemory);
        Node<TestChildMemory, Integer> parentCallingNodeInChildMemory = parentCallingNodeInChildMemoryBuilder
                .type(Type.generic("parent-calling"))
                .role(Role.of("in-child"))
                .build(device -> CompletableFuture.completedFuture(7));
        Node.CommunalBuilder<TestMemory> childCallingNodeInParentMemoryBuilder = Node.communalBuilder(TestMemory.class);
        childCallingNodeInParentMemoryBuilder.newMemoryDependency(parentCallingNodeInChildMemory);
        Node<TestMemory, Integer> childCallingNodeInParentMemory = childCallingNodeInParentMemoryBuilder
                .type(Type.generic("child-calling"))
                .role(Role.of("in-parent"))
                .build(device -> CompletableFuture.completedFuture(7));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(childCallingNodeInParentMemory));

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    @Test
    public void passesWithSingleAncestralRelationship() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(TestMemory.class, TestChildMemory.class)
                .build();

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    /** Utility class that helps build GraphCandidates based on desired ancestral Memory edges/relationships. */
    private static class AncestralRelationshipGraphCandidateBuilder {
        private final Set<Node<TestMemory, ?>> roots = new HashSet<>();

        public <C extends Memory<?>, D extends Memory<?>> AncestralRelationshipGraphCandidateBuilder addMemoryEdge(
                Class<C> consumerClass, Class<D> dependencyClass) {
            // Build a simple dependency in the dependency Memory
            Node<D, Integer> dependencyNode = Node.communalBuilder(dependencyClass)
                    .type(Type.generic("dependency"))
                    .role(Role.of("dependency"))
                    .build(device -> CompletableFuture.completedFuture(5));

            // Build a consumer in the consumer Memory accessing the dependency through an ancestral relationship
            Node.CommunalBuilder<C> consumerCallingDependencyNodeBuilder = Node.communalBuilder(consumerClass);
            consumerCallingDependencyNodeBuilder.ancestorMemoryDependency(dependencyNode);
            Node<C, Integer> consumerCallingDependencyNode = consumerCallingDependencyNodeBuilder
                    .type(Type.generic("consumer"))
                    .role(Role.of("consumer"))
                    .build(device -> CompletableFuture.completedFuture(7));

            // Figure out whether we can use the consumer as a root or need another bridge node to funciton as the root
            Node<TestMemory, Integer> root;
            if (consumerClass.equals(TestMemory.class)) {
                // Suppress justification: We know that consumerClass *is* TestMemory
                @SuppressWarnings("unchecked")
                Node<TestMemory, Integer> warning = (Node<TestMemory, Integer>) consumerCallingDependencyNode;
                root = warning;
            } else {
                // Build a bridge node to function as the root. It accesses the consumer through a new relationship
                Node.CommunalBuilder<TestMemory> rootCallingConsumerNodeBuilder = Node
                        .communalBuilder(TestMemory.class);
                rootCallingConsumerNodeBuilder.newMemoryDependency(consumerCallingDependencyNode);
                root = rootCallingConsumerNodeBuilder.type(Type.generic("root"))
                        .role(Role.of("root"))
                        .build(device -> CompletableFuture.completedFuture(7));

            }
            roots.add(root);

            return this;
        }

        public GraphCandidate<TestMemory> build() {
            return GraphCandidate.fromRoots(roots);
        }
    }

    @Test
    public void throwsExceptionGivenSimpleCycleInAncestorMemoryDependencyMemories() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(TestMemory.class, TestChildMemory.class)
                .addMemoryEdge(TestChildMemory.class, TestMemory.class)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate));

        assertThat(thrown.getMessage(), anyOf(containsString("TestMemory->TestChildMemory->TestMemory"),
                containsString("TestChildMemory->TestMemory->TestChildMemory")));
    }

    @Test
    public void throwsExceptionGivenLongerCycleInAncestorMemoryDependencyMemories() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(TestMemory.class, TestChildMemory.class)
                .addMemoryEdge(TestChildMemory.class, TestGrandchildMemory.class)
                .addMemoryEdge(TestGrandchildMemory.class, TestMemory.class)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate));

        assertThat(thrown.getMessage(),
                anyOf(containsString("TestMemory->TestChildMemory->TestGrandchildMemory->TestMemory"),
                        containsString("TestChildMemory->TestGrandchildMemory->TestMemory->TestChildMemory"),
                        containsString("TestGrandchildMemory->TestMemory->TestChildMemory->TestGrandchildMemory")));
    }

    @Test
    public void passesGivenMultiplePathsInAncestralMemoryDependencyMemoriesToSameMemory() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(AMemory.class, BMemory.class)
                .addMemoryEdge(AMemory.class, CMemory.class)
                .addMemoryEdge(BMemory.class, DMemory.class)
                .addMemoryEdge(CMemory.class, DMemory.class)
                .build();

        GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate);
    }

    // Useless Memory to make it easier to create Memory classes.
    public abstract static class UselessMemory extends Memory<Integer> {
        public UselessMemory() {
            super(mock(MemoryScope.class), CompletableFuture.completedFuture(5), Set.of(),
                    () -> new ConcurrentHashMapStorage());
        }
    }

    public static class AMemory extends UselessMemory {
    }

    public static class BMemory extends UselessMemory {
    }

    public static class CMemory extends UselessMemory {
    }

    public static class DMemory extends UselessMemory {
    }

    @Test
    public void canProbablyProperlyBacktrackToDetectCycles() {
        // This test relies on iteration order of the Memory classes in order to catch the desired flaw. I can't
        // guarantee or control iteration order. So, run the test 100 times to say it's "probably" okay.
        final int NUM_TRIES = 100;
        IntStream.range(0, NUM_TRIES).forEach(i -> testCanProbablyProperlyBacktrackToDetectCycles());
    }

    private void testCanProbablyProperlyBacktrackToDetectCycles() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(AMemory.class, BMemory.class)
                .addMemoryEdge(AMemory.class, CMemory.class)
                .addMemoryEdge(AMemory.class, DMemory.class)
                .addMemoryEdge(AMemory.class, TestMemory.class)
                .addMemoryEdge(AMemory.class, TestChildMemory.class)
                .addMemoryEdge(AMemory.class, TestGrandchildMemory.class)
                .addMemoryEdge(TestGrandchildMemory.class, AMemory.class)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate));

        assertThat(thrown.getMessage(), anyOf(containsString("AMemory->TestGrandchildMemory->AMemory"),
                containsString("TestGrandchildMemory->AMemory->TestGrandchildMemory")));
    }

    @Test
    public void probablyIteratesThroughAllMemoryClasses() {
        // This test relies on iteration order of the Memory classes in order to catch the desired flaw. I can't
        // guarantee or control iteration order. So, run the test 100 times to say it's "probably" okay.
        final int NUM_TRIES = 100;
        IntStream.range(0, NUM_TRIES).forEach(i -> testProbablyIteratesThroughAllMemoryClasses());
    }

    private void testProbablyIteratesThroughAllMemoryClasses() {
        GraphCandidate<TestMemory> candidate = new AncestralRelationshipGraphCandidateBuilder()
                .addMemoryEdge(AMemory.class, BMemory.class)
                .addMemoryEdge(CMemory.class, DMemory.class)
                .addMemoryEdge(EMemory.class, FMemory.class)
                .addMemoryEdge(GMemory.class, HMemory.class)
                .addMemoryEdge(TestMemory.class, TestChildMemory.class)
                .addMemoryEdge(TestChildMemory.class, TestMemory.class)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GraphValidators.ancestorMemoryRelationshipsDontCycle().validate(candidate));

        assertThat(thrown.getMessage(), anyOf(containsString("TestMemory->TestChildMemory->TestMemory"),
                containsString("TestChildMemory->TestMemory->TestChildMemory")));
    }

    public static class EMemory extends UselessMemory {
    }

    public static class FMemory extends UselessMemory {
    }

    public static class GMemory extends UselessMemory {
    }

    public static class HMemory extends UselessMemory {
    }

    @Test
    public void memoryEdgeEqualsObeysContract() {
        MemoryEdge memoryEdge = new MemoryEdge(AMemory.class, BMemory.class);
        MemoryEdge memoryEdgeCopy = new MemoryEdge(AMemory.class, BMemory.class);
        MemoryEdge memoryEdgeDifferentConsumer = new MemoryEdge(CMemory.class, BMemory.class);
        MemoryEdge memoryEdgeDifferentDependency = new MemoryEdge(AMemory.class, CMemory.class);

        assertThat(memoryEdge, equalTo(memoryEdge));
        assertThat(memoryEdge, equalTo(memoryEdgeCopy));
        assertThat(memoryEdgeCopy, equalTo(memoryEdge));
        assertThat(memoryEdge, not(equalTo(memoryEdgeDifferentConsumer)));
        assertThat(memoryEdgeDifferentConsumer, not(equalTo(memoryEdge)));
        assertThat(memoryEdge, not(equalTo(memoryEdgeDifferentDependency)));
        assertThat(memoryEdgeDifferentDependency, not(equalTo(memoryEdge)));
        assertThat(memoryEdge, not(equalTo(null)));
    }

    @Test
    public void memoryEdgeHashCodeObeysContract() {
        MemoryEdge memoryEdge = new MemoryEdge(AMemory.class, BMemory.class);
        MemoryEdge memoryEdgeCopy = new MemoryEdge(AMemory.class, BMemory.class);

        assertThat(memoryEdge.hashCode(), equalTo(memoryEdge.hashCode()));
        assertThat(memoryEdge.hashCode(), equalTo(memoryEdgeCopy.hashCode()));
    }
}
