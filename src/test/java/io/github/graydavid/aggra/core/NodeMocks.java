package io.github.graydavid.aggra.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCompositeCancelSignal;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * A utility to create Node-related mocks and matchers. Useful to add SuppressWarnings and a justifying explanation in a
 * single place. Justification: returned mocks only ever used in compatible way for declared type.
 */
public class NodeMocks {
    @SuppressWarnings("unchecked")
    public static <M extends Memory<?>> DependencyCallingDevice<M> dependencyCallingDevice() {
        return mock(DependencyCallingDevice.class);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Memory<?>, T> Behavior<M, T> behavior() {
        return mock(Behavior.class);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Memory<?>, T> BehaviorWithCompositeCancelSignal<M, T> behaviorWithCompositeCancelSignal() {
        return mock(BehaviorWithCompositeCancelSignal.class);
    }

    @SuppressWarnings("unchecked")
    public static <I> Memory<I> memory() {
        return mock(Memory.class);
    }

    public static <M extends Memory<?>, T> Node<M, T> node() {
        @SuppressWarnings("unchecked")
        Node<M, T> node = mock(Node.class);
        when(node.getCancelMode()).thenReturn(CancelMode.DEFAULT);
        return node;
    }

    @SuppressWarnings("unchecked")
    public static <T> ObserverBeforeStart<T> observerBeforeStart() {
        return mock(ObserverBeforeStart.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ObserverAfterStop<T> observerAfterStop() {
        return mock(ObserverAfterStop.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> Reply<T> reply() {
        return mock(Reply.class);
    }

    @SuppressWarnings("unchecked")
    public static DependencyCallingDevice<TestMemory> anyDependencyCallingDevice() {
        return any(DependencyCallingDevice.class);
    }
}
