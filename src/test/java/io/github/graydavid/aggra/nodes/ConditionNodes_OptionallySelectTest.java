package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the optionally-select factory in ConditionNodes. You can find other ConditionNodes-related tests under
 * ConditionNodes_*Test classes.
 */
public class ConditionNodes_OptionallySelectTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void callsSelectedNodeWhenReturned() {
        Node<TestMemory, Integer> dependency1 = nodeReturning(10);
        Node<TestMemory, Number> dependency2 = nodeReturning(20);
        Node<TestMemory, Optional<Node<TestMemory, Number>>> selectDependency2 = FunctionNodes
                .synchronous(Role.of("select-dependency-2"), TestMemory.class)
                .getValue(Optional.of(dependency2));
        Node<TestMemory, Optional<Number>> selectSelection = ConditionNodes
                .startNode(Role.of("call-selected"), TestMemory.class)
                .optionallySelect(selectDependency2, Set.of(dependency1, dependency2));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);

        Optional<Number> result = TestData.callNodeInNewTestMemoryGraph(graphInput, selectSelection, observer).join();

        assertThat(result, is(Optional.of(20)));
        assertThat(calledNodes, not(hasItem(dependency1)));
        assertThat(selectSelection.getType(), is(ConditionNodes.CONDITION_TYPE));
        assertThat(selectSelection.getRole(), is(Role.of("call-selected")));
        assertThat(selectSelection.getDependencies(),
                containsInAnyOrder(newSameMemoryDependency(selectDependency2, PrimingMode.PRIMED),
                        newSameMemoryDependency(dependency1, PrimingMode.UNPRIMED),
                        newSameMemoryDependency(dependency2, PrimingMode.UNPRIMED)));
    }

    private <T> Node<TestMemory, T> nodeReturning(T value) {
        return FunctionNodes.synchronous(Role.of("constant"), TestMemory.class).getValue(value);
    }

    @Test
    public void returnsEmptyOptionalWhenNodeSelectedThatReturnsNull() {
        Node<TestMemory, Number> dependency = nodeReturning(null);
        Node<TestMemory, Optional<Node<TestMemory, Number>>> selectDependency = FunctionNodes
                .synchronous(Role.of("select-dependency"), TestMemory.class)
                .getValue(Optional.of(dependency));
        Node<TestMemory, Optional<Number>> optionallySelectSelection = ConditionNodes
                .startNode(Role.of("call-selected"), TestMemory.class)
                .optionallySelect(selectDependency, Set.of(dependency));

        Optional<Number> result = TestData.callNodeInNewTestMemoryGraph(graphInput, optionallySelectSelection).join();

        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void returnsEmptyOptionalWhenNoNodeSelectedFromPossibleDependencies() {
        Node<TestMemory, Number> dependency = nodeReturning(null);
        Node<TestMemory, Optional<Node<TestMemory, Number>>> selectNothing = FunctionNodes
                .synchronous(Role.of("select-nothing"), TestMemory.class)
                .getValue(Optional.empty());
        Node<TestMemory, Optional<Number>> optionallySelectSelection = ConditionNodes
                .startNode(Role.of("call-selected"), TestMemory.class)
                .optionallySelect(selectNothing, Set.of(dependency));

        Optional<Number> result = TestData.callNodeInNewTestMemoryGraph(graphInput, optionallySelectSelection).join();

        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void returnsEmptyOptionalWhenNoNodeSelectedFromNoPossibilities() {
        Node<TestMemory, Optional<Node<TestMemory, Number>>> selectNothing = FunctionNodes
                .synchronous(Role.of("select-nothing"), TestMemory.class)
                .getValue(Optional.empty());
        Node<TestMemory, Optional<Number>> optionallySelectSelection = ConditionNodes
                .startNode(Role.of("call-selected"), TestMemory.class)
                .optionallySelect(selectNothing, Collections.emptySet());

        Optional<Number> result = TestData.callNodeInNewTestMemoryGraph(graphInput, optionallySelectSelection).join();

        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void doesntAccessSelectedDependencyResponseInBehavior() {
        CountDownLatch selectFutureCreated = new CountDownLatch(1);
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        Node<TestMemory, Boolean> dependency = FunctionNodes
                .asynchronous(Role.of("wait-for-creation"), TestMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> selectFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, Optional<Node<TestMemory, Boolean>>> selectDependency = FunctionNodes
                .synchronous(Role.of("select-dependency"), TestMemory.class)
                .getValue(Optional.of(dependency));
        Node<TestMemory, Optional<Boolean>> optionallySelectSelection = ConditionNodes
                .startNode(Role.of("call-selected"), TestMemory.class)
                .optionallySelect(selectDependency, Set.of(dependency));

        Reply<Optional<Boolean>> selectFuture = TestData.callNodeInNewTestMemoryGraph(graphInput,
                optionallySelectSelection);
        selectFutureCreated.countDown();

        assertThat(selectFuture.join(), is(Optional.of(true)));
    }
}
