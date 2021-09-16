package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

public class IterationNodesTest {
    private final CompletableFuture<Integer> parentInput = CompletableFuture.completedFuture(null);
    private final Executor asynchronousExecutor = Executors.newCachedThreadPool();
    private final Node<TestMemory, List<Integer>> parentList123 = FunctionNodes
            .synchronous(Role.of("start"), TestMemory.class)
            .getValue(List.of(1, 2, 3));
    private final Node<TestChildMemory, Integer> childRequireInput = Node.inputBuilder(TestChildMemory.class)
            .role(Role.of("require-input"))
            .build();
    private final Node<TestChildMemory, Integer> childInputTimes2 = FunctionNodes
            .synchronous(Role.of("multiply-input-times-2"), TestChildMemory.class)
            .apply(input -> 2 * input, childRequireInput);

    @Test
    public void collectionTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(IterationNodes.ITERATION_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void iterateThrowsExceptionGivenNullMemoryFactory() {
        assertThrows(NullPointerException.class,
                () -> IterationNodes.startNode(Role.of("all-input-times-2"), TestMemory.class)
                        .iterate(parentList123, null, childInputTimes2));
    }

    @Test
    public void iterateThrowsExceptionGivenNullInputsNode() {
        assertThrows(NullPointerException.class,
                () -> IterationNodes.startNode(Role.of("all-input-times-2"), TestMemory.class)
                        .iterate(null, TestChildMemory::new, childInputTimes2));
    }

    @Test
    public void collectToCustomThrowsExceptionGivenNullTransform() {
        assertThrows(NullPointerException.class,
                () -> IterationNodes.startNode(Role.of("all-input-times-2"), TestMemory.class)
                        .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                        .collectToCustom(null));
    }

    @Test
    public void allowsOptionalSettingsToBeSet() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, List<Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToOutputList();

        assertThat(allInputTimes2.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(allInputTimes2.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(allInputTimes2.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, List<Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToOutputList();

        assertThat(allInputTimes2.getGraphValidatorFactories(), empty());
    }

    @Test
    public void collectToOutputListIteratesOverInputCallsAndCollects() {
        Node<TestMemory, List<Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToOutputList();

        List<Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results, contains(2, 4, 6));
        assertThat(allInputTimes2.getType(), is(IterationNodes.ITERATION_TYPE));
        assertThat(allInputTimes2.getRole(), is(Role.of("all-input-times-2")));
        assertThat(allInputTimes2.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(parentList123, PrimingMode.PRIMED),
                        Dependencies.newNewMemoryDependency(childInputTimes2)));
    }

    @Test
    public void collectToOutputListProducesEmptyListGivenEmptyInput() {
        Node<TestMemory, List<Integer>> emptyStart = FunctionNodes.synchronous(Role.of("empty-start"), TestMemory.class)
                .getValue(Collections.emptyList());
        Node<TestMemory, List<Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(emptyStart, TestChildMemory::new, childInputTimes2)
                .collectToOutputList();

        List<Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results, emptyCollectionOf(Integer.class));
    }

