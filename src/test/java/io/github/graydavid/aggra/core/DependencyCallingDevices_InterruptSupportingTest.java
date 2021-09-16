package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.InterruptModifier;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.MemoryBridges.AncestorMemoryAccessor;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the interrupt-supporting DependencyCallingDevices in {@link DependencyCallingDevices}. You can find other
 * DependencyCallingDevices-related tests under DependencyCallingDevices_*Test classes.
 */
public class DependencyCallingDevices_InterruptSupportingTest {
    private final DependencyCallingDevice<TestMemory> decorated = NodeMocks.dependencyCallingDevice();
    private final InterruptModifier interruptModifier = mock(InterruptModifier.class);
    // Suppress justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    private final Consumer<Throwable> interruptClearingExceptionSuppressor = mock(Consumer.class);
    private final DependencyCallingDevice<TestMemory> device = DependencyCallingDevices
            .createInterruptSupporting(decorated, interruptModifier, interruptClearingExceptionSuppressor);

    @Test
    public void constructorThrowsExceptionForNullArguments() {
        assertThrows(NullPointerException.class, () -> DependencyCallingDevices.createInterruptSupporting(null,
                interruptModifier, interruptClearingExceptionSuppressor));
        assertThrows(NullPointerException.class, () -> DependencyCallingDevices.createInterruptSupporting(decorated,
                null, interruptClearingExceptionSuppressor));
        assertThrows(NullPointerException.class,
                () -> DependencyCallingDevices.createInterruptSupporting(decorated, interruptModifier, null));
    }

    @Test
    public void getPrimingResponseDelegatesToDecorated() {
        // javac doesn't like using mockito to mock getPrimingResponse, so use a real thing
        DependencyCallingDevice<TestMemory> decorated = DependencyCallingDevices.createForCancelled(
                mock(GraphCall.class), NodeMocks.node(), new TestMemory(CompletableFuture.completedFuture(12)),
                Observer.doNothing());
        Try<? extends CompletionStage<Void>> decoratedResponse = decorated.getPrimingResponse();
        DependencyCallingDevice<TestMemory> device = DependencyCallingDevices.createInterruptSupporting(decorated,
                interruptModifier, interruptClearingExceptionSuppressor);

        Try<? extends CompletionStage<Void>> actualResponse = device.getPrimingResponse();

        assertThat(actualResponse, is(decoratedResponse));
    }

    @Test
    public void ignoreDelegatesToDecorated() {
        Reply<?> reply = NodeMocks.reply();

        device.ignore(reply);

        verify(decorated).ignore(reply);
    }

