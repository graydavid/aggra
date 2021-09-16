package io.github.graydavid.aggra.nodes;

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
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.ConcurrentHashMapStorage;
import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.Graph;
import io.github.graydavid.aggra.core.GraphCall;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Storage;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.onemoretry.Try;

public class CaptureResponseNodesTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void captureResponseTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(CaptureResponseNodes.CAPTURE_RESPONSE_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void allowsOptionalSettings() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("return-constant"))
                .build(device -> CompletableFuture.completedFuture(12));
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .graphValidatorFactory(validatorFactory)
                .captureResponse(dependency);

        assertThat(capture.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(capture.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("return-constant"))
                .build(device -> CompletableFuture.completedFuture(12));
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .captureResponse(dependency);

        assertThat(capture.getGraphValidatorFactories(), empty());
    }

    @Test
    public void captureResponseCapturesSuccessForNonNull() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("return-constant"))
                .build(device -> CompletableFuture.completedFuture(12));

        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .captureResponse(dependency);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, capture).join(), is(Try.ofSuccess(12)));
        assertThat(capture.getType(), is(CaptureResponseNodes.CAPTURE_RESPONSE_TYPE));
        assertThat(capture.getRole(), is(Role.of("capture")));
        assertThat(capture.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(dependency, PrimingMode.PRIMED)));
    }

    @Test
    public void captureResponseCapturesSuccessForNull() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("return-null"))
                .build(device -> CompletableFuture.completedFuture(null));

        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .captureResponse(dependency);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, capture).join(), is(Try.ofSuccess(null)));
    }

    @Test
    public void captureResponseCapturesFailureThrownOrReturnedByDependency() {
        IllegalArgumentException failure = new IllegalArgumentException("failure");
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("throw"))
                .build(device -> {
                    throw failure;
                });
        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .captureResponse(dependency);

        Try<Integer> captured = TestData.callNodeInNewTestMemoryGraph(graphInput, capture).join();

        CompletionException wrapped = (CompletionException) captured.getFailure().get();
        Throwable root = Reply.getFirstNonContainerException(wrapped).get();
        assertThat(root, is(failure));
    }

    @Test
    public void captureResponsePropagatesFailureThrownCallingDependency() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("normal"))
                .build(device -> CompletableFuture.completedFuture(34));
        Node<TestMemory, Try<Integer>> capture = CaptureResponseNodes.startNode(Role.of("capture"), TestMemory.class)
                .captureResponse(dependency);
        IllegalArgumentException failure = new IllegalArgumentException("failure");
        Storage normal = new ConcurrentHashMapStorage();
        Storage rejectDependency = new Storage() {
            @Override
            public <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
                if (node == dependency) {
                    throw failure;
                }
                return normal.computeIfAbsent(node, replySupplier);
            }
        };
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(dependency, capture));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(
                scope -> new TestMemory(scope, CompletableFuture.completedFuture(21), () -> rejectDependency),
                Observer.doNothing());

        Throwable thrown = assertThrows(Throwable.class, () -> graphCall.call(capture).join());

        Throwable root = Reply.getFirstNonContainerException(thrown).get();
        assertThat(root, is(failure));
    }
}
