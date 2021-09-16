package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class EdgeTest {
    @Test
    public void throwsExceptionGivenNullParameters() {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"));
        SameMemoryDependency<?, ?> consumeDependency = nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.build(device -> CompletableFuture.completedFuture(4));

        assertThrows(NullPointerException.class, () -> new Edge(null, consumeDependency));
        assertThrows(NullPointerException.class, () -> new Edge(node, null));
    }

    @Test
    public void throwsExceptionConsumerDoesNotDependOnDependency() {
        Node.CommunalBuilder<TestMemory> hasNoDependenciesBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("no-dependencies"))
                .role(Role.of("no-dependencies"));
        hasNoDependenciesBuilder.sameMemoryUnprimedDependency(NodeMocks.node());
        Node<TestMemory, Integer> hasNoDependencies = hasNoDependenciesBuilder
                .build(device -> CompletableFuture.completedFuture(4));
        SameMemoryDependency<?, ?> randomDependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.PRIMED);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Edge(hasNoDependencies, randomDependency));
        assertThat(thrown.getMessage(),
                allOf(containsString(hasNoDependencies.toString()), containsString(randomDependency.toString()),
                        containsString(hasNoDependencies.getDependencies().toString())));
    }

    @Test
    public void accessorsExposeProperties() {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"));
        SameMemoryDependency<?, ?> consumeDependency = nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.build(device -> CompletableFuture.completedFuture(4));

        Edge edge = new Edge(node, consumeDependency);

        assertThat(edge.getConsumingNode(), is(node));
        assertThat(edge.getDependency(), is(consumeDependency));
    }

    @Test
    public void equalsObeysContract() {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Node<TestMemory, Integer> differentDependency = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node1"))
                .role(Role.of("node1"));
        SameMemoryDependency<?, ?> consumeDependency = nodeBuilder.sameMemoryUnprimedDependency(dependency);
        SameMemoryDependency<?, ?> consumeDifferentDependency = nodeBuilder
                .sameMemoryUnprimedDependency(differentDependency);
        Node<TestMemory, Integer> node = nodeBuilder.build(device -> CompletableFuture.completedFuture(4));

        Node.CommunalBuilder<TestMemory> differentNodeWithSameDependencyBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node2"))
                .role(Role.of("node2"));
        differentNodeWithSameDependencyBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> differentNodeWithSameDependency = differentNodeWithSameDependencyBuilder
                .build(device -> CompletableFuture.completedFuture(4));

        Edge edge = new Edge(node, consumeDependency);
        Edge edgeCopy = new Edge(node, consumeDependency);
        Edge edgeWithDifferentNode = new Edge(differentNodeWithSameDependency, consumeDependency);
        Edge edgeWithDifferentDependency = new Edge(node, consumeDifferentDependency);

        assertThat(edge, equalTo(edge));
        assertThat(edge, equalTo(edgeCopy));
        assertThat(edgeCopy, equalTo(edge));
        assertThat(edge, not(equalTo(edgeWithDifferentNode)));
        assertThat(edgeWithDifferentNode, not(equalTo(edge)));
        assertThat(edge, not(equalTo(edgeWithDifferentDependency)));
        assertThat(edge, not(equalTo(null)));
    }

    @Test
    public void hashCodeObeysContract() {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"));
        SameMemoryDependency<?, ?> consumeDependency = nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.build(device -> CompletableFuture.completedFuture(4));
        Edge edge = new Edge(node, consumeDependency);
        Edge edgeCopy = new Edge(node, consumeDependency);

        assertThat(edge.hashCode(), equalTo(edge.hashCode()));
        assertThat(edge.hashCode(), equalTo(edgeCopy.hashCode()));
    }

    @Test
    public void toStringContainsConsumingNodeAndDependency() {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node1"))
                .role(Role.of("node1"));
        SameMemoryDependency<?, ?> consumeDependency = nodeBuilder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.build(device -> CompletableFuture.completedFuture(4));
        Edge edge = new Edge(node, consumeDependency);

        String toString = edge.toString();

        assertThat(toString, containsString(node.toString()));
        assertThat(toString, containsString(consumeDependency.toString()));
    }
}