    @Test
    public void callDoesntModifyInterruptsForPrimedDependencies() {
        SameMemoryDependency<TestMemory, Integer> dependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.PRIMED);
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.call(dependency)).thenReturn(reply);

        Reply<?> actual = device.call(dependency);

        assertThat(actual, is(reply));
        verifyNoInteractions(interruptModifier);
    }

    @Test
    public void callUsesInterruptModifierToIsolateDecoratedCallForUnprimedDependencies() {
        SameMemoryDependency<TestMemory, Integer> dependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.UNPRIMED);
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.call(dependency)).thenReturn(reply);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(false);

        Reply<?> actual = device.call(dependency);

        assertThat(actual, is(reply));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        inOrder.verify(decorated).call(dependency);
        inOrder.verify(interruptModifier).setCurrentThreadInterrupt(false);
    }

    @Test
    public void dependencyCallsRestoreThreadInterruptEvenIfDecoratedThrowsException() {
        RuntimeException exception = new RuntimeException();
        SameMemoryDependency<TestMemory, Integer> dependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.UNPRIMED);
        when(decorated.call(dependency)).thenThrow(exception);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(true);

        RuntimeException actual = assertThrows(RuntimeException.class, () -> device.call(dependency));

        assertThat(actual, is(exception));
        verify(interruptModifier).setCurrentThreadInterrupt(true);
    }

    @Test
    public void callWithExecutorUsesInterruptModifierToIsolateDecoratedCallForUnprimedDependencies() {
        SameMemoryDependency<TestMemory, Integer> dependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.UNPRIMED);
        Reply<Integer> reply = NodeMocks.reply();
        Executor executor = mock(Executor.class);
        when(decorated.call(dependency, executor)).thenReturn(reply);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(false);

        Reply<?> actual = device.call(dependency, executor);

        assertThat(actual, is(reply));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        inOrder.verify(decorated).call(dependency, executor);
        inOrder.verify(interruptModifier).setCurrentThreadInterrupt(false);
    }

    @Test
    public void createMemoryAndCallUsesInterruptModifierToIsolateDecoratedCallForUnprimedDependencies() {
        MemoryFactory<TestMemory, Integer, TestChildMemory> memoryFactory = TestChildMemory::new;
        CompletableFuture<Integer> newInput = CompletableFuture.completedFuture(13);
        NewMemoryDependency<TestChildMemory, Integer> dependency = Dependencies
                .newNewMemoryDependency(NodeMocks.node());
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.createMemoryAndCall(memoryFactory, newInput, dependency)).thenReturn(reply);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(false);

        Reply<?> actual = device.createMemoryAndCall(memoryFactory, newInput, dependency);

        assertThat(actual, is(reply));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        inOrder.verify(decorated).createMemoryAndCall(memoryFactory, newInput, dependency);
        inOrder.verify(interruptModifier).setCurrentThreadInterrupt(false);
    }

    @Test
    public void createMemoryNoInputAndCallUsesInterruptModifierToIsolateDecoratedCallForUnprimedDependencies() {
        MemoryNoInputFactory<TestMemory, TestChildMemory> memoryFactory = (scope, parent) -> new TestChildMemory(scope,
                CompletableFuture.completedFuture(12), parent);
        NewMemoryDependency<TestChildMemory, Integer> dependency = Dependencies
                .newNewMemoryDependency(NodeMocks.node());
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.createMemoryNoInputAndCall(memoryFactory, dependency)).thenReturn(reply);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(false);

        Reply<?> actual = device.createMemoryNoInputAndCall(memoryFactory, dependency);

        assertThat(actual, is(reply));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        inOrder.verify(decorated).createMemoryNoInputAndCall(memoryFactory, dependency);
        inOrder.verify(interruptModifier).setCurrentThreadInterrupt(false);
    }

    @Test
    public void accessAncestorMemoryAndCallUsesInterruptModifierToIsolateDecoratedCallForUnprimedDependencies() {
        DependencyCallingDevice<TestChildMemory> decorated = NodeMocks.dependencyCallingDevice();
        DependencyCallingDevice<TestChildMemory> device = DependencyCallingDevices.createInterruptSupporting(decorated,
                interruptModifier, interruptClearingExceptionSuppressor);
        AncestorMemoryAccessor<TestChildMemory, TestMemory> ancestorMemoryAccessor = TestChildMemory::getTestMemory;
        AncestorMemoryDependency<TestMemory, Integer> dependency = Dependencies
                .newAncestorMemoryDependency(NodeMocks.node());
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.accessAncestorMemoryAndCall(ancestorMemoryAccessor, dependency)).thenReturn(reply);
        when(interruptModifier.clearCurrentThreadInterrupt()).thenReturn(false);

        Reply<?> actual = device.accessAncestorMemoryAndCall(ancestorMemoryAccessor, dependency);

        assertThat(actual, is(reply));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        inOrder.verify(decorated).accessAncestorMemoryAndCall(ancestorMemoryAccessor, dependency);
        inOrder.verify(interruptModifier).setCurrentThreadInterrupt(false);
    }

    @Test
    public void weaklyCloseCallsDecoratedAndThenClearsInterrupt() {
        DependencyCallingDevices.FinalState finalState = DependencyCallingDevices.FinalState.of(Set.of());
        when(decorated.weaklyClose()).thenReturn(finalState);

        DependencyCallingDevices.FinalState actual = device.weaklyClose();

        assertThat(actual, is(finalState));
        InOrder inOrder = Mockito.inOrder(interruptModifier, decorated);
        inOrder.verify(decorated).weaklyClose();
        inOrder.verify(interruptModifier).clearCurrentThreadInterrupt();
        verifyNoInteractions(interruptClearingExceptionSuppressor);
    }

    @Test
    public void weaklyCloseSuppressesInterruptClearingExceptions() {
        DependencyCallingDevices.FinalState finalState = DependencyCallingDevices.FinalState.of(Set.of());
        when(decorated.weaklyClose()).thenReturn(finalState);
        RuntimeException exception = new RuntimeException();
        when(interruptModifier.clearCurrentThreadInterrupt()).thenThrow(exception);

        DependencyCallingDevices.FinalState actual = device.weaklyClose();

        assertThat(actual, is(finalState));
        verify(interruptClearingExceptionSuppressor).accept(exception);
    }

    @Test
    public void modifiesCustomCancelActionsToRunExclusivelyFromDependencyCalls() {
        final int NUM_TRIES = 100; // The test is non-deterministic (see note inside it), so try multiple times
        IntStream.range(0, NUM_TRIES)
                .forEach(this::tryOnceToVerifyModifiesCustomCancelActionsToRunExclusivelyFromDependencyCalls);
    }

    private void tryOnceToVerifyModifiesCustomCancelActionsToRunExclusivelyFromDependencyCalls(int trialNum) {
        CountDownLatch decoratedCallStarted = new CountDownLatch(1);
        CountDownLatch allowDecoratedCallToContinue = new CountDownLatch(1);
        SameMemoryDependency<TestMemory, Integer> dependency = Dependencies.newSameMemoryDependency(NodeMocks.node(),
                PrimingMode.UNPRIMED);
        Reply<Integer> reply = NodeMocks.reply();
        when(decorated.call(dependency)).thenAnswer(invocation -> {
            decoratedCallStarted.countDown();
            Try.callUnchecked(() -> allowDecoratedCallToContinue.await(5, TimeUnit.SECONDS));
            return reply;
        });
        CustomCancelAction decoratedAction = mock(CustomCancelAction.class);

        CompletableFuture.runAsync(() -> device.call(dependency));
        Try.callUnchecked(() -> decoratedCallStarted.await(5, TimeUnit.SECONDS));

        CountDownLatch modifiedActionStarted = new CountDownLatch(1);
        CustomCancelAction modifiedAction = device.modifyCustomCancelAction(decoratedAction);
        CompletableFuture<Void> modifiedActionFuture = CompletableFuture.runAsync(() -> {
            modifiedActionStarted.countDown();
            modifiedAction.run(true);
        });
        Try.callUnchecked(() -> modifiedActionStarted.await(5, TimeUnit.SECONDS));

        // At this point, we can't be 100% sure that the modifiedAction thread didn't decide to pause (which would also
        // cause the test to pass). That's why this whole test is non-deterministic and needs to be run multiple times.
        verifyNoInteractions(decoratedAction);
        allowDecoratedCallToContinue.countDown();
        modifiedActionFuture.join();

        verify(decoratedAction).run(true);
    }

    @Test
    public void modifiesCustomCancelActionsToGiveUpLockOnException() {
        Error error = new Error();
        CustomCancelAction decoratedAction = mock(CustomCancelAction.class);
        doThrow(error).when(decoratedAction).run(false);
        CustomCancelAction modifiedAction = device.modifyCustomCancelAction(decoratedAction);

        Error thrown1 = assertThrows(Error.class, () -> modifiedAction.run(false));
        Error thrown2 = assertThrows(Error.class, () -> modifiedAction.run(false));

        assertThat(thrown1, is(error));
        assertThat(thrown2, is(error));
    }
}
