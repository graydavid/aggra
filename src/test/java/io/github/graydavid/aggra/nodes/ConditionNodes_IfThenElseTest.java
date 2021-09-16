package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the if-then-else factory in ConditionNodes. You can find other ConditionNodes-related tests under
 * ConditionNodes_*Test classes.
 */
public class ConditionNodes_IfThenElseTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void callsIfDependentNodeWhenConditionEvaluatesToTrue() {
        Node<TestMemory, Boolean> returnTrue = FunctionNodes.synchronous(Role.of("return-true"), TestMemory.class)
                .getValue(true);
        Node<TestMemory, Integer> ifDependency = nodeReturning(10);
        Node<TestMemory, Integer> elseDependency = nodeReturning(20);
        Node<TestMemory, Number> ifThenElseSelection = ConditionNodes.startNode(Role.of("call-if"), TestMemory.class)
                .ifThenElse(returnTrue, ifDependency, elseDependency);
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);

        Number result = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenElseSelection, observer).join();

        assertThat(result, is(10));
        assertThat(calledNodes, not(hasItem(elseDependency)));
        assertThat(ifThenElseSelection.getType(), is(ConditionNodes.CONDITION_TYPE));
        assertThat(ifThenElseSelection.getRole(), is(Role.of("call-if")));
        assertThat(ifThenElseSelection.getDependencies(),
                containsInAnyOrder(newSameMemoryDependency(returnTrue, PrimingMode.PRIMED),
                        newSameMemoryDependency(ifDependency, PrimingMode.UNPRIMED),
                        newSameMemoryDependency(elseDependency, PrimingMode.UNPRIMED)));
    }

    private <T> Node<TestMemory, T> nodeReturning(T value) {
        return FunctionNodes.synchronous(Role.of("constant"), TestMemory.class).getValue(value);
    }

    @Test
    public void callsElseDependentNodeWhenConditionEvaluatesToFalse() {
        Node<TestMemory, Boolean> returnFalse = FunctionNodes.synchronous(Role.of("return-false"), TestMemory.class)
                .getValue(false);
        Node<TestMemory, Integer> ifDependency = nodeReturning(10);
        Node<TestMemory, Number> elseDependency = nodeReturning(20);
        Node<TestMemory, Number> ifThenElseSelection = ConditionNodes.startNode(Role.of("call-if"), TestMemory.class)
                .ifThenElse(returnFalse, ifDependency, elseDependency);
        Set<Node<?, ?>> calledNodes = new HashSet<>();
        Observer observer = TestData.observerObservingEveryCallAndStoringCalledNodesIn(calledNodes);

        Number result = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenElseSelection, observer).join();

        assertThat(result, is(20));
        assertThat(calledNodes, not(hasItem(ifDependency)));
    }

    @Test
    public void doesntAccessDependenciesResponseInBehavior() {
        Node<TestMemory, Boolean> returnTrue = FunctionNodes.synchronous(Role.of("return-true"), TestMemory.class)
                .getValue(true);
        CountDownLatch ifThenElseFutureCreated = new CountDownLatch(1);
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        Node<TestMemory, Boolean> waitForIfThenElseCreation = FunctionNodes
                .asynchronous(Role.of("wait-for-creation"), TestMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> ifThenElseFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, Boolean> ifThenSelection = ConditionNodes
                .startNode(Role.of("conditionally-call"), TestMemory.class)
                .ifThenElse(returnTrue, waitForIfThenElseCreation, waitForIfThenElseCreation);

        Reply<Boolean> ifThenFuture = TestData.callNodeInNewTestMemoryGraph(graphInput, ifThenSelection);
        ifThenElseFutureCreated.countDown();

        assertTrue(ifThenFuture.join());
    }
}
