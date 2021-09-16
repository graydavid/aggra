package io.github.graydavid.aggra.core;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyCallingDevices.FinalState;

/**
 * Data/classes/methods used in multiple tests. Only data/classes/methods that having nothing interesting to say about
 * the test should be placed here; otherwise, centralization will take away from the test's readability.
 */
public class TestData {
    public static class TestMemory extends Memory<Integer> {
        public TestMemory(CompletionStage<Integer> input) {
            this(mock(MemoryScope.class), input);
        }

        public TestMemory(MemoryScope scope, CompletionStage<Integer> input) {
            super(scope, input, Set.of(), () -> new ConcurrentHashMapStorage());
        }

        public TestMemory(MemoryScope scope, CompletionStage<Integer> input,
                Supplier<? extends Storage> storageFactory) {
            super(scope, input, Set.of(), storageFactory);
        }
    }
    public static class TestChildMemory extends Memory<Integer> {
        private final TestMemory parent;

        public TestChildMemory(MemoryScope scope, CompletionStage<Integer> input, TestMemory parent) {
            super(scope, input, Set.of(parent), () -> new ConcurrentHashMapStorage());
            this.parent = parent;
        }

        public TestMemory getTestMemory() {
            return parent;
        }
    }
    public static class TestGrandchildMemory extends Memory<Integer> {
        private final TestChildMemory parent;

        public TestGrandchildMemory(MemoryScope scope, CompletionStage<Integer> input, TestChildMemory parent) {
            super(scope, input, Set.of(parent), () -> createStorage());
            this.parent = parent;
        }

        private static Storage createStorage() {
            return new ConcurrentHashMapStorage();
        }

        public TestChildMemory getTestChildMemory() {
            return parent;
        }
    }

    public static Node<TestMemory, Integer> nodeReturningValue(int value) {
        return nodeBackedBy(CompletableFuture.completedFuture(value));
    }

    public static Node<TestMemory, Integer> nodeBackedBy(CompletableFuture<Integer> behavior) {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("dependency"))
                .build(device -> behavior);
    }

    public static <T> Reply<T> callNodeInNewTestMemoryGraph(CompletableFuture<Integer> input, Node<TestMemory, T> node,
            Observer observer) {
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(node));
        return graph.openCancellableCall(scope -> new TestMemory(scope, input), observer).call(node);
    }

    public static <T> Reply<T> callNodeInNewTestMemoryGraph(CompletableFuture<Integer> input,
            Node<TestMemory, T> node) {
        return callNodeInNewTestMemoryGraph(input, node, Observer.doNothing());
    }

    public static <T> Reply<T> callNodeInNewTestMemoryGraph(Node<TestMemory, T> node) {
        return callNodeInNewTestMemoryGraph(CompletableFuture.completedFuture(55), node, Observer.doNothing());
    }

    public static Observer observerObservingEveryCallAndStoringCalledNodesIn(Set<Node<?, ?>> calledNodes) {
        ObserverBeforeStart<Reply<?>> everyCallObserver = (type, caller, node, memory) -> {
            calledNodes.add(node);
            return ObserverAfterStop.doNothing();
        };
        return Observer.builder().observerBeforeEveryCall(everyCallObserver).build();
    }

    public static BehaviorWithCustomCancelAction<TestMemory, Integer> arbitraryBehaviorWithCustomCancelAction(
            boolean cancelActionMayInterruptIfRunning) {
        return new BehaviorWithCustomCancelAction<>() {
            @Override
            public CustomCancelActionBehaviorResponse<Integer> run(DependencyCallingDevice<TestMemory> device,
                    CompositeCancelSignal signal) {
                return new CustomCancelActionBehaviorResponse<>(CompletableFuture.completedFuture(15), bool -> {
                });
            }

            @Override
            public boolean cancelActionMayInterruptIfRunning() {
                return cancelActionMayInterruptIfRunning;
            }
        };
    }

    public static DependencyCallingDevice<?> deviceWithEmptyFinalState() {
        DependencyCallingDevice<?> device = mock(DependencyCallingDevice.class);
        when(device.weaklyClose()).thenReturn(FinalState.empty());
        return device;
    }
}
