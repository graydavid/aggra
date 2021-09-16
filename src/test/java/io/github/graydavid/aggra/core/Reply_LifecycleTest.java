package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.observerAfterStop;
import static io.github.graydavid.aggra.core.TestData.deviceWithEmptyFinalState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyCallingDevices.FinalState;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the logic in {@link Reply} related to the Replys lifecycle, including completion and the nodeForAllSignal. You
 * can find other Reply-related tests under Reply_*Test classes.
 */
public class Reply_LifecycleTest {
    // Default node: doesn't add behavior onto the backing CF on completion
    private final Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("test"))
            .role(Role.of("node"))
            .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
            .dependencyLifetime(DependencyLifetime.GRAPH)
            .build(device -> CompletableFuture.completedFuture(40));
    private final Caller caller = () -> Role.of("top-level-caller");
    private final TestMemory memory = new TestMemory(CompletableFuture.completedFuture(55));
    private final GraphCall<?> graphCall = mock(GraphCall.class);
    private final Observer observer = new Observer() {};

    @Test
    public void getNodeReturnsNodeReplyCreatedWith() {
        Reply<Integer> reply = Reply.forCall(caller, node);

        assertThat(reply.getNode(), is(node));
    }

    @Test
    public void startCompleteClosesDeviceImmediatelyButWaitsUntilLifetimeCompleteBeforeExecutingAfterStopObserver() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> new CompletableFuture<>());
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> new CompletableFuture<>());
        Reply<Integer> dependencyReply = Reply.forCall(caller, dependency);
        DependencyCallingDevice<?> device = mock(DependencyCallingDevice.class);
        FinalState finalState = FinalState.of(Set.of(dependencyReply));
        when(device.weaklyClose()).thenReturn(finalState);
        ObserverAfterStop<Integer> afterStopObserver = NodeMocks.observerAfterStop();
        Reply<Integer> nodeReply = Reply.forCall(caller, node);
        Runnable afterNodeReply = mock(Runnable.class);
        nodeReply.thenRun(afterNodeReply);

        nodeReply.startComplete(10, null, device, afterStopObserver);

        verify(device).weaklyClose();
        verifyNoInteractions(afterStopObserver, afterNodeReply);
        dependencyReply.startComplete(5, null, deviceWithEmptyFinalState(), NodeMocks.observerAfterStop());

        InOrder inOrder = inOrder(afterStopObserver, afterNodeReply);
        inOrder.verify(afterStopObserver).observe(10, null);
        inOrder.verify(afterNodeReply).run();
    }

    @Test
    public void allOfProbablyCompleteNodeForAllSignalIsCompleteImmediatelyForNoReplies() {
        CompletableFuture<Void> answer = Reply.allOfProbablyCompleteNodeForAllSignal(List.of()).toCompletableFuture();

        assertTrue(answer.isDone());
    }

    @Test
    public void allOfProbablyCompleteNodeForAllSignalCompletesWhenSingleEffectivelyNodeForAllReplyCompletes() {
        Reply<Integer> single = Reply.forCall(caller, node);

        CompletableFuture<Void> answer = Reply.allOfProbablyCompleteNodeForAllSignal(List.of(single))
                .toCompletableFuture();

        assertFalse(answer.isDone());
        single.startComplete(15, null, deviceWithEmptyFinalState(), observerAfterStop());
        assertTrue(answer.isDone());
    }

    @Test
    public void allOfProbablyCompleteNodeForAllSignalCompletesWhenAllEffectivelyNodeForAllReplysComplete() {
        Node<TestMemory, Integer> node2 = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("node2"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(40));
        Reply<Integer> first = Reply.forCall(caller, node);
        Reply<Integer> second = Reply.forCall(caller, node2);

        CompletableFuture<Void> answer = Reply.allOfProbablyCompleteNodeForAllSignal(List.of(first, second))
                .toCompletableFuture();
        assertFalse(answer.isDone());

        first.startComplete(15, null, deviceWithEmptyFinalState(), observerAfterStop());
        assertFalse(answer.isDone());

        second.startComplete(15, null, deviceWithEmptyFinalState(), observerAfterStop());
        assertTrue(answer.isDone());
    }

    @Test
    public void allOfProbablyCompleteNodeForAllSignalCompletesOnlyWhenAllTransitiveDependenciesComplete() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("dependency"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> dependencyResponse);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> consumeDependency = builder.sameMemoryUnprimedDependency(dependency);
        Node<TestMemory, Integer> consumer = builder.type(Type.generic("test"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> {
                    // Call but don't wait for dependency
                    device.call(consumeDependency);
                    return CompletableFuture.completedFuture(40);
                });
        Reply<Integer> consumerReply = consumer.call(caller, memory, graphCall, observer);
        Reply<Integer> dependencyReply = dependency.call(caller, memory, graphCall, observer);

        consumerReply.join();
        CompletableFuture<Void> nodeForAllConsumer = Reply.allOfProbablyCompleteNodeForAllSignal(List.of(consumerReply))
                .toCompletableFuture();
        assertFalse(dependencyReply.isDone());
        assertFalse(nodeForAllConsumer.isDone());

        dependencyResponse.complete(34);
        assertTrue(nodeForAllConsumer.isDone());
    }

    @Test
    public void allOfProbablyCompleteNodeForAllSignalDoesntGetConfusedByAlreadyCompleteNodeForAllReply() {
        Node<TestMemory, Integer> node2 = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("node2"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(40));
        Reply<Integer> incomplete = Reply.forCall(caller, node);
        Reply<Integer> complete = Reply.forCall(caller, node2);
        complete.startComplete(15, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertTrue(complete.isDone());
        CompletableFuture<Void> answer = Reply.allOfProbablyCompleteNodeForAllSignal(List.of(incomplete, complete))
                .toCompletableFuture();
        assertFalse(answer.isDone());

        incomplete.startComplete(15, null, deviceWithEmptyFinalState(), observerAfterStop());
        assertTrue(answer.isDone());
    }
}
