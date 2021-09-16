package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.NodeMocks.observerAfterStop;
import static io.github.graydavid.aggra.core.TestData.arbitraryBehaviorWithCustomCancelAction;
import static io.github.graydavid.aggra.core.TestData.deviceWithEmptyFinalState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the logic in {@link Reply} related to cancellation. You can find other Reply-related tests under Reply_*Test
 * classes.
 */
public class Reply_CancellationTest {
    private final Caller caller = () -> Role.of("top-level-caller");

    @Test
    public void createsANonresponsiveToCancellationReplyForNodesThatDontSupportReplyCancelSignalPassiveHook() {
        Node<TestMemory, Integer> noPassiveHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("noPassiveHook"))
                .role(Role.of("noPassiveHook"))
                .build(device -> CompletableFuture.completedFuture(15));

        Reply<Integer> reply = Reply.forCall(caller, noPassiveHook);

        assertFalse(reply.isResponsiveToCancelSignal());
        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertFalse(reply.isCancelSignalTriggered());
        assertFalse(reply.supportsCustomCancelAction());
        assertThrows(UnsupportedOperationException.class, () -> reply.setCustomCancelAction(bool -> {
        }));
    }

    @Test
    public void createsAResponsiveToPassiveCancellationReplyForNodesThatSupportReplyCancelSignalPassiveHook() {
        Node<TestMemory, Integer> yesPassiveHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("yesPassiveHook"))
                .role(Role.of("yesPassiveHook"))
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(15));

        Reply<Integer> reply = Reply.forCall(caller, yesPassiveHook);

        assertTrue(reply.isResponsiveToCancelSignal());
        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        assertFalse(reply.supportsCustomCancelAction());
        assertThrows(UnsupportedOperationException.class, () -> reply.setCustomCancelAction(bool -> {
        }));
    }

    @Test
    public void createsAResponsiveToCancellationReplyForNodesThatSupportCustomCancelSignals() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertTrue(reply.isResponsiveToCancelSignal());
        assertTrue(reply.supportsCustomCancelAction());
        reply.setCustomCancelAction(bool -> {
        }); // doesn't throw
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaActionSetThenCancellationThenComplete() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertTrue(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaCancelThenActionSetThenComplete() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertTrue(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertTrue(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaActionSetThenCompleteThenCancellation() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertFalse(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertFalse(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaCompleteThenActionSetThenCancellation() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertFalse(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaCompleteThenCancellationThenActionSet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertFalse(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusViaCancellationThenCompleteThenActionSet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        assertTrue(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(bool -> {
        });

        assertTrue(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplyReflectsCorrectStatusIfCancelActionInProgressWhenCompleteStarts() {
        CountDownLatch cancelActionStarted = new CountDownLatch(1);
        CountDownLatch startCompleteFinished = new CountDownLatch(1);
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        CustomCancelAction action = mayInterrupt -> {
            cancelActionStarted.countDown();
            Try.callUnchecked(() -> startCompleteFinished.await(5, TimeUnit.SECONDS));
            assertFalse(reply.isDone());
        };

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(action);

        // Current Thread will block on triggerCancelSignal, so create new thread to complete Reply while action running
        CompletableFuture.runAsync(() -> {
            Try.callUnchecked(() -> cancelActionStarted.await(5, TimeUnit.SECONDS));
            assertTrue(reply.isCancelSignalTriggered());
            reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());
            startCompleteFinished.countDown();
        });

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
        assertThat(reply.join(), is(13));
    }

    @Test
    public void responsiveToCancellationReplySetCancelActionThrowsExceptionForNullAction() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertThrows(NullPointerException.class, () -> reply.setCustomCancelAction(null));
    }

    @Test
    public void responsiveToCancellationReplySetCancelActionThrowsExceptionIfDetectsActionAlreadySet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        reply.setCustomCancelAction(bool -> {
        });

        assertThrows(IllegalStateException.class, () -> reply.setCustomCancelAction(bool -> {
        }));
    }

    @Test
    public void responsiveToCancellationReplyCallsCustomActionViaActionSetThenCancellation() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(action);

        verifyNoInteractions(action);
        reply.triggerCancelSignal();

        verify(action).run(false);
    }

    @Test
    public void responsiveToCancellationReplyCallsCustomActionViaCancellationThenActionSet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        verifyNoInteractions(action);
        reply.setCustomCancelAction(action);

        verify(action).run(false);
    }

    @Test
    public void responsiveToCancellationReplyDoesntCallCustomActionViaActionSetThenCompleteThenCancellation() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.setCustomCancelAction(action);

        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());
        reply.triggerCancelSignal();

        verifyNoInteractions(action);
    }

    @Test
    public void responsiveToCancellationReplyDoesntCallCustomActionViaCancellationThenCompleteThenActionSet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);

        assertFalse(reply.isCancelSignalTriggered());
        reply.triggerCancelSignal();

        reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());
        reply.setCustomCancelAction(action);

        verifyNoInteractions(action);
    }

    @Test
    public void responsiveToCancellationReplyPassesCancelActionMayInterruptIfRunningToCancelAction() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(true));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        reply.setCustomCancelAction(action);
        reply.triggerCancelSignal();

        verify(action).run(true);
    }

    @Test
    public void responsiveToCancellationReplyCallsCancelActionOnlyOnceViaActionSetThenCancellation() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        reply.setCustomCancelAction(action);

        verifyNoInteractions(action);
        reply.triggerCancelSignal();

        verify(action).run(false);
        reply.triggerCancelSignal();
        reply.triggerCancelSignal();
        reply.triggerCancelSignal();

        verifyNoMoreInteractions(action);
    }

    @Test
    public void responsiveToCancellationReplyCallsCancelActionOnlyOnceViaCancellationThenActionSet() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        CustomCancelAction action = mock(CustomCancelAction.class);

        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        reply.triggerCancelSignal();

        verifyNoInteractions(action);
        reply.setCustomCancelAction(action);

        verify(action).run(false);
        reply.triggerCancelSignal();
        reply.triggerCancelSignal();
        reply.triggerCancelSignal();

        verifyNoMoreInteractions(action);
    }

    @Test
    public void responsiveToCancellationReplyCallsCancelActionExactlyOnceForSetAndTriggerRegardlessOfRaceConditions() {
        // Multi-threaded code is non-deterministic; so try multiple times. This chosen value isn't perfect in detecting
        // purposefully-introduced bugs, but it's a nice balance of being quick enough to run as a unit test every time.
        final int NUM_TRIES = 500;
        IntStream.range(0, NUM_TRIES)
                .forEach(
                        this::tryOnceToVerifyResponsiveToCancellationReplyCallsCancelActionExactlyOnceForSetAndTriggerRegardlessOfRaceConditions);
    }

    private void tryOnceToVerifyResponsiveToCancellationReplyCallsCancelActionExactlyOnceForSetAndTriggerRegardlessOfRaceConditions(
            int trialNum) {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        CustomCancelAction action = mock(CustomCancelAction.class);
        Runnable setCancel = () -> reply.setCustomCancelAction(action);
        Runnable cancel = () -> reply.triggerCancelSignal();

        new ConcurrentLoadGenerator(List.of(setCancel, cancel)).run();

        verify(action).run(false);
    }

    @Test
    public void responsiveToCancellationReplyCallsCancelActionAtMostOnceBeforeCompleteForSetTriggerAndCompleteRegardlessOfRaceConditions() {
        // Multi-threaded code is non-deterministic; so try multiple times. This chosen value isn't perfect in detecting
        // purposefully-introduced bugs, but it's a nice balance of being quick enough to run as a unit test every time.
        final int NUM_TRIES = 500;
        IntStream.range(0, NUM_TRIES)
                .forEach(
                        this::tryOnceToVerifyResponsiveToCancellationReplyCallsCancelActionAtMostOnceBeforeCompleteForSetTriggerAndCompleteRegardlessOfRaceConditions);
    }

    private void tryOnceToVerifyResponsiveToCancellationReplyCallsCancelActionAtMostOnceBeforeCompleteForSetTriggerAndCompleteRegardlessOfRaceConditions(
            int trialNum) {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        // Try to measure when reply is completed by chaining on the end of it. Imperfect, but sufficient.
        Runnable afterReplyComplete = mock(Runnable.class);
        reply.thenRun(afterReplyComplete);
        CustomCancelAction action = mock(CustomCancelAction.class);
        Runnable setCancel = () -> reply.setCustomCancelAction(action);
        Runnable cancel = () -> reply.triggerCancelSignal();
        Runnable complete = () -> reply.startComplete(13, null, deviceWithEmptyFinalState(), observerAfterStop());

        new ConcurrentLoadGenerator(List.of(setCancel, cancel, complete)).run();

        // atMostOnce isn't supported in InOrder verifications, so do the next best thing. Run it separately...
        verify(action, atMostOnce()).run(false);
        // ... and then make sure that action hasn't been called after reply complete
        InOrder inOrder = inOrder(afterReplyComplete, action);
        inOrder.verify(afterReplyComplete).run();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void responsiveToCancellationReplyToStringIncludesState() {
        Node<TestMemory, Integer> activeHook = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("activeHook"))
                .role(Role.of("activeHook"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Reply<Integer> reply = Reply.forCall(caller, activeHook);
        reply.triggerCancelSignal();

        assertThat(reply.toString(), containsString("state=CANCEL_TRIGGERED_CANCEL_ACTION_UNSET"));
    }
}
