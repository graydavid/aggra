package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newNewMemoryDependency;
import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.Graph;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.NodeMocks;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class MemoryTripNodesTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);
    private final TestMemory parentMemory = new TestMemory(CompletableFuture.completedFuture(55));
    private final Node<TestChildMemory, Integer> nodeInChild = Node.inputBuilder(TestChildMemory.class)
            .role(Role.of("child-input"))
            .build();
    private final int rawInputToChild = 22;
    private final Node<TestMemory, Integer> inputToChild = FunctionNodes
            .synchronous(Role.of("inputToChild"), TestMemory.class)
            .getValue(rawInputToChild);

    @Test
    public void createMemoryTripTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(MemoryTripNodes.CREATE_MEMORY_TRIP_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void ancestorAccessorMemoryTripTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(MemoryTripNodes.ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void createMemoryAndCallThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                .createMemoryAndCall(null, inputToChild, nodeInChild));
        assertThrows(NullPointerException.class, () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                .createMemoryAndCall(TestChildMemory::new, null, nodeInChild));
    }

    @Test
    public void allowsOptionalSettings() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> callChildFromParentNode = MemoryTripNodes
                .startNode(Role.of("create-child-memory-node"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .graphValidatorFactory(validatorFactory)
                .createMemoryAndCall(TestChildMemory::new, inputToChild, nodeInChild);

        assertThat(callChildFromParentNode.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(callChildFromParentNode.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> callChildFromParentNode = MemoryTripNodes
                .startNode(Role.of("create-child-memory-node"), TestMemory.class)
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .createMemoryAndCall(TestChildMemory::new, inputToChild, nodeInChild);

        assertThat(callChildFromParentNode.getGraphValidatorFactories(), empty());
    }

    @Test
    public void createMemoryAndCallCreatesNodesToCreateMemoriesAndExecuteADependency() {
        Node<TestMemory, Integer> callChildFromParentNode = MemoryTripNodes
                .startNode(Role.of("create-child-memory-node"), TestMemory.class)
                .createMemoryAndCall(TestChildMemory::new, inputToChild, nodeInChild);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, callChildFromParentNode).join(),
                is(rawInputToChild));
        assertThat(callChildFromParentNode.getType(), is(MemoryTripNodes.CREATE_MEMORY_TRIP_TYPE));
        assertThat(callChildFromParentNode.getRole(), is(Role.of("create-child-memory-node")));
        assertThat(callChildFromParentNode.getDependencies(), containsInAnyOrder(
                newSameMemoryDependency(inputToChild, PrimingMode.PRIMED), newNewMemoryDependency(nodeInChild)));
    }

    @Test
    public void createMemoryNoInputAndCallThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                .createMemoryNoInputAndCall((MemoryNoInputFactory<TestMemory, TestChildMemory>) null, nodeInChild));
        assertThrows(NullPointerException.class,
                () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                        .createMemoryNoInputAndCall((scope, parent) -> new TestChildMemory(scope,
                                CompletableFuture.completedFuture(rawInputToChild), parentMemory), null));
    }

    @Test
    public void createMemoryNoInputAndCallCreatesNodesToCreateMemoriesAndExecuteADependency() {
        Node<TestMemory, Integer> callChildFromParentNode = MemoryTripNodes
                .startNode(Role.of("create-child-memory-node"), TestMemory.class)
                .createMemoryNoInputAndCall((scope, parent) -> new TestChildMemory(scope,
                        CompletableFuture.completedFuture(rawInputToChild), parentMemory), nodeInChild);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, callChildFromParentNode).join(),
                is(rawInputToChild));
        assertThat(callChildFromParentNode.getType(), is(MemoryTripNodes.CREATE_MEMORY_TRIP_TYPE));
        assertThat(callChildFromParentNode.getRole(), is(Role.of("create-child-memory-node")));
        assertThat(callChildFromParentNode.getDependencies(), containsInAnyOrder(newNewMemoryDependency(nodeInChild)));
    }

    @Test
    public void createMemoryNoInputAndCallWithNodeThrowsExceptionGivenNullArguments() {
        Node<TestMemory, MemoryNoInputFactory<TestMemory, TestChildMemory>> memoryFactory = FunctionNodes
                .synchronous(Role.of("memoryFactory"), TestMemory.class)
                .getValue((scope, parent) -> new TestChildMemory(scope,
                        CompletableFuture.completedFuture(rawInputToChild), parentMemory));

        assertThrows(NullPointerException.class, () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                .createMemoryNoInputAndCall((Node<TestMemory, MemoryNoInputFactory<TestMemory, TestChildMemory>>) null,
                        nodeInChild));
        assertThrows(NullPointerException.class, () -> MemoryTripNodes.startNode(Role.of("invalid"), TestMemory.class)
                .createMemoryNoInputAndCall(memoryFactory, null));
    }

    @Test
    public void createMemoryNoInputAndCallWithNodeCreatesNodesToCreateMemoriesAndExecuteADependency() {
        Node<TestMemory, MemoryNoInputFactory<TestMemory, TestChildMemory>> memoryFactory = FunctionNodes
                .synchronous(Role.of("memoryFactory"), TestMemory.class)
                .getValue((scope, parent) -> new TestChildMemory(scope,
                        CompletableFuture.completedFuture(rawInputToChild), parentMemory));
        Node<TestMemory, Integer> callChildFromParentNode = MemoryTripNodes
                .startNode(Role.of("create-child-memory-node"), TestMemory.class)
                .createMemoryNoInputAndCall(memoryFactory, nodeInChild);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, callChildFromParentNode).join(),
                is(rawInputToChild));
        assertThat(callChildFromParentNode.getType(), is(MemoryTripNodes.CREATE_MEMORY_TRIP_TYPE));
        assertThat(callChildFromParentNode.getRole(), is(Role.of("create-child-memory-node")));
        assertThat(callChildFromParentNode.getDependencies(), containsInAnyOrder(
                newSameMemoryDependency(memoryFactory, PrimingMode.PRIMED), newNewMemoryDependency(nodeInChild)));
    }

    @Test
    public void accessAncestorMemoryAndCallThrowsExceptionGivenNullAncestorAccessor() {
        assertThrows(NullPointerException.class,
                () -> MemoryTripNodes.startNode(Role.of("invalid"), TestChildMemory.class)
                        .accessAncestorMemoryAndCall(null, NodeMocks.node()));
    }

    @Test
    public void accessAncestorMemoryAndCallCreatesNodesToAccessAncestorMemoriesAndExecuteADependency() {
        Node<TestMemory, Integer> nodeInParent = Node.inputBuilder(TestMemory.class)
                .role(Role.of("parent-input"))
                .build();
        Node<TestChildMemory, Integer> callParentFromChildNode = MemoryTripNodes
                .startNode(Role.of("access-parent-node"), TestChildMemory.class)
                .accessAncestorMemoryAndCall(TestChildMemory::getTestMemory, nodeInParent);
        CompletableFuture<Integer> inputToParent = CompletableFuture.completedFuture(93);
        TestMemory parentMemory = new TestMemory(inputToParent);

        assertThat(callNodeInTestChildMemoryGraph(parentMemory, graphInput, callParentFromChildNode).join(),
                is(inputToParent.join()));
        assertThat(callParentFromChildNode.getType(), is(MemoryTripNodes.ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE));
        assertThat(callParentFromChildNode.getRole(), is(Role.of("access-parent-node")));
        assertThat(callParentFromChildNode.getDependencies(),
                containsInAnyOrder(Dependencies.newAncestorMemoryDependency(nodeInParent)));
    }

    private static <T> Reply<T> callNodeInTestChildMemoryGraph(TestMemory parentMemory,
            CompletableFuture<Integer> childInput, Node<TestChildMemory, T> nodeInChild) {
        Graph<TestChildMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(nodeInChild));
        return graph
                .openCancellableCall(scope -> new TestChildMemory(scope, childInput, parentMemory),
                        Observer.doNothing())
                .call(nodeInChild);
    }
}
