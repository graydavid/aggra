package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Tests the if-then factory in ConditionNodes. You can find other ConditionNodes-related tests under
 * ConditionNodes_*Test classes.
 */
public class ConditionNodes_IfThenTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void callsDependentNodeWhenConditionEvaluatesToTrue() {
        Node<TestMemory, Boolean> alwaysCall = FunctionNodes.synchronous(Role.of("always-call"), TestMemory.class)
                .getValue(true);
        Node<TestMemory, Integer> conditionallyCalled = nodeReturning(10);
        Node<TestMemory, Optional<Integer>> ifThenSelection = ConditionNodes
                .startNode(Role.of("conditionally-call"), TestMemory.class)
                .ifThen(alwaysCall, conditionallyCalled);

        Optional<Integer> result = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenSelection).join();

        assertThat(result, is(Optional.of(10)));
        assertThat(ifThenSelection.getType(), is(ConditionNodes.CONDITION_TYPE));
        assertThat(ifThenSelection.getRole(), is(Role.of("conditionally-call")));
        assertThat(ifThenSelection.getDependencies(),
                containsInAnyOrder(newSameMemoryDependency(alwaysCall, PrimingMode.PRIMED),
                        newSameMemoryDependency(conditionallyCalled, PrimingMode.UNPRIMED)));
    }

    private <T> Node<TestMemory, T> nodeReturning(T value) {
        return FunctionNodes.synchronous(Role.of("constant"), TestMemory.class).getValue(value);
    }

    @Test
    public void returnsEmptyOptionalWhenConditionEvaluatesToTrueAndDependentNodeReturnsNull() {
        Node<TestMemory, Boolean> alwaysCall = FunctionNodes.synchronous(Role.of("always-call"), TestMemory.class)
                .getValue(true);
        Node<TestMemory, Object> conditionallyCalled = nodeReturning(null);
        Node<TestMemory, Optional<Object>> ifThenSelection = ConditionNodes
                .startNode(Role.of("conditionally-call"), TestMemory.class)
                .ifThen(alwaysCall, conditionallyCalled);

        Optional<Object> result = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenSelection).join();

        assertThat(result, is(Optional.empty()));
    }

    @Test
    public void doesntCallOptionalDependencyNodeWhenConditionEvaluatesToFalse() {
        Node<TestMemory, Boolean> neverCall = FunctionNodes.synchronous(Role.of("never-call"), TestMemory.class)
                .getValue(false);
        Node<TestMemory, Integer> conditionallyCalled = nodeReturning(10);
        Node<TestMemory, Optional<Integer>> ifThenSelection = ConditionNodes
                .startNode(Role.of("conditionally-call"), TestMemory.class)
                .ifThen(neverCall, conditionallyCalled);
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);

        Optional<Integer> result = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenSelection, observer).join();

        assertThat(result, is(Optional.empty()));
        assertThat(calledNodes, not(hasItem(conditionallyCalled)));
    }

    @Test
    public void doesntAccessIfDependencyResponseInBehavior() {
        Node<TestMemory, Boolean> alwaysCall = FunctionNodes.synchronous(Role.of("always-call"), TestMemory.class)
                .getValue(true);
        CountDownLatch ifThenFutureCreated = new CountDownLatch(1);
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        Node<TestMemory, Boolean> waitForIfThenCreation = FunctionNodes
                .asynchronous(Role.of("wait-for-creation"), TestMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> ifThenFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, Optional<Boolean>> ifThenSelection = ConditionNodes
                .startNode(Role.of("conditionally-call"), TestMemory.class)
                .ifThen(alwaysCall, waitForIfThenCreation);

        Reply<Optional<Boolean>> ifThenFuture = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenSelection);
        ifThenFutureCreated.countDown();

        assertTrue(ifThenFuture.join().get());
    }
}