    @Test
    public void collectToInputToOutputMapIteratesOverInputCallsAndCollects() {
        Node<TestMemory, Map<Integer, Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToInputToOutputMap();
        Map<Integer, Integer> expectedResults = Map.of(1, 2, 2, 4, 3, 6);

        Map<Integer, Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results, is(expectedResults));
        assertThat(allInputTimes2.getType(), is(IterationNodes.ITERATION_TYPE));
    }

    @Test
    public void collectToInputToOutputMapProducesEmptyMapGivenEmptyInput() {
        Node<TestMemory, List<Integer>> emptyStart = FunctionNodes.synchronous(Role.of("empty-start"), TestMemory.class)
                .getValue(Collections.emptyList());
        Node<TestMemory, Map<Integer, Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(emptyStart, TestChildMemory::new, childInputTimes2)
                .collectToInputToOutputMap();

        Map<Integer, Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results.size(), is(0));
    }

    @Test
    public void collectToInputToOutputMapProducesIllegalStateExceptionGivenInputWithDuplicates() throws Throwable {
        Node<TestMemory, List<Integer>> duplicateInputs = FunctionNodes
                .synchronous(Role.of("empty-start"), TestMemory.class)
                .getValue(List.of(1, 1));
        Node<TestMemory, Map<Integer, Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(duplicateInputs, TestChildMemory::new, childInputTimes2)
                .collectToInputToOutputMap();

        Reply<Map<Integer, Integer>> reply = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2);
        Throwable root = reply.getFirstNonContainerExceptionNow().get();
        assertThat(root, instanceOf(IllegalStateException.class));
        assertThat(root.getMessage(), allOf(containsString("1"), containsString("2")));
    }

    @Test
    public void collectToInputToOutputMapProducesUnmodifiableMap() {
        Node<TestMemory, List<Integer>> emptyStart = FunctionNodes.synchronous(Role.of("empty-start"), TestMemory.class)
                .getValue(Collections.emptyList());
        Node<TestMemory, Map<Integer, Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(emptyStart, TestChildMemory::new, childInputTimes2)
                .collectToInputToOutputMap();

        Map<Integer, Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results.size(), is(0));
        assertThrows(UnsupportedOperationException.class, () -> results.put(5, 13));
    }

    @Test
    public void collectToCustomIteratesOverInputCallsAndCollectsWithTransformer() {
        // Suppress justification: transform is a mock treated in a type-compatible way
        @SuppressWarnings("unchecked")
        BiFunction<List<Integer>, List<Integer>, String> transform = mock(BiFunction.class);
        Node<TestMemory, String> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToCustom(transform);
        List<Integer> expectedInput = List.of(1, 2, 3);
        List<Integer> expectedOutput = List.of(2, 4, 6);
        when(transform.apply(expectedInput, expectedOutput)).thenReturn("Passed");

        String results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results, is("Passed"));
        assertThat(allInputTimes2.getType(), is(IterationNodes.ITERATION_TYPE));
    }

    @Test
    public void collectToCustomProvidesUnmodifiableInputList() {
        BiFunction<List<Integer>, List<Integer>, List<Integer>> tryModifyInput = (inputs, outputs) -> {
            inputs.add(5); // should throw
            return outputs;
        };
        Node<TestMemory, List<Integer>> callTryModifyInput = IterationNodes
                .startNode(Role.of("call-modify-input"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToCustom(tryModifyInput);

        Reply<List<Integer>> results = TestData.callNodeInNewTestMemoryGraph(parentInput, callTryModifyInput);

        assertThat(results.getFirstNonContainerExceptionNow().get(), instanceOf(UnsupportedOperationException.class));
    }

    @Test
    public void collectToCustomProvidesUnmodifiableOutputList() {
        BiFunction<List<Integer>, List<Integer>, List<Integer>> tryModifyOutput = (inputs, outputs) -> {
            outputs.add(5); // should throw
            return outputs;
        };
        Node<TestMemory, List<Integer>> callTryModifyOutput = IterationNodes
                .startNode(Role.of("call-modify-output"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childInputTimes2)
                .collectToCustom(tryModifyOutput);

        Reply<List<Integer>> results = TestData.callNodeInNewTestMemoryGraph(parentInput, callTryModifyOutput);

        assertThat(results.getFirstNonContainerExceptionNow().get(), instanceOf(UnsupportedOperationException.class));
    }

    @Test
    public void collectToOutputListReturnsCalculateInputsExceptionIfCalculateInputsNodeFails() {
        IllegalStateException inputsError = new IllegalStateException();
        Node<TestMemory, List<Integer>> errorInputs = FunctionNodes
                .synchronous(Role.of("error-inputs"), TestMemory.class)
                .get(() -> {
                    throw inputsError;
                });
        Node<TestMemory, List<Integer>> callInputsError = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(errorInputs, TestChildMemory::new, childInputTimes2)
                .collectToOutputList();

        Reply<List<Integer>> reply = TestData.callNodeInNewTestMemoryGraph(parentInput, callInputsError);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), is(inputsError));
    }

    @Test
    public void collectToOutputListReturnsMemoryFactoryExceptionIfMemoryFactoryFails() {
        IllegalStateException factoryError = new IllegalStateException();
        Node<TestMemory, List<Integer>> callFactoryError = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                // javac has trouble inferring type variables
                .<Integer, TestChildMemory, Integer>iterate(parentList123, (scope, parent, input) -> {
                    throw factoryError;
                }, childInputTimes2)
                .collectToOutputList();

        Reply<List<Integer>> reply = TestData.callNodeInNewTestMemoryGraph(parentInput, callFactoryError);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), is(factoryError));
    }

    @Test
    public void collectToOutputListReturnsNodeInNewExceptionIfNodeInNewFails() {
        IllegalStateException nodeInNewError = new IllegalStateException();
        Node<TestChildMemory, Integer> childThrowError = FunctionNodes
                .synchronous(Role.of("throw-error"), TestChildMemory.class)
                .get(() -> {
                    throw nodeInNewError;
                });
        Node<TestMemory, List<Integer>> callNodeInNewError = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, childThrowError)
                .collectToOutputList();

        Reply<List<Integer>> reply = TestData.callNodeInNewTestMemoryGraph(parentInput, callNodeInNewError);

        assertThat(reply.getFirstNonContainerExceptionNow().get(), is(nodeInNewError));
    }

    @Test
    public void collectToOutputListDoesntBlockInBehaviorOnCalledNodesInNewMemory() {
        CountDownLatch resultsFutureCreated = new CountDownLatch(1);
        // This node would block the calling thread if its results were accessed in Behavior directly, rather than
        // accessed asynchronously. The node returns whether or not it was blocking (it waits 5 seconds to see).
        Node<TestChildMemory, Boolean> waitForSignalAndContinue = FunctionNodes
                .asynchronous(Role.of("wait-for-signal-and-continue"), TestChildMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> resultsFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, List<Boolean>> allInputWaits = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterate(parentList123, TestChildMemory::new, waitForSignalAndContinue)
                .collectToOutputList();

        Reply<List<Boolean>> resultsFuture = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputWaits);
        resultsFutureCreated.countDown(); // This unblocks waitForSignalAndContinue, allowing it to return true

        assertThat(resultsFuture.join(), contains(true, true, true));
    }

    @Test
    public void iterateNoInputToListIteratesOverInputCallsAndCollects() {
        List<MemoryNoInputFactory<TestMemory, TestChildMemory>> factories = List.of(
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(1), parent),
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(2), parent),
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(3), parent));
        Node<TestMemory, List<MemoryNoInputFactory<TestMemory, TestChildMemory>>> parentList123 = FunctionNodes
                .synchronous(Role.of("start"), TestMemory.class)
                .getValue(factories);
        Node<TestMemory, List<Integer>> allInputTimes2 = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterateNoInputToList(parentList123, childInputTimes2);

        List<Integer> results = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputTimes2).join();

        assertThat(results, contains(2, 4, 6));
        assertThat(allInputTimes2.getType(), is(IterationNodes.ITERATION_TYPE));
        assertThat(allInputTimes2.getRole(), is(Role.of("all-input-times-2")));
        assertThat(allInputTimes2.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(parentList123, PrimingMode.PRIMED),
                        Dependencies.newNewMemoryDependency(childInputTimes2)));
    }

    @Test
    public void iterateNoInputToListDoesntBlockInBehaviorOnCalledNodesInNewMemory() {
        CountDownLatch resultsFutureCreated = new CountDownLatch(1);
        // This node would block the calling thread if its results were accessed in Behavior directly, rather than
        // accessed asynchronously. The node returns whether or not it was blocking (it waits 5 seconds to see).
        Node<TestChildMemory, Boolean> waitForSignalAndContinue = FunctionNodes
                .asynchronous(Role.of("wait-for-signal-and-continue"), TestChildMemory.class)
                .executor(asynchronousExecutor)
                .get(Try.uncheckedSupplier(() -> resultsFutureCreated.await(5, TimeUnit.SECONDS)));
        List<MemoryNoInputFactory<TestMemory, TestChildMemory>> factories = List.of(
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(1), parent),
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(2), parent),
                (scope, parent) -> new TestChildMemory(scope, CompletableFuture.completedFuture(3), parent));
        Node<TestMemory, List<MemoryNoInputFactory<TestMemory, TestChildMemory>>> parentList123 = FunctionNodes
                .synchronous(Role.of("start"), TestMemory.class)
                .getValue(factories);
        Node<TestMemory, List<Boolean>> allInputWaits = IterationNodes
                .startNode(Role.of("all-input-times-2"), TestMemory.class)
                .iterateNoInputToList(parentList123, waitForSignalAndContinue);

        Reply<List<Boolean>> resultsFuture = TestData.callNodeInNewTestMemoryGraph(parentInput, allInputWaits);
        resultsFutureCreated.countDown();// This unblocks waitForSignalAndContinue, allowing it to return true

        assertThat(resultsFuture.join(), contains(true, true, true));
    }
}
