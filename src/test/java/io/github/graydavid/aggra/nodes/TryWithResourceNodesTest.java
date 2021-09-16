package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

public class TryWithResourceNodesTest {
    private final AutoCloseable resource = mock(AutoCloseable.class);
    private final Node<TestMemory, AutoCloseable> resourceNode = FunctionNodes
            .synchronous(Role.of("produce-resource"), TestMemory.class)
            .graphValidatorFactory(TryWithResourceNodes.validateResourceConsumedByTryWithResource())
            .getValue(resource);
    private final Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("normal"), TestMemory.class)
            .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
            .getValue(34);

    @Test
    public void tryWithResourcesTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(TryWithResourceNodes.TRY_WITH_RESOURCE_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void throwsExceptionIfResourceNodeDoesntValidateThatItsConsumedByTryWithResourceNode() {
        Node<TestMemory, AutoCloseable> resourceNode = FunctionNodes
                .synchronous(Role.of("produce-resource"), TestMemory.class)
                .clearGraphValidatorFactories() // Not necessary, but for emphasis
                .getValue(resource);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TryWithResourceNodes.startNode(Role.of("invalid"), TestMemory.class)
                        .tryWith(() -> resourceNode, rN -> outputNode));

        assertThat(thrown.getMessage(), containsString(
                "resourceNodeFactory must produce a node that validates it's consumed by a TryWithResource node"));
    }

    @Test
    public void throwsExceptionIfOutputNodeDoesntDeclareThatItWaitsForAllDependencies() {
        Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("invalid-lifetime"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .getValue(1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TryWithResourceNodes.startNode(Role.of("invalid"), TestMemory.class)
                        .tryWith(() -> resourceNode, rN -> outputNode));

        assertThat(thrown.getMessage(), containsString(
                "outputNodeFactory must produce a node with a declared DependencyLifetime that waits for all dependencies"));
    }

    @Test
    public void throwsExceptionGivenNullCheckedExceptionTransformer() {
        assertThrows(NullPointerException.class,
                () -> TryWithResourceNodes.startNode(Role.of("invalid"), TestMemory.class)
                        .tryWith(() -> resourceNode, rN -> outputNode, null));
    }

    @Test
    public void allowsOptionalSettings() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory)
                .tryWith(() -> resourceNode, r -> outputNode);

        assertThat(tryWith.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(tryWith.getGraphValidatorFactories(), hasItem(validatorFactory));
    }

    @Test
    public void canClearUserControlledGraphValidatorFactories() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .tryWith(() -> resourceNode, r -> outputNode);

        assertThat(tryWith.getGraphValidatorFactories(), not(hasItem(validatorFactory)));
    }

    @Test
    public void returnsNodeThatValidatesItEnvelopsTheResourceNode() {
        Node<TestMemory, Integer> escaping = FunctionNodes.synchronous(Role.of("escaping"), TestMemory.class)
                .apply(res -> 2, resourceNode);
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(tryWith, escaping));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tryWith.createGraphValidators().forEach(validator -> validator.validate(candidate)));

        assertThat(thrown.getMessage(), containsString("escaping->" + resourceNode.getRole()));
    }

    @Test
    public void clearingGraphValidatorFactoriesDoesntAffectValidationThatTryWithEnvelopsTheResourceNode() {
        Node<TestMemory, Integer> escaping = FunctionNodes.synchronous(Role.of("escaping"), TestMemory.class)
                .apply(res -> 2, resourceNode);
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .clearGraphValidatorFactories()
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(tryWith, escaping));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tryWith.createGraphValidators().forEach(validator -> validator.validate(candidate)));

        assertThat(thrown.getMessage(), containsString("escaping->" + resourceNode.getRole()));
    }

    @Test
    public void returnsNodeWithValidatorThatThrowsExceptionForNullNode() {
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        tryWith.getGraphValidatorFactories()
                .stream()
                .forEach(factory -> assertThrows(NullPointerException.class, () -> factory.create(null)));
    }

    @Test
    public void returnsNodeWithWellDescribedGraphValidatorFactory() {
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Collection<String> validatorFactoryDescriptions = tryWith.getGraphValidatorFactories()
                .stream()
                .map(ForNodeGraphValidatorFactory::getDescription)
                .collect(Collectors.toList());
        assertThat(validatorFactoryDescriptions, hasItem("Envelops '" + resourceNode.getRole() + "'"));
    }

    @Test
    public void callsResourceThenOutputThenClosesResourceAndReturnsOutput() throws Exception {
        // Suppress justification: returned mocks only ever used in compatible way for declared type.
        @SuppressWarnings("unchecked")
        Supplier<Integer> outputSupplier = mock(Supplier.class);
        when(outputSupplier.get()).thenReturn(54);
        Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("normal"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .get(outputSupplier);
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        assertThat(tryWith.getType(), is(TryWithResourceNodes.TRY_WITH_RESOURCE_TYPE));
        assertThat(tryWith.getRole(), is(Role.of("try-with")));
        assertThat(tryWith.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(resourceNode, PrimingMode.PRIMED),
                        Dependencies.newSameMemoryDependency(outputNode, PrimingMode.UNPRIMED)));
        InOrder inOrder = inOrder(outputSupplier, resource);
        inOrder.verify(outputSupplier).get();
        inOrder.verify(resource).close();
        assertThat(reply.join(), is(54));
    }

    @Test
    public void doesntBlockInBehaviorCallToOutputNode() {
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        CountDownLatch resultsFutureCreated = new CountDownLatch(1);
        Node<TestMemory, Boolean> waitForSignalAndContinue = FunctionNodes
                .asynchronous(Role.of("wait-for-signal-and-continue"), TestMemory.class)
                .executor(asynchronousExecutor)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .get(Try.uncheckedSupplier(() -> resultsFutureCreated.await(5, TimeUnit.SECONDS)));
        Node<TestMemory, Boolean> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> waitForSignalAndContinue);

        Reply<Boolean> resultsFuture = TestData.callNodeInNewTestMemoryGraph(tryWith);
        resultsFutureCreated.countDown();// This unblocks waitForSignalAndContinue, allowing it to return true

        assertTrue(resultsFuture.join());
    }

    @Test
    public void neverCallsOutputAndPropagatesFailureIfResourceNodeFails() throws Exception {
        IllegalArgumentException resourceFailure = new IllegalArgumentException();
        Node<TestMemory, AutoCloseable> resourceNode = FunctionNodes.synchronous(Role.of("throw"), TestMemory.class)
                .graphValidatorFactory(TryWithResourceNodes.validateResourceConsumedByTryWithResource())
                .get(() -> {
                    throw resourceFailure;
                });
        // Suppress justification: returned mocks only ever used in compatible way for declared type.
        @SuppressWarnings("unchecked")
        Supplier<Integer> outputSupplier = mock(Supplier.class);
        Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("normal"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .get(outputSupplier);
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        verifyNoInteractions(outputSupplier);
        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(resourceFailure)));
    }

    @Test
    public void stillClosesResourceIfOutputNodeFails() throws Exception {
        IllegalArgumentException outputFailure = new IllegalArgumentException();
        Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("throwing"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .get(() -> {
                    throw outputFailure;
                });
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        verify(resource).close();
        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(outputFailure)));
    }

    @Test
    public void suppressesResourceCloseFailureOnCallExceptionIfOutputNodeAlsoFails() throws Exception {
        IllegalArgumentException outputFailure = new IllegalArgumentException();
        Node<TestMemory, Integer> outputNode = FunctionNodes.synchronous(Role.of("throwing"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .get(() -> {
                    throw outputFailure;
                });
        IllegalStateException closeFailure = new IllegalStateException();
        doThrow(closeFailure).when(resource).close();
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(outputFailure)));
        assertThat(reply.getCallExceptionNow().get().getSuppressed(), arrayContaining(closeFailure));
    }

    @Test
    public void returnsResourceCloseFailureIfOnlyItFails() throws Exception {
        IllegalStateException closeFailure = new IllegalStateException();
        doThrow(closeFailure).when(resource).close();
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(closeFailure)));
    }

    @Test
    public void byDefaultWrapsCheckedResourceCloseFailuresInRuntimeExceptions() throws Exception {
        Exception closeFailure = new Exception();
        doThrow(closeFailure).when(resource).close();
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        Throwable thrown = reply.getFirstNonContainerExceptionNow().get();
        assertThat(thrown, instanceOf(RuntimeException.class));
        assertThat(thrown.getCause(), is(closeFailure));
        assertFalse(Thread.interrupted());
    }

    @Test
    public void invokesResourceCloseCheckedExceptionTransformerWhenProvidedIfResourceFailuresIsChecked()
            throws Exception {
        // Suppress justification: returned mocks only ever used in compatible way for declared type.
        @SuppressWarnings("unchecked")
        Function<? super Throwable, RuntimeException> resourceCloseCheckedExceptionTransformer = mock(Function.class);
        Exception closeFailure = new Exception();
        RuntimeException transformedFailure = new RuntimeException();
        when(resourceCloseCheckedExceptionTransformer.apply(closeFailure)).thenReturn(transformedFailure);
        doThrow(closeFailure).when(resource).close();
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode, resourceCloseCheckedExceptionTransformer);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        assertThat(reply.getFirstNonContainerExceptionNow(), is(Optional.of(transformedFailure)));
    }

    @Test
    public void setsInterruptFlagWhenResourceCloseFailsWithInterruptedException() throws Exception {
        InterruptedException closeFailure = new InterruptedException();
        doThrow(closeFailure).when(resource).close();
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(tryWith);

        Throwable thrown = reply.getFirstNonContainerExceptionNow().get();
        assertThat(thrown, instanceOf(RuntimeException.class));
        assertThat(thrown.getCause(), is(closeFailure));
        assertTrue(Thread.interrupted());
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorThrowsExceptionIfResourceNodeNotPartOfGraphCandidate() {
        TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(candidate));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorThrowsExceptionIfTryWithResourceNodeNotPartOfGraphCandidate() {
        TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(resourceNode));
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("Expected exactly 1 TryWithResource"));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorPassesIfResourceConsumedByOneTryWithResourceNode() {
        Node<TestMemory, Integer> tryWith = TryWithResourceNodes.startNode(Role.of("try-with"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(tryWith));
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        validator.validate(candidate);
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorThrowsExceptionIfResourceNodeConsumedByNonTryWithResourceNode() {
        Node<TestMemory, Integer> otherTypeNode = FunctionNodes
                .synchronous(Role.of("consume-resource"), TestMemory.class)
                .apply(resource -> 12, resourceNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(otherTypeNode));
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("Expected exactly 1 TryWithResource"));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorThrowsExceptionIfResourceConsumedByMultipleTryWithResourceNode() {
        Node<TestMemory, Integer> tryWith1 = TryWithResourceNodes.startNode(Role.of("try-with-1"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        Node<TestMemory, Integer> tryWith2 = TryWithResourceNodes.startNode(Role.of("try-with-2"), TestMemory.class)
                .tryWith(() -> resourceNode, r -> outputNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(tryWith1, tryWith2));
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(),
                allOf(containsString("Expected exactly 1 TryWithResource"), containsString("but found '2'")));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceValidatorThrowsExceptionIfResourceConsumedByTryWithResourceNodeTargetingOtherResourceNode() {
        Node<TestMemory, AutoCloseable> resourceNode = FunctionNodes
                .synchronous(Role.of("produce-resource"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .getValue(resource);
        Node<TestMemory, AutoCloseable> otherResourceNode = FunctionNodes
                .synchronous(Role.of("other-resource-node"), TestMemory.class)
                .graphValidatorFactory(TryWithResourceNodes.validateResourceConsumedByTryWithResource())
                .getValue(resource);
        Node<TestMemory, AutoCloseable> tryWithOther = TryWithResourceNodes
                .startNode(Role.of("try-with-other"), TestMemory.class)
                .tryWith(() -> otherResourceNode, r -> resourceNode);
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(tryWithOther, resourceNode));
        GraphValidator validator = TryWithResourceNodes.validateResourceConsumedByTryWithResource()
                .create(resourceNode);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), allOf(containsString("Expected exactly 1 TryWithResource"),
                containsString("among consumers '[try-with-other]'")));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceGraphValidatorFactoryDescribesItselfWell() {
        ForNodeGraphValidatorFactory validatorFactory = TryWithResourceNodes
                .validateResourceConsumedByTryWithResource();

        assertThat(validatorFactory.getDescription(), is("Consumed by TryWithResource"));
    }

    @Test
    public void validateResourceConsumedByTryWithResourceGraphValidatorFactoryThrowsExceptionIfPassedNullNode() {
        assertThrows(NullPointerException.class,
                () -> TryWithResourceNodes.validateResourceConsumedByTryWithResource().create(null));
    }
}
