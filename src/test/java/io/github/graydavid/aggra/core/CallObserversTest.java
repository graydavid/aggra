package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.CallObservers.ObservationType.BEHAVIOR;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.CUSTOM_CANCEL_ACTION;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.EVERY_CALL;
import static io.github.graydavid.aggra.core.CallObservers.ObservationType.FIRST_CALL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.CallObservers.AfterStopObservationException;
import io.github.graydavid.aggra.core.CallObservers.BeforeStartObservationException;
import io.github.graydavid.aggra.core.CallObservers.ObservationException;
import io.github.graydavid.aggra.core.CallObservers.ObservationFailureObserver;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.CallObservers.ObserverBeforeStart;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class CallObserversTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Node<TestMemory, ?> node = NodeMocks.node();
    private final TestMemory memory = new TestMemory(CompletableFuture.completedFuture(12));
    private final ObserverBeforeStart<Object> decorated = NodeMocks.observerBeforeStart();
    private final ObserverAfterStop<Object> stopObserver = NodeMocks.observerAfterStop();
    private final ObservationFailureObserver observationFailureObserver = mock(ObservationFailureObserver.class);

    @Test
    public void observerFaultTolerantThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> Observer.faultTolerant(null, observationFailureObserver));
        assertThrows(NullPointerException.class, () -> Observer.faultTolerant(mock(Observer.class), null));
    }

    @Test
    public void observerFaultTolerantIsFaultTolerantForObserveBeforeFirstCall() {
        Observer decorated = mock(Observer.class);
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observeBeforeFirstCall(caller, node, memory)).thenThrow(startThrowable);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> observerAfterStop = faultTolerant.observeBeforeFirstCall(caller, node, memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(12, null); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantDelegatesToObserveBeforeFirstCallOnSuccess() {
        Observer decorated = mock(Observer.class);
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(decorated.observeBeforeFirstCall(caller, node, memory)).thenReturn(observerAfterStop);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> result = faultTolerant.observeBeforeFirstCall(caller, node, memory);
        result.observe(16, null);

        verify(observerAfterStop).observe(16, null);
    }

    @Test
    public void observerFaultTolerantIsFaultTolerantForObserveBeforeBehavior() {
        Observer decorated = mock(Observer.class);
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observeBeforeBehavior(caller, node, memory)).thenThrow(startThrowable);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> observerAfterStop = faultTolerant.observeBeforeBehavior(caller, node, memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(12, null); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantDelegatesToObserveBeforeBehaviorOnSuccess() {
        Observer decorated = mock(Observer.class);
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(decorated.observeBeforeBehavior(caller, node, memory)).thenReturn(observerAfterStop);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> result = faultTolerant.observeBeforeBehavior(caller, node, memory);
        result.observe(16, null);

        verify(observerAfterStop).observe(16, null);
    }

    @Test
    public void observerFaultTolerantIsFaultTolerantForObserveBeforeCustomCancelAction() {
        Observer decorated = mock(Observer.class);
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observeBeforeCustomCancelAction(caller, node, memory)).thenThrow(startThrowable);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> observerAfterStop = faultTolerant.observeBeforeCustomCancelAction(caller, node,
                memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(null, new Throwable()); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantDelegatesToObserveBeforeCustomCancelActionOnSuccess() {
        Observer decorated = mock(Observer.class);
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(decorated.observeBeforeCustomCancelAction(caller, node, memory)).thenReturn(observerAfterStop);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<Object> result = faultTolerant.observeBeforeCustomCancelAction(caller, node, memory);
        Throwable throwable = new Error();
        result.observe(null, throwable);

        verify(observerAfterStop).observe(null, throwable);
    }

    @Test
    public void observerFaultTolerantIsFaultTolerantForObserveBeforeEveryCall() {
        Observer decorated = mock(Observer.class);
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observeBeforeEveryCall(caller, node, memory)).thenThrow(startThrowable);
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> observerAfterStop = faultTolerant.observeBeforeEveryCall(caller, node,
                memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(NodeMocks.reply(), startThrowable); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantDelegatesToObserveBeforeEveryCallOnSuccess() {
        // Mockito/java has compilation issues mocking beforeEveryCall (probably because of wildcards)
        ObserverAfterStop<? super Reply<?>> observerAfterStop = NodeMocks.observerAfterStop();
        Observer decorated = new Observer() {
            @Override
            public ObserverAfterStop<? super Reply<?>> observeBeforeEveryCall(Caller caller, Node<?, ?> node,
                    Memory<?> memory) {
                return observerAfterStop;
            }
        };
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> result = faultTolerant.observeBeforeEveryCall(caller, node, memory);
        Reply<?> reply = NodeMocks.reply();
        result.observe(reply, null);

        verify(observerAfterStop).observe(reply, null);
    }

    @Test
    public void observerFaultTolerantReturnsDoNothingGivenDoNothing() {
        Observer doNothing = Observer.doNothing();

        assertThat(Observer.faultTolerant(doNothing, observationFailureObserver), sameInstance(doNothing));
    }

    @Test
    public void observerFaultTolerantWithBuilderIsFaultTolerantForObserveBeforeFirstCall() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        when(observerBeforeStart.observe(FIRST_CALL, caller, node, memory)).thenThrow(startThrowable);
        Observer decorated = Observer.builder().observerBeforeFirstCall(observerBeforeStart).build();
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> observerAfterStop = faultTolerant.observeBeforeFirstCall(caller, node,
                memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(NodeMocks.reply(), startThrowable); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantWithBuilderIsFaultTolerantForObserveBeforeBehaviorFirstCall() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        when(observerBeforeStart.observe(BEHAVIOR, caller, node, memory)).thenThrow(startThrowable);
        Observer decorated = Observer.builder().observerBeforeBehavior(observerBeforeStart).build();
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> observerAfterStop = faultTolerant.observeBeforeBehavior(caller, node,
                memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(NodeMocks.reply(), startThrowable); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantWithBuilderIsFaultTolerantForObserveBeforeCustomCancelAction() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        when(observerBeforeStart.observe(CUSTOM_CANCEL_ACTION, caller, node, memory)).thenThrow(startThrowable);
        Observer decorated = Observer.builder().observerBeforeCustomCancelAction(observerBeforeStart).build();
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> observerAfterStop = faultTolerant.observeBeforeCustomCancelAction(caller,
                node, memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(NodeMocks.reply(), startThrowable); // Doesn't throw
    }

    @Test
    public void observerFaultTolerantWithBuilderIsFaultTolerantForObserveBeforeEveryCall() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        ObserverBeforeStart<Reply<?>> observerBeforeStart = NodeMocks.observerBeforeStart();
        when(observerBeforeStart.observe(EVERY_CALL, caller, node, memory)).thenThrow(startThrowable);
        Observer decorated = Observer.builder().observerBeforeEveryCall(observerBeforeStart).build();
        Observer faultTolerant = Observer.faultTolerant(decorated, observationFailureObserver);

        ObserverAfterStop<? super Reply<?>> observerAfterStop = faultTolerant.observeBeforeEveryCall(caller, node,
                memory);

        ArgumentCaptor<BeforeStartObservationException> captureException = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(captureException.capture());
        BeforeStartObservationException exception = captureException.getValue();
        assertThat(exception.getCause(), is(startThrowable));

        observerAfterStop.observe(NodeMocks.reply(), startThrowable); // Doesn't throw
    }

    @Test
    public void observerDoNothingDoesntThrowExceptionsForEachMethod() {
        Observer doNothing = Observer.doNothing();

        doNothing.observeBeforeFirstCall(caller, NodeMocks.node(), memory);
        doNothing.observeBeforeBehavior(caller, NodeMocks.node(), memory);
        doNothing.observeBeforeCustomCancelAction(caller, NodeMocks.node(), memory);
        doNothing.observeBeforeEveryCall(caller, NodeMocks.node(), memory);
    }

    @Test
    public void builderWithoutOverridesDoesntThrowExceptionsForEachMethod() {
        Observer defaultBuilt = Observer.builder().build();

        defaultBuilt.observeBeforeFirstCall(caller, NodeMocks.node(), memory);
        defaultBuilt.observeBeforeBehavior(caller, NodeMocks.node(), memory);
        defaultBuilt.observeBeforeCustomCancelAction(caller, NodeMocks.node(), memory);
        defaultBuilt.observeBeforeEveryCall(caller, NodeMocks.node(), memory);
    }

    @Test
    public void builderObserverBeforeFirstCallControlsBeforeFirstCall() {
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(observerBeforeStart.observe(FIRST_CALL, caller, node, memory)).thenReturn(observerAfterStop);
        Observer built = Observer.builder().observerBeforeFirstCall(observerBeforeStart).build();

        ObserverAfterStop<?> actual = built.observeBeforeFirstCall(caller, node, memory);

        assertThat(actual, sameInstance(observerAfterStop));
    }

    @Test
    public void builderObserverBeforeBehaviorControlsBeforeBehavior() {
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(observerBeforeStart.observe(BEHAVIOR, caller, node, memory)).thenReturn(observerAfterStop);
        Observer built = Observer.builder().observerBeforeBehavior(observerBeforeStart).build();

        ObserverAfterStop<?> actual = built.observeBeforeBehavior(caller, node, memory);

        assertThat(actual, sameInstance(observerAfterStop));
    }

    @Test
    public void builderObserverBeforeCustomCancelActionControlsBeforeCustomCancelAction() {
        ObserverBeforeStart<Object> observerBeforeStart = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> observerAfterStop = NodeMocks.observerAfterStop();
        when(observerBeforeStart.observe(CUSTOM_CANCEL_ACTION, caller, node, memory)).thenReturn(observerAfterStop);
        Observer built = Observer.builder().observerBeforeCustomCancelAction(observerBeforeStart).build();

        ObserverAfterStop<?> actual = built.observeBeforeCustomCancelAction(caller, node, memory);

        assertThat(actual, sameInstance(observerAfterStop));
    }

    @Test
    public void builderObserverBeforeEveryCallControlsBeforeEveryCall() {
        ObserverBeforeStart<Reply<?>> observerBeforeStart = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Reply<?>> observerAfterStop = NodeMocks.observerAfterStop();
        when(observerBeforeStart.observe(EVERY_CALL, caller, node, memory)).thenReturn(observerAfterStop);
        Observer built = Observer.builder().observerBeforeEveryCall(observerBeforeStart).build();

        ObserverAfterStop<?> actual = built.observeBeforeEveryCall(caller, node, memory);

        assertThat(actual, sameInstance(observerAfterStop));
    }

    @Test
    public void observerBeforeStartFaultTolerantThrowsExceptionGivenNullDecoratedObserver() {
        assertThrows(NullPointerException.class,
                () -> ObserverBeforeStart.faultTolerant(null, observationFailureObserver));
    }

    @Test
    public void observerBeforeStartFaultTolerantThrowsExceptionGivenNullObservationFailureObserver() {
        assertThrows(NullPointerException.class, () -> ObserverBeforeStart.faultTolerant(decorated, null));
    }

    @Test
    public void observerBeforeStartFaultTolerantObserveStartReturnsStopObserverThatCallsDesiredStopObserver() {
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver);
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        ObserverAfterStop<Object> returnedStopObserver = faultTolerant.observe(FIRST_CALL, caller, node, memory);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");
        returnedStopObserver.observe(result, resultThrowable);

        verify(stopObserver).observe(result, resultThrowable);
        verifyNoInteractions(observationFailureObserver);
    }

    @Test
    public void observerBeforeStartFaultTolerantObserveStartIsTolerantToFaultsInStartObserver() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observe(EVERY_CALL, caller, node, memory)).thenThrow(startThrowable);
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        faultTolerant.observe(EVERY_CALL, caller, node, memory);

        ArgumentCaptor<BeforeStartObservationException> exceptionCaptor = ArgumentCaptor
                .forClass(BeforeStartObservationException.class);
        verify(observationFailureObserver).observeBeforeStart(exceptionCaptor.capture());
        BeforeStartObservationException exception = exceptionCaptor.getValue();
        assertThat(exception.getCause(), is(startThrowable));
        assertThat(exception.getObservationType(), is(EVERY_CALL));
        assertThat(exception.getCaller(), is(caller));
        assertThat(exception.getNode(), is(node));
        assertThat(exception.getMemory(), is(memory));
    }

    @Test
    public void observerBeforeStartFaultTolerantReturnsDecoratedWhenAlreadyFaultTolerant() {
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        assertThat(ObserverBeforeStart.faultTolerant(faultTolerant, observationFailureObserver),
                sameInstance(faultTolerant));
    }

    @Test
    public void observerBeforeStartFaultTolerantObserveStartIsTolerantToFaultsInObservationFailureObserver() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenThrow(startThrowable);
        Throwable failureThrowable = new IllegalArgumentException("failureThrowable");
        doThrow(failureThrowable).when(observationFailureObserver).observeBeforeStart(any());
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        ObserverAfterStop<Object> returnedStopObserver = faultTolerant.observe(FIRST_CALL, caller, node, memory);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");
        returnedStopObserver.observe(result, resultThrowable);

        // No exception should have been thrown
    }

    @Test
    public void observerBeforeStartFaultTolerantObserveStartReturnsStopObserverThatIsTolerantToFaultsInTheRootStopObserver() {
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");
        Throwable stopThrowable = new IllegalArgumentException("stopThrowable");
        doThrow(stopThrowable).when(stopObserver).observe(result, resultThrowable);
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        ObserverAfterStop<Object> returnedStopObserver = faultTolerant.observe(FIRST_CALL, caller, node, memory);
        returnedStopObserver.observe(result, resultThrowable);

        ArgumentCaptor<AfterStopObservationException> exceptionCaptor = ArgumentCaptor
                .forClass(AfterStopObservationException.class);
        verify(observationFailureObserver).observeAfterStop(exceptionCaptor.capture());
        AfterStopObservationException exception = exceptionCaptor.getValue();
        assertThat(exception.getCause(), is(stopThrowable));
        assertThat(exception.getObservationType(), is(FIRST_CALL));
        assertThat(exception.getCaller(), is(caller));
        assertThat(exception.getNode(), is(node));
        assertThat(exception.getMemory(), is(memory));
        assertThat(exception.getObservedResult(), is(result));
        assertThat(exception.getObservedThrowable(), is(resultThrowable));
    }

    @Test
    public void observerBeforeStartFaultTolerantObserveStartReturnsStopObserverThatIsTolerantToFaultsInObservationFailureObserver() {
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");
        Throwable stopThrowable = new IllegalArgumentException("stopThrowable");
        doThrow(stopThrowable).when(stopObserver).observe(result, resultThrowable);
        Throwable failureThrowable = new IllegalArgumentException("failureThrowable");
        doThrow(failureThrowable).when(observationFailureObserver).observeAfterStop(any());
        ObserverBeforeStart<Object> faultTolerant = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        ObserverAfterStop<Object> returnedStopObserver = faultTolerant.observe(FIRST_CALL, caller, node, memory);
        returnedStopObserver.observe(result, resultThrowable);

        // No exception should have been thrown
    }

    @Test
    public void observerBeforeStartFaultTolerantIsTolerantToNullResponsesFromObserverBeforeStart() {
        when(decorated.observe(EVERY_CALL, caller, node, memory)).thenReturn(null);
        ObserverBeforeStart<Object> faultTolerantStart = ObserverBeforeStart.faultTolerant(decorated,
                observationFailureObserver);

        ObserverAfterStop<Object> faultTolerantStop = faultTolerantStart.observe(EVERY_CALL, caller, node, memory);
        faultTolerantStop.observe(13, null);

        ArgumentCaptor<AfterStopObservationException> exceptionCaptor = ArgumentCaptor
                .forClass(AfterStopObservationException.class);
        verify(observationFailureObserver).observeAfterStop(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue().getCause(), instanceOf(NullPointerException.class));
    }

    @Test
    public void observerBeforeStartCompositeThrowsExceptionGivenNullComponentsList() {
        assertThrows(NullPointerException.class,
                () -> ObserverBeforeStart.composite((List<ObserverBeforeStart<? super Object>>) null));
    }

    @Test
    public void observerBeforeStartCompositeThrowsExceptionGivenNullComponentsInList() {
        assertThrows(NullPointerException.class,
                () -> ObserverBeforeStart.composite(Arrays.asList((ObserverBeforeStart<Object>) null)));
    }

    @Test
    public void observerBeforeStartCompositeAllowsEmptyList() {
        ObserverBeforeStart<Object> composite = ObserverBeforeStart.composite(Collections.emptyList());
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");

        ObserverAfterStop<Object> returnedStopObserver = composite.observe(EVERY_CALL, caller, node, memory);
        returnedStopObserver.observe(result, resultThrowable);

        // No exception thrown
    }

    @Test
    public void observerBeforeStartCompositeCallsEachComponentInOrderToStartAndThenEachComponentInReverseOrderToEnd() {
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver);
        ObserverBeforeStart<Object> decorated2 = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> stopObserver2 = NodeMocks.observerAfterStop();
        when(decorated2.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver2);
        ObserverBeforeStart<Object> composite = ObserverBeforeStart.composite(List.of(decorated, decorated2));
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");

        ObserverAfterStop<Object> returnedStopObserver = composite.observe(FIRST_CALL, caller, node, memory);
        returnedStopObserver.observe(result, resultThrowable);

        InOrder inOrder = inOrder(decorated, decorated2, stopObserver, stopObserver2);
        inOrder.verify(decorated).observe(FIRST_CALL, caller, node, memory);
        inOrder.verify(decorated2).observe(FIRST_CALL, caller, node, memory);
        inOrder.verify(stopObserver2).observe(result, resultThrowable);
        inOrder.verify(stopObserver).observe(result, resultThrowable);
    }

    @Test
    public void observerBeforeStartCompositeOfFaultTolerantThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class,
                () -> ObserverBeforeStart.compositeOfFaultTolerant((List<ObserverBeforeStart<? super Object>>) null,
                        observationFailureObserver));
        assertThrows(NullPointerException.class, () -> ObserverBeforeStart.compositeOfFaultTolerant(List.of(), null));
    }

    @Test
    public void observerBeforeStartCompositeOfFaultTolerantThrowsExceptionGivenNullComponentsInList() {
        assertThrows(NullPointerException.class, () -> ObserverBeforeStart
                .compositeOfFaultTolerant(List.of((ObserverBeforeStart<Object>) null), observationFailureObserver));
    }

    @Test
    public void observerBeforeStartCompositeOfFaultTolerantAllowsEmptyList() {
        ObserverBeforeStart<Object> composite = ObserverBeforeStart.compositeOfFaultTolerant(Collections.emptyList(),
                observationFailureObserver);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");

        ObserverAfterStop<Object> returnedStopObserver = composite.observe(EVERY_CALL, caller, node, memory);
        returnedStopObserver.observe(result, resultThrowable);

        // No exception thrown
    }

    @Test
    public void observerBeforeStartCompositeOfFaultTolerantCreatesACompositeListOfFaultTolerantObservers() {
        Throwable startThrowable = new IllegalArgumentException("startThrowable");
        when(decorated.observe(FIRST_CALL, caller, node, memory)).thenThrow(startThrowable);
        ObserverBeforeStart<Object> decorated2 = NodeMocks.observerBeforeStart();
        ObserverAfterStop<Object> stopObserver2 = NodeMocks.observerAfterStop();
        when(decorated2.observe(FIRST_CALL, caller, node, memory)).thenReturn(stopObserver2);
        ObserverBeforeStart<Object> compositeOfFaultTolerant = ObserverBeforeStart
                .compositeOfFaultTolerant(List.of(decorated, decorated2), observationFailureObserver);
        Integer result = Integer.valueOf(5);
        Throwable resultThrowable = new IllegalArgumentException("resultThrowable");

        ObserverAfterStop<Object> returnedStopObserver = compositeOfFaultTolerant.observe(FIRST_CALL, caller, node,
                memory);
        returnedStopObserver.observe(result, resultThrowable);

        InOrder inOrder = inOrder(decorated, observationFailureObserver, decorated2, stopObserver2);
        inOrder.verify(decorated).observe(FIRST_CALL, caller, node, memory);
        inOrder.verify(observationFailureObserver).observeBeforeStart(any());
        inOrder.verify(decorated2).observe(FIRST_CALL, caller, node, memory);
        inOrder.verify(stopObserver2).observe(result, resultThrowable);
    }

    @Test
    public void observationFailureObserverDefaultObserveDoesntThrowException() {
        ObservationFailureObserver observer = new ObservationFailureObserver() {};

        observer.defaultObserve(null);
    }

    @Test
    public void observationFailureObserverObserveMethodsCallDefaultObserveVisitByDefault() {
        ObservationFailureObserver observer = spy(new ObservationFailureObserver() {});
        Throwable cause = new Throwable();
        BeforeStartObservationException beforeException = new BeforeStartObservationException(cause, FIRST_CALL, caller,
                node, memory);
        AfterStopObservationException afterException = new AfterStopObservationException(cause, caller, FIRST_CALL,
                node, memory, null, null);

        observer.observeBeforeStart(beforeException);
        observer.observeAfterStop(afterException);

        verify(observer).defaultObserve(beforeException);
        verify(observer).defaultObserve(afterException);
    }

    @Test
    public void observationFailureObserverFaultSwallowingSwallowsAllExceptionsThrownByDecorated() {
        ObservationFailureObserver throwing = new ObservationFailureObserver() {
            @Override
            public void observeBeforeStart(BeforeStartObservationException exception) {
                throw new Error();
            }

            @Override
            public void observeAfterStop(AfterStopObservationException exception) {
                throw new Error();
            }

            @Override
            public void defaultObserve(ObservationException exception) {
                throw new Error();
            }
        };
        ObservationFailureObserver swallowing = ObservationFailureObserver.faultSwallowing(throwing);
        Throwable cause = new Throwable();
        BeforeStartObservationException beforeException = new BeforeStartObservationException(cause, FIRST_CALL, caller,
                node, memory);
        AfterStopObservationException afterException = new AfterStopObservationException(cause, caller, FIRST_CALL,
                node, memory, null, null);

        swallowing.observeBeforeStart(beforeException);
        swallowing.observeAfterStop(afterException);
        swallowing.defaultObserve(beforeException);
    }

    @Test
    public void observationFailureObserverFaultSwallowingAllowsSuccessfulDecorated() {
        ObservationFailureObserver successful = new ObservationFailureObserver() {};
        ObservationFailureObserver swallowing = ObservationFailureObserver.faultSwallowing(successful);
        Throwable cause = new Throwable();
        BeforeStartObservationException beforeException = new BeforeStartObservationException(cause, FIRST_CALL, caller,
                node, memory);
        AfterStopObservationException afterException = new AfterStopObservationException(cause, caller, FIRST_CALL,
                node, memory, null, null);

        swallowing.observeBeforeStart(beforeException);
        swallowing.observeAfterStop(afterException);
        swallowing.defaultObserve(beforeException);
    }

    @Test
    public void beforeStartObservationExceptionConstructorThrowsExceptionsForNullArguments() {
        Throwable cause = new Throwable();

        assertThrows(NullPointerException.class,
                () -> new BeforeStartObservationException(cause, null, caller, node, memory));
        assertThrows(NullPointerException.class,
                () -> new BeforeStartObservationException(cause, FIRST_CALL, null, node, memory));
        assertThrows(NullPointerException.class,
                () -> new BeforeStartObservationException(cause, FIRST_CALL, caller, null, memory));
        assertThrows(NullPointerException.class,
                () -> new BeforeStartObservationException(cause, FIRST_CALL, caller, node, null));
    }

    @Test
    public void beforeStartObservationExceptionAccessorsReturnConstructorParameters() {
        Throwable cause = new Throwable();

        BeforeStartObservationException exception = new BeforeStartObservationException(cause, FIRST_CALL, caller, node,
                memory);

        assertThat(exception.getObservationType(), is(FIRST_CALL));
        assertThat(exception.getCaller(), is(caller));
        assertThat(exception.getNode(), is(node));
        assertThat(exception.getMemory(), is(memory));
    }

    @Test
    public void afterStopObservationExceptionConstructorThrowsExceptionsForNullArguments() {
        Throwable cause = new Throwable();
        Object observedResult = new Object();
        Throwable observedThrowable = new Throwable();

        assertThrows(NullPointerException.class, () -> new AfterStopObservationException(cause, null, FIRST_CALL, node,
                memory, observedResult, observedThrowable));
        assertThrows(NullPointerException.class, () -> new AfterStopObservationException(cause, caller, null, node,
                memory, observedResult, observedThrowable));
        assertThrows(NullPointerException.class, () -> new AfterStopObservationException(cause, caller, FIRST_CALL,
                null, memory, observedResult, observedThrowable));
        assertThrows(NullPointerException.class, () -> new AfterStopObservationException(cause, caller, FIRST_CALL,
                node, null, observedResult, observedThrowable));
    }

    @Test
    public void afterStopObservationExceptionConstructorAllowsNullObservedResultAndThrowable() {
        Throwable cause = new Throwable();

        AfterStopObservationException exception = new AfterStopObservationException(cause, caller, FIRST_CALL, node,
                memory, null, null);

        assertNull(exception.getObservedResult());
        assertNull(exception.getObservedThrowable());
    }

    @Test
    public void afterStopObservationExceptionAccessorsReturnConstructorParameters() {
        Throwable cause = new Throwable();
        Object observedResult = new Object();
        Throwable observedThrowable = new Throwable();

        AfterStopObservationException exception = new AfterStopObservationException(cause, caller, FIRST_CALL, node,
                memory, observedResult, observedThrowable);

        assertThat(exception.getObservationType(), is(FIRST_CALL));
        assertThat(exception.getCaller(), is(caller));
        assertThat(exception.getNode(), is(node));
        assertThat(exception.getMemory(), is(memory));
        assertThat(exception.getObservedResult(), is(observedResult));
        assertThat(exception.getObservedThrowable(), is(observedThrowable));
    }
}
