package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the consumerEnvelopsDependency validator from GraphValidators. You can find other GraphValidators-related tests
 * under GraphValidators_*Test classes.
 */
public class GraphValidators_ConsumerEnvelopsDependencyValidatorTest {
    private final Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("dependency"))
            .role(Role.of("dependency"))
            .build(device -> CompletableFuture.completedFuture(15));
    private final Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("consumer"))
            .role(Role.of("consumer"));

    @Test
    public void consumerEnvelopsDependencyThrowsExceptionIfArgumentsAreNull() {
        assertThrows(NullPointerException.class, () -> GraphValidators.consumerEnvelopsDependency(null, dependency));
        assertThrows(NullPointerException.class, () -> GraphValidators.consumerEnvelopsDependency(dependency, null));
    }

    @Test
    public void throwsExceptionWhenDependencyIsNotAPartOfTheCandidate() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(dependency, dependency);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(candidate));
    }

    @Test
    public void passesWhenDependencyConsumedByConsumerWhenNodesAreIdenticalEvenIfDependencyIsARoot() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(dependency));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(dependency, dependency);

        validator.validate(candidate);
    }

    @Test
    public void throwsExceptionWhenDependencyConsumedByDistinctConsumerButIsAlsoARoot() {
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer, dependency));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("dependency"));
    }

    @Test
    public void passesWhenDependencyConsumedIndirectlyByConsumerBeforeAnyRoot() {
        Node.CommunalBuilder<TestMemory> intermediateBuilder = Node.communalBuilder(TestMemory.class);
        intermediateBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> intermediate = intermediateBuilder.type(Type.generic("intermediate"))
                .role(Role.of("intermediate"))
                .build(device -> CompletableFuture.completedFuture(17));
        consumerBuilder.primedDependency(intermediate);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        validator.validate(candidate);
    }


    @Test
    public void throwsExceptionWhenDependencyConsumedIndirectlyByConsumerAndIntermediateIsRoot() {
        Node.CommunalBuilder<TestMemory> intermediateBuilder = Node.communalBuilder(TestMemory.class);
        intermediateBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> intermediate = intermediateBuilder.type(Type.generic("intermediate"))
                .role(Role.of("intermediate"))
                .build(device -> CompletableFuture.completedFuture(17));
        consumerBuilder.primedDependency(intermediate);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer, intermediate));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("intermediate->dependency"));
    }

    @Test
    public void throwsExceptionWhenDependencyConsumedByAnotherUnconnectedRootNodeAsWell() {
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));
        Node.CommunalBuilder<TestMemory> unconnectedBuilder = Node.communalBuilder(TestMemory.class);
        unconnectedBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> unconnected = unconnectedBuilder.type(Type.generic("unconnected"))
                .role(Role.of("unconnected"))
                .build(device -> CompletableFuture.completedFuture(17));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer, unconnected));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("unconnected->dependency"));
    }

    @Test
    public void throwsExceptionWhenDependencyConsumedByAnotherUnconnectedNonRootNodeAsWell() {
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));

        Node.CommunalBuilder<TestMemory> unconnectedIntermediateBuilder = Node.communalBuilder(TestMemory.class);
        unconnectedIntermediateBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> unconnectedIntermediate = unconnectedIntermediateBuilder
                .type(Type.generic("unconnected-intermediate"))
                .role(Role.of("unconnected-intermediate"))
                .build(device -> CompletableFuture.completedFuture(17));

        Node.CommunalBuilder<TestMemory> unconnectedRootBuilder = Node.communalBuilder(TestMemory.class);
        unconnectedRootBuilder.primedDependency(unconnectedIntermediate);
        Node<TestMemory, Integer> unconnectedRoot = unconnectedRootBuilder.type(Type.generic("unconnected-root"))
                .role(Role.of("unconnected-root"))
                .build(device -> CompletableFuture.completedFuture(18));

        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer, unconnectedRoot));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("unconnected-root->unconnected-intermediate->dependency"));
    }

    @Test
    public void passesWhenMultiplePathsConvergeOnConsumerBeforeAnyRoot() {
        Node.CommunalBuilder<TestMemory> intermediate1Builder = Node.communalBuilder(TestMemory.class);
        intermediate1Builder.primedDependency(dependency);
        Node<TestMemory, Integer> intermediate1 = intermediate1Builder.type(Type.generic("intermediate1"))
                .role(Role.of("intermediate1"))
                .build(device -> CompletableFuture.completedFuture(17));

        Node.CommunalBuilder<TestMemory> intermediate2Builder = Node.communalBuilder(TestMemory.class);
        intermediate2Builder.primedDependency(intermediate1);
        Node<TestMemory, Integer> intermediate2 = intermediate2Builder.type(Type.generic("intermediate2"))
                .role(Role.of("intermediate2"))
                .build(device -> CompletableFuture.completedFuture(18));

        consumerBuilder.primedDependency(dependency);
        consumerBuilder.primedDependency(intermediate2);
        Node<TestMemory, Integer> consumer = consumerBuilder.build(device -> CompletableFuture.completedFuture(16));

        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(consumer));
        GraphValidator validator = GraphValidators.consumerEnvelopsDependency(consumer, dependency);

        validator.validate(candidate);
    }
}
