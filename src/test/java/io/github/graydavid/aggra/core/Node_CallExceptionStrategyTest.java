package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.anyDependencyCallingDevice;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the ExceptionStrategy-based logic in {@link Node#call(Caller, Memory)}. You can find other Node-related tests
 * under Node_*Test classes.
 */
public class Node_CallExceptionStrategyTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Storage storage = new ConcurrentHashMapStorage();
    private final TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(55),
            () -> storage);
    private final GraphCall<?> graphCall = mock(GraphCall.class);
    private final Observer observer = new Observer() {};

    @Test
    public void returnsDoubleWrappedExceptionUsingExceptionStrategyWhenPrimedThrowsNonCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedDependencyException = internalThrowable -> internalThrowable
                .getCause()
                .getCause();

        testReturnsExceptionWhenPrimedDependencyThrowsException(new IllegalAccessError(),
                navigateToExpectedDependencyException);
        testReturnsExceptionWhenPrimedDependencyThrowsException(new IllegalArgumentException(),
                navigateToExpectedDependencyException);
        testReturnsExceptionWhenPrimedDependencyThrowsException(new CancellationException(),
                navigateToExpectedDependencyException);
        testReturnsExceptionWhenPrimedDependencyThrowsException(new CompletionException(new Throwable()),
                navigateToExpectedDependencyException);
    }

    private void testReturnsExceptionWhenPrimedDependencyThrowsException(Throwable dependencyException,
            Function<Throwable, Throwable> navigateToExpectedDependencyException) {
        Node<TestMemory, Integer> dependency = NodeMocks.node();
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("call-bad-to-prime"))
                .role(Role.of("call-bad-to-prime"));
        builder.primedDependency(dependency);
        Node<TestMemory, Integer> node = builder.build(behavior);
        when(dependency.call(node, memory, graphCall, observer)).thenThrow(dependencyException);

        testReturnsExceptionWhenNodeFails(node, dependencyException, navigateToExpectedDependencyException);
        verifyNoInteractions(behavior);
    }

    private void testReturnsExceptionWhenNodeFails(Node<TestMemory, Integer> nodeToFail, Throwable nodeException,
            Function<Throwable, Throwable> navigateToExpectedNodeException) {
        Reply<Integer> reply = nodeToFail.call(caller, memory, graphCall, observer);
        Reply<Integer> reply2 = nodeToFail.call(caller, memory, graphCall, observer);

        assertThat(reply, sameInstance(reply2));
        Throwable thrown = waitAndAccessInternalExceptionOf(reply);
        assertThat(thrown.getCause(), instanceOf(CallException.class));
        assertThat(navigateToExpectedNodeException.apply(thrown), sameInstance(nodeException));
    }

    // join and get may modify the exception stored internally by reply, but whenComplete will not
    private static Throwable waitAndAccessInternalExceptionOf(CompletionStage<?> reply) {
        while (!reply.toCompletableFuture().isDone()) {
            Thread.yield();
        }
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        reply.whenComplete((result, throwable) -> {
            if (throwable != null) {
                thrown.set(throwable);
            } ;
        });
        return thrown.get();
    }

    @Test
    public void returnsSingleWrappedExceptionUsingExceptionStrategyWhenPrimedDependencyThrowsCallException() {
        Function<Throwable, Throwable> navigateToExpectedDependencyException = internalThrowable -> internalThrowable
                .getCause();

        testReturnsExceptionWhenPrimedDependencyThrowsException(
                new CallException(caller, NodeMocks.node(), new Throwable()), navigateToExpectedDependencyException);
    }

    @Test
    public void returnsUnwrappedExceptionUsingExceptionStrategyWhenPrimedDependencyThrowsCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedDependencyException = internalThrowable -> internalThrowable;

        testReturnsExceptionWhenPrimedDependencyThrowsException(
                new CompletionException(new CallException(caller, NodeMocks.node(), new Throwable())),
                navigateToExpectedDependencyException);
    }

    @Test
    public void returnsDoubleWrappedExceptionUsingExceptionStrategyWhenBehaviorThrowsNonCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable
                .getCause()
                .getCause();

        testReturnsExceptionWhenBehaviorThrowsException(new IllegalAccessError(), navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorThrowsException(new IllegalArgumentException(),
                navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorThrowsException(new CancellationException(),
                navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorThrowsException(new CompletionException(new Throwable()),
                navigateToExpectedBehaviorException);
    }

    private void testReturnsExceptionWhenBehaviorThrowsException(Throwable behaviorException,
            Function<Throwable, Throwable> navigateToExpectedBehaviorException) {
        Behavior<TestMemory, Integer> behavior = NodeMocks.behavior();
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("call-bad-behavior"))
                .role(Role.of("call-bad-behavior"))
                .build(behavior);
        when(behavior.run(anyDependencyCallingDevice())).thenThrow(behaviorException);

        testReturnsExceptionWhenNodeFails(node, behaviorException, navigateToExpectedBehaviorException);
    }

    @Test
    public void returnsSingleWrappedExceptionUsingExceptionStrategyWhenBehaviorThrowsCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable
                .getCause();

        testReturnsExceptionWhenBehaviorThrowsException(new CallException(caller, NodeMocks.node(), new Throwable()),
                navigateToExpectedBehaviorException);
    }

    @Test
    public void returnsUnwrappedExceptionUsingExceptionStrategyWhenBehaviorThrowsCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable;

        testReturnsExceptionWhenBehaviorThrowsException(
                new CompletionException(new CallException(caller, NodeMocks.node(), new Throwable())),
                navigateToExpectedBehaviorException);
    }

    @Test
    public void returnsDoubleWrappedExceptionUsingExceptionStrategyWhenBehaviorReturnsNonCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable
                .getCause()
                .getCause();

        testReturnsExceptionWhenBehaviorReturnsException(new IllegalAccessError(), navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorReturnsException(new IllegalArgumentException(),
                navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorReturnsException(new CancellationException(),
                navigateToExpectedBehaviorException);
        testReturnsExceptionWhenBehaviorReturnsException(new CompletionException(new Throwable()),
                navigateToExpectedBehaviorException);
    }

    private void testReturnsExceptionWhenBehaviorReturnsException(Throwable behaviorException,
            Function<Throwable, Throwable> navigateToExpectedBehaviorException) {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("call-bad-behavior"))
                .role(Role.of("call-bad-behavior"))
                .build(device -> CompletableFuture.failedFuture(behaviorException));

        testReturnsExceptionWhenNodeFails(node, behaviorException, navigateToExpectedBehaviorException);
    }

    @Test
    public void returnsSingleWrappedExceptionUsingExceptionStrategyWhenBehaviorReturnsCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable
                .getCause();

        testReturnsExceptionWhenBehaviorReturnsException(new CallException(caller, NodeMocks.node(), new Throwable()),
                navigateToExpectedBehaviorException);
    }

    @Test
    public void returnsUnwrappedExceptionUsingExceptionStrategyWhenBehaviorReturnsCompletionCallException() {
        Function<Throwable, Throwable> navigateToExpectedBehaviorException = internalThrowable -> internalThrowable;

        testReturnsExceptionWhenBehaviorReturnsException(
                new CompletionException(new CallException(caller, NodeMocks.node(), new Throwable())),
                navigateToExpectedBehaviorException);
    }

    @Test
    public void canAddPrimedAndUnprimedDependencyExceptionsAsSuppressedToMainBehaviorExceptionUsingExceptionStrategy() {
        IllegalArgumentException primedException = new IllegalArgumentException();
        Node<TestMemory, Integer> primedDependency = exceptionalNode("throw-primed", primedException);
        UnsupportedOperationException unprimedException = new UnsupportedOperationException();
        Node<TestMemory, Integer> unprimedDependency = exceptionalNode("throw-unprimed", unprimedException);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main"))
                .role(Role.of("throwing"));
        builder.primedDependency(primedDependency);
        SameMemoryDependency<TestMemory, Integer> consumeUnprimed = builder
                .sameMemoryUnprimedDependency(unprimedDependency);
        IllegalStateException mainException = new IllegalStateException();
        Node<TestMemory, Integer> main = builder.build(device -> {
            Try.runCatchRuntime(() -> device.call(consumeUnprimed).join());
            throw mainException;
        });

        Reply<Integer> reply = main.call(caller, memory, graphCall, observer);

        Throwable mainNonContainer = reply.getFirstNonContainerExceptionNow().get();
        assertThat(mainNonContainer, is(mainException));
        Reply<Integer> primedReply = primedDependency.call(caller, memory, graphCall, observer);
        assertThat(primedReply.getFirstNonContainerExceptionNow().get(), is(primedException));
        Reply<Integer> unprimedReply = unprimedDependency.call(caller, memory, graphCall, observer);
        assertThat(unprimedReply.getFirstNonContainerExceptionNow().get(), is(unprimedException));
        assertThat(reply.getCallExceptionNow().get().getSuppressed(),
                arrayContainingInAnyOrder(primedReply.getExceptionNow().get(), unprimedReply.getExceptionNow().get()));
    }

    private static Node<TestMemory, Integer> exceptionalNode(String role, RuntimeException exception) {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("throwing"))
                .role(Role.of(role))
                .build(device -> {
                    throw exception;
                });
    }
}
