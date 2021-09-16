package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the select factory in ConditionNodes. You can find other ConditionNodes-related tests under
 * ConditionNodes_*Test classes.
 */
public class ConditionNodes_SelectTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void conditionTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(ConditionNodes.CONDITION_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void throwsExceptionGivenEmptyDependencies() {
        Node<TestMemory, Node<TestMemory, Integer>> select10 = FunctionNodes
                .synchronous(Role.of("select-10"), TestMemory.class)
                .getValue(nodeReturning(10));

        assertThrows(IllegalArgumentException.class,
                () -> ConditionNodes.startNode(Role.of("empty-dependencies"), TestMemory.class)
                        .select(select10, Collections.emptySet()));
    }

    private <T> Node<TestMemory, T> nodeReturning(T value) {
        return FunctionNodes.synchronous(Role.of("constant"), TestMemory.class).getValue(value);
    }

    @Test
    public void allowsOptionalSettingsToBeSet() {
        Node<TestMemory, Integer> dependency1 = nodeReturning(10);
        Node<TestMemory, Number> dependency2 = nodeReturning(20);
        Node<TestMemory, Node<TestMemory, Number>> selectDependency2 = FunctionNodes
                .synchronous(Role.of("select-dependency-2"), TestMemory.class)
                .getValue(dependency2);
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Number> selectSelection = ConditionNodes.startNode(Role.of("call-selected"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory)
                .select(selectDependency2, Set.of(dependency1, dependency2));

        assertThat(selectSelection.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(selectSelection.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(selectSelection.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        Node<TestMemory, Integer> dependency1 = nodeReturning(10);
        Node<TestMemory, Number> dependency2 = nodeReturning(20);
        Node<TestMemory, Node<TestMemory, Number>> selectDependency2 = FunctionNodes
                .synchronous(Role.of("select-dependency-2"), TestMemory.class)
                .getValue(dependency2);
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Number> selectSelection = ConditionNodes.startNode(Role.of("call-selected"), TestMemory.class)
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .select(selectDependency2, Set.of(dependency1, dependency2));

        assertThat(selectSelection.getGraphValidatorFactories(), empty());
    }

    @Test
    public void callsSelectedNode() {
        Node<TestMemory, Integer> dependency1 = nodeReturning(10);
        Node<TestMemory, Number> dependency2 = nodeReturning(20);
        Node<TestMemory, Node<TestMemory, Number>> selectDependency2 = FunctionNodes
                .synchronous(Role.of("select-dependency-2"), TestMemory.class)
                .getValue(dependency2);
        Node<TestMemory, Number> selectSelection = ConditionNodes.startNode(Role.of("call-selected"), TestMemory.class)
                .select(selectDependency2, Set.of(dependency1, dependency2));
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);

        Number result = TestData.callNodeInNewTestMemoryGraph(graphInput, selectSelection, observer).join();

        assertThat(result, is(20));
        assertThat(calledNodes, not(hasItem(dependency1)));
        assertThat(selectSelection.getType(), is(ConditionNodes.CONDITION_TYPE));
        assertThat(selectSelection.getRole(), is(Role.of("call-selected")));
        assertThat(selectSelection.getDependencies(),
                containsInAnyOrder(newSameMemoryDependency(selectDependency2, PrimingMode.PRIMED),
                        newSameMemoryDependency(dependency1, PrimingMode.UNPRIMED),
                        newSameMemoryDependency(dependency2, PrimingMode.UNPRIMED)));
    }

    @Test
    public void doesntAccessSelectedDependencyResponseInBehavior() {
        CountDownLatch selectFutureCreated = new CountDownLatch(1);
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        Node<TestMemory, Boolean> dependency = FunctionNodes
                .asynchronous(Role.of("wait-for-creation"), TestMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> selectFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, Node<TestMemory, Boolean>> selectDependency = FunctionNodes
                .synchronous(Role.of("select-dependency"), TestMemory.class)
                .getValue(dependency);
        Node<TestMemory, Boolean> selectSelection = ConditionNodes.startNode(Role.of("call-selected"), TestMemory.class)
                .select(selectDependency, Set.of(dependency));

        Reply<Boolean> selectFuture = TestData.callNodeInNewTestMemoryGraph(graphInput, selectSelection);
        selectFutureCreated.countDown();

        assertTrue(selectFuture.join());
    }
}
