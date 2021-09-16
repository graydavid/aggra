/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.nodes.FunctionNodes;
import io.github.graydavid.naryfunctions.ThreeAryFunction;
import io.github.graydavid.onemoretry.Try;

/**
 * The response from calling a Node: {@link Node#call(Caller, Memory)}.
 *
 * The main purpose of this class is to provide a CompletableFuture-like response to calling a Node that users can use
 * to orchestrate consumers and to access the value of the computation... but with one big caveat: it doesn't contain
 * any of CompletableFuture's mutation methods. Direct modifications of a Node response (e.g. by completing or
 * cancelling it) by arbitrary, unknown actors would run counter to Node's design, where the value its response takes is
 * determined solely by the node itself (or at least collectively agreed-upon, in the case of cancellation).
 * 
 * For ease of use, this class does implement the CompletionStage interface, but is neither a Future nor
 * CompletableFuture itself. It's not a Future, because it doesn't implement any sort of cancellation behavior and
 * having a cancel method would be misleading. It's not a CompletableFuture, because even though there are indications
 * in the CompletableFuture javadoc and some forums
 * (https://stackoverflow.com/questions/26579139/how-to-subclass-completablefuture,
 * http://jsr166-concurrency.10961.n7.nabble.com/How-to-subclass-CompletableFuture-td11382.html , and
 * http://cs.oswego.edu/pipermail/concurrency-interest/2015-January/013600.html ), I can't find any official guidance
 * that disabling all mutation methods on a CompletableFuture subclass would be supported by that framework. In
 * addition, java could add new mutation methods to CompletableFuture with any new version, and so NodeReply would be
 * constantly trying to hit a moving target.
 * 
 * In order not to reimplement CompletableFuture's internal functionality, NodeReply delegates to an internal backing
 * CompletableFuture field. In a sense, NodeReply's job is to prevent modifications to that underlying CompletableFuture
 * through its interface. In support of that, NodeReply will not return the backing CompletableFuture directly
 * (otherwise, it could be modified with its mutation methods) or return any other response that would indirectly allow
 * for modification. In particular, {@link #toCompletableFuture()} returns a copy of the backing CompletableFuture; so,
 * yes, clients can mutate that copy, but the backing CompletableFuture will remain untouched. Incidentally,
 * {@link #toCompletableFuture()} provides access to the full set of methods on a CompletableFuture as well (unlike this
 * class), should the client need that (just remember the disconnect with the mutation methods and the backing
 * CompletableFuture). For the CompletionStage methods, this class only guarantees to return a CompletionStage. They may
 * return anything (e.g. the response from the backing CompletableFuture's method call directly or some wrapped version
 * of that); so clients should not cast it to anything specifically (which would be an implementation detail) and should
 * instead rely on the response's {@link CompletionStage#toCompletableFuture()} for any such needs.
 * 
 * Completion:
 * 
 * Nodes will indicate to their Reply when they should start completing. This may happen, for example, when the Behavior
 * is done, when a priming error happens (under certain PrimingFailureStrategys), when a Node detects various
 * cancellation signals, etc. Node is in control of that. Meanwhile, Replys will actually complete when the Node's
 * dependencies finish their lifetimes, as per {@link Node#getMinimumDependencyLifetime()}. Reply is in charge of that.
 * 
 * Exceptional Responses:
 * 
 * There are a couple of nuances to accessing exceptional responses:<br>
 * 1. Internal representation exception (sometimes referred to as just "the exception") -- the Reply's internally-stored
 * exceptional response. It's always a CompletionException caused by a CallException caused by an encountered exception
 * (more on that below). This exception is what will be passed to methods like "exceptionally", "handle", or
 * "whenComplete". It's important to differentiate between "internal" and "external" representations. E.g.
 * {@link #get()} (because it's based on CompletableFuture#get) will first unwrap the CompletionException and then wrap
 * the CallException in an ExecutionException. Meanwhile, {@link #join()} will just throw the CompletionException as is.
 * So, while the internal representation is constant, the external representation could be different. As for the
 * CallException, it may be indicative of this Node's failure alone, but it also might contain dependency failures as
 * suppressed exceptions. This depends on {@link Node#getExceptionStrategy()}.<br>
 * 2. Encountered exception -- this is the exception that the Node encountered during its call. This usually means the
 * exception thrown or returned from {@link Behavior} (but could also arise from other mechanisms). Despite this, you
 * usually don't want to interact with this exception. The reason is that CompletableFuture "likes to" add
 * CompletionExceptions between stages of its computation. Because the user is encouraged to use CompletableFuture to
 * create Behavior responses, this can lead to situations where the encountered exception is a CompletionException
 * itself and wraps the actual exception the user wants to throw. Worse still, this can vary based on how the user
 * decides to compute values. E.g., {@link FunctionNodes#synchronous(Role, Class)} will not yield a CompletionException
 * while {@link FunctionNodes#asynchronous(Role, Class, Executor)} will, even for the same executed function. These
 * types of additions are not easily avoidable, and users should expect them to be present in the Causal Chain. Note:
 * what actually constitutes the encountered exception is a combination of
 * {@link Node#call(Caller, Memory, CallObservers.Observer)} and the Node's ExceptionStrategy. <br>
 * 3. First non-container exception -- this is the first exception in the causal chain that's not a CompletionException,
 * CallException, or an ExecutionException. This is usually the exception that you want to interact with. It avoids the
 * extraneous CompletionException additions from the encountered exception or the possibly-unwrapping or re-wrapping
 * done by using "get" vs. "join", providing a constant value in spite of the way the Behavior might be implemented
 * internally.
 * 
 * @apiNote It's unfortunate that every Node call will need a wrapper object like this, but considering everything
 *          mentioned so far (preventing mutation and indicating that prevention through the interface), this choice
 *          seems like the best choice. If future data shows the creation of a new object too costly, then I can revisit
 *          this decision and perhaps create a subclass of CompletableFuture instead, just with the mutation methods
 *          overridden. I would also have to come up with a plan for handling new mutation methods java might add to
 *          CompletableFuture in future versions... while still remaining compatible with the version of java this
 *          library is targeted for (java 11+).
 * @apiNote In an ideal world, the CallException would itself be the internal exception rather than the surrounding
 *          CompletionException. However, CompletableFuture has a habit of wrapping exceptions in CompletionException
 *          (e.g. in the "compose" method). In addition, "join" will also wrap exceptions in CompletionExceptions if
 *          they already aren't. Creating exceptions can be relatively expensive, and I don't want to keep wrapping and
 *          unwrapping it in CompletionExceptions to keep the CallException as the internal representation. Instead, I'd
 *          rather stick with a CompletionException cause by a CallException to avoid that cost. This does give
 *          preference to calling "join" in the intrinsic behavior rather than "get", though, as the latter will unwrap
 *          the exception and wrap it in a different ExecutionException. All things considered, I think the
 *          CompletionException + CallException is the right balance.
 * @apiNote One option I thought of was to make CallException itself a CompletionException. This would certainly solve
 *          the need for wrapping in the "join" case. However, "get" would then unwrap the CallException and wrap its
 *          cause in an ExecutionException. The loss of debug information contained in the CallException would be very
 *          bad, and there's no way to prescribe users not to use "get" or its variants (and why would I want to?). So,
 *          this option is also out.
 * @apiNote instead of defining "First non-container exception" in addition to "encountered exception", another option I
 *          thought of was to remove any wrapping container exceptions from the encountered exception and call that the
 *          encountered exception. This is what CompletableFuture#get does (unwrapping any CompletionException and
 *          rewrapping the result in ExecutionException). However, I'm uncomfortable with discarding any potentially
 *          useful information that could be in the container exception, which the user may have thrown specifically (I
 *          don't know). Plus, I'm not convinced this would be a perfect solution in all cases. Instead, creating the
 *          "First non-container exception" concept and providing guidance around it seems like the best option.
 * 
 */
public abstract class Reply<T> implements CompletionStage<T> {
    private final CompletableFuture<T> backing;
    private final Caller firstCaller;
    private final Node<?, T> node;
    /*
     * A complimentary signal to the backing future. Whereas the backing future completes according to node's lifetime,
     * the nodeForAllSignal completes as if the node's lifetime were NODE_FOR_ALL. This concept is useful to control the
     * lifecycle of Graphs and Memorys, which for some actions must wait for all of their relevant nodes and transient
     * dependencies to complete.
     * 
     * This may or may not be different from the backing future, depending on the backing future's lifetime. However,
     * it's guaranteed that this signal will not complete before the backing future completes.
     */
    private final CompletableFuture<?> nodeForAllSignal;

    private Reply(CompletableFuture<T> backing, Caller firstCaller, Node<?, T> node) {
        this.backing = backing;
        this.firstCaller = firstCaller;
        this.node = node;
        this.nodeForAllSignal = node.getMinimumDependencyLifetime().calculateReplyNodeForAllSignal(backing);
    }

    /**
     * Package private to facilitate testing. This class decorates a backing future, but we don't want to allow clients
     * to have access to that backing future (perhaps mutating it). We want this class to be the only ones that can
     * complete it. At the same time, we want to be able to test all of the decorated methods... which would just be too
     * difficult without having access to the decorated future. So, package private is a compromise. Only the test suite
     * Reply_AsDecoratingBackingFutureTest should be using this method in the Aggra framework.
     */
    static <T> Reply<T> ofBackingForCall(CompletableFuture<T> backing, Caller firstCaller, Node<?, T> node) {
        return factoryForNode(node).apply(backing, firstCaller, node);
    }

    private static <T> ThreeAryFunction<CompletableFuture<T>, Caller, Node<?, T>, Reply<T>> factoryForNode(
            Node<?, T> node) {
        CancelMode cancelMode = node.getCancelMode();
        if (cancelMode.supportsCustomAction()) {
            return ResponsiveToCancelReply::new;
        }
        return cancelMode.supportsReplySignalPassiveHook() ? ResponsiveToPassiveCancelReply::new
                : NonresponsiveToCancelReply::new;
    }

    public static <T> Reply<T> forCall(Caller firstCaller, Node<?, T> node) {
        return ofBackingForCall(new CompletableFuture<>(), firstCaller, node);
    }

    /**
     * A Reply that is completely non-responsive to the Reply cancellation signal. See
     * {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is appropriate.
     */
    private static class NonresponsiveToCancelReply<T> extends Reply<T> {
        private NonresponsiveToCancelReply(CompletableFuture<T> backing, Caller firstCaller, Node<?, T> node) {
            super(backing, firstCaller, node);
        }

        @Override
        void setCustomCancelAction(CustomCancelAction cancelAction) {
            throw new UnsupportedOperationException();
        }

        @Override
        void triggerCancelSignal() {}

        @Override
        boolean isResponsiveToCancelSignal() {
            return false;
        }

        @Override
        boolean isCancelSignalTriggered() {
            return false;
        }
    }

    /**
     * A Reply that is responsive to passive hooks for the Reply cancellation signal (but not active hooks). See
     * {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is appropriate.
     */
    private static class ResponsiveToPassiveCancelReply<T> extends Reply<T> {
        private volatile boolean cancelled;

        private ResponsiveToPassiveCancelReply(CompletableFuture<T> backing, Caller firstCaller, Node<?, T> node) {
            super(backing, firstCaller, node);
            this.cancelled = false;
        }

        @Override
        void setCustomCancelAction(CustomCancelAction cancelAction) {
            throw new UnsupportedOperationException();
        }

        @Override
        void triggerCancelSignal() {
            cancelled = true;
        }

        @Override
        boolean isResponsiveToCancelSignal() {
            return true;
        }

        @Override
        boolean isCancelSignalTriggered() {
            return cancelled;
        }
    }

    /**
     * A Reply that is fully-responsive, both to passive and active hooks, for the Reply cancellation signal. See
     * {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is appropriate.
     */
    private static class ResponsiveToCancelReply<T> extends Reply<T> {
        // VarHandle mechanics
        private static final VarHandle STATE;
        static {
            // Note: used Try paradigm to avoid impossible catch block artificially lowering code coverage. If the try
            // block fails, it will explode, and I don't care how.
            STATE = Try.callCatchException(() -> {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return lookup.findVarHandle(ResponsiveToCancelReply.class, "state", State.class);
            }).getOrThrowUnchecked(ExceptionInInitializerError::new);
        }

        /**
         * The state of this Reply, initially NEW. Represents the relevant combinations of (whether cancel triggered),
         * (cancel action set, started, or finished), and (complete started).
         * 
         * Possible state transitions:<br>
         * * NEW -> CANCEL_ACTION_SET (setCustomCancelAction)<br>
         * * NEW -> CANCEL_REQUESTED_CANCEL_ACTION_UNSET (triggerCancelSignal)<br>
         * * NEW -> COMPLETE_STARTED (startComplete)<br>
         * * CANCEL_ACTION_SET -> CANCEL_ACTION_STARTED (triggerCancelSignal)<br>
         * * CANCEL_ACTION_SET -> COMPLETE_STARTED (startComplete)<br>
         * * CANCEL_REQUESTED_CANCEL_ACTION_UNSET -> CANCEL_ACTION_STARTED (setCustomCancelAction)<br>
         * * CANCEL_REQUESTED_CANCEL_ACTION_UNSET -> COMPLETE_STARTED (startComplete)<br>
         * * CANCEL_ACTION_STARTED -> CANCEL_ACTION_FINISHED (setCustomCancelAction or triggerCancelSignal)<br>
         * * CANCEL_ACTION_STARTED -> CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET (startComplete)<br>
         * * CANCEL_ACTION_FINISHED -> COMPLETE_STARTED (startComplete)<br>
         * * CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET -> COMPLETE_STARTED (setCustomCancelAction or
         * triggerCancelSignal)<br>
         * 
         * This pattern ensures the following properties:<br>
         * * The custom cancel action will run at most once.<br>
         * * The custom cancel action will always finish before the Reply starts completion.<br>
         * 
         * These properties are realized consistently via the following rules:<br>
         * * State transitions only ever happen from lower states to higher states (in terms of ordinals).<br>
         * * Before attempting to transition from state X, we must first prove that the state cannot be in states < X
         */
        private enum State {
            // <cancel not triggered, cancel action not set, incomplete>
            NEW,
            // <cancel not triggered, cancel action set, incomplete>
            CANCEL_ACTION_SET,
            // <cancel triggered, cancel action not set, incomplete>
            CANCEL_TRIGGERED_CANCEL_ACTION_UNSET,
            // <cancel triggered, cancel action started, incomplete>
            CANCEL_ACTION_STARTED,
            // <cancel triggered, cancel action finished, incomplete>
            CANCEL_ACTION_FINISHED,
            // <cancel triggered, cancel action started, incomplete>
            CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET,
            // <cancel not triggered, X, complete>
            COMPLETE_STARTED_CANCEL_NOT_TRIGGERED,
            // <cancel triggered, X, complete>
            COMPLETE_STARTED_CANCEL_TRIGGERED;
        }

        private volatile State state;
        private CustomCancelAction cancelAction; // non-volatile, protected by state reads/writes
        private Runnable startCompleteAction; // non-volatile, protected by state reads/writes

        private ResponsiveToCancelReply(CompletableFuture<T> backing, Caller firstCaller, Node<?, T> node) {
            super(backing, firstCaller, node);
            this.state = State.NEW;
            this.cancelAction = null;
        }

        @Override
        void setCustomCancelAction(CustomCancelAction cancelAction) {
            // Best-effort detection. Not guaranteed due to lack of complex synchronization and possible race condition
            // of multiple callers to setCustomCancelAction the first time. Good enough due to Aggra-internal-only use
            // (which will only ever once try to set the custom action per Reply)
            if (this.cancelAction != null) {
                throw new IllegalStateException("Cancel action can only ever be set once, but detected multiple times");
            }

            // For visibility, assignment must happen before any state is modified
            this.cancelAction = Objects.requireNonNull(cancelAction);

            if (STATE.compareAndSet(this, State.NEW, State.CANCEL_ACTION_SET)) {
                return;
            }

            if (compareAndSet(State.CANCEL_TRIGGERED_CANCEL_ACTION_UNSET, State.CANCEL_ACTION_STARTED)) {
                runCancelAction();
                return;
            }
        }

        private boolean compareAndSet(State initialState, State desiredState) {
            return STATE.compareAndSet(this, initialState, desiredState);
        }

        private void runCancelAction() {
            cancelAction.run(getNode().getCancelMode().supportsCustomActionInterrupt());

            if (compareAndSet(State.CANCEL_ACTION_STARTED, State.CANCEL_ACTION_FINISHED)) {
                return;
            }

            // Must be in CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET because
            // 1. runCancelAction is only ever called from CANCEL_ACTION_STARTED
            // 2. If the current state is not CANCEL_ACTION_STARTED (since the above check failed), startComplete must
            // have transitioned us to CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET (the only other allowable transition)
            // 3. runCancelAction is the only one who can transition out of CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET
            // 4. runCancelAction is only ever called once, which together with #3 means this current call is the one
            // that needs to transition out of CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET
            state = State.COMPLETE_STARTED_CANCEL_TRIGGERED;
            startCompleteAction.run();
            return;
        }

        @Override
        void triggerCancelSignal() {
            if (compareAndSet(State.NEW, State.CANCEL_TRIGGERED_CANCEL_ACTION_UNSET)) {
                return;
            }

            // At this point, we must be later than NOT_CANCELLED_NO_ACTION_SET, to which we will never go back. Either:
            // 1. setCustomCancelAction got us to the current state, in which case we should run the cancel action, or
            // 2. we're already cancelled (doesn't matter how), so do nothing
            if (compareAndSet(State.CANCEL_ACTION_SET, State.CANCEL_ACTION_STARTED)) {
                runCancelAction();
                return;
            }
        }

        @Override
        boolean isResponsiveToCancelSignal() {
            return true;
        }

        @Override
        boolean isCancelSignalTriggered() {
            int localStateOrdinal = state.ordinal(); // local variable to prevent multiple volatile reads
            return localStateOrdinal > State.CANCEL_ACTION_SET.ordinal()
                    && localStateOrdinal != State.COMPLETE_STARTED_CANCEL_NOT_TRIGGERED.ordinal();
        }

        @Override
        void startComplete(T result, Throwable throwable, DependencyCallingDevice<?> device,
                ObserverAfterStop<? super T> firstCallObserverAfterStop) {
            // For visibility, assignment must happen before any state is modified
            startCompleteAction = () -> super.startComplete(result, throwable, device, firstCallObserverAfterStop);

            // Avoid excessive volatile reads by indexing by initial state to decide where to start state comparisons.
            // Relies on fact that state transitions always go from low to high. Each case is designed to fall through
            // to the next unless state matches and transitions. Makes average number of volatile reads 2.
            State initialState = state;
            switch (initialState) {
                case NEW:
                    if (compareAndSet(State.NEW, State.COMPLETE_STARTED_CANCEL_NOT_TRIGGERED)) {
                        startCompleteAction.run();
                        return;
                    }
                case CANCEL_ACTION_SET:
                    if (compareAndSet(State.CANCEL_ACTION_SET, State.COMPLETE_STARTED_CANCEL_NOT_TRIGGERED)) {
                        startCompleteAction.run();
                        return;
                    }
                case CANCEL_TRIGGERED_CANCEL_ACTION_UNSET:
                    if (compareAndSet(State.CANCEL_TRIGGERED_CANCEL_ACTION_UNSET,
                            State.COMPLETE_STARTED_CANCEL_TRIGGERED)) {
                        startCompleteAction.run();
                        return;
                    }
                case CANCEL_ACTION_STARTED:
                    if (compareAndSet(State.CANCEL_ACTION_STARTED, State.CANCEL_ACTION_STARTED_COMPLETE_ACTION_SET)) {
                        // startCompleteAction will be run as a part of the runCancelAction method in this case
                        return;
                    }
                default: // Could only be CANCEL_ACTION_FINISHED
                    state = State.COMPLETE_STARTED_CANCEL_TRIGGERED;
                    startCompleteAction.run();
            }
        }

        @Override
        public String toString() {
            return super.toString() + "[state=" + state + "]";
        }
    }

    ////////////////////////////
    // CompletionStage methods
    ////////////////////////////

    @Override
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn) {
        return backing.thenApply(fn);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return backing.thenApplyAsync(fn);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return backing.thenApplyAsync(fn, executor);
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return backing.thenAccept(action);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return backing.thenAcceptAsync(action);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return backing.thenAcceptAsync(action, executor);
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return backing.thenRun(action);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return backing.thenRunAsync(action);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return backing.thenRunAsync(action, executor);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return backing.thenCombine(other, fn);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return backing.thenCombineAsync(other, fn);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return backing.thenCombineAsync(other, fn, executor);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return backing.thenAcceptBoth(other, action);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return backing.thenAcceptBothAsync(other, action);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor) {
        return backing.thenAcceptBothAsync(other, action, executor);
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return backing.runAfterBoth(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return backing.runAfterBothAsync(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return backing.runAfterBothAsync(other, action, executor);
    }

    @Override
    public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return backing.applyToEither(other, fn);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return backing.applyToEitherAsync(other, fn);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn,
            Executor executor) {
        return backing.applyToEitherAsync(other, fn, executor);
    }

    @Override
    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return backing.acceptEither(other, action);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return backing.acceptEitherAsync(other, action);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action,
            Executor executor) {
        return backing.acceptEitherAsync(other, action, executor);
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return backing.runAfterEither(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return backing.runAfterEitherAsync(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return backing.runAfterEitherAsync(other, action, executor);
    }

    @Override
    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return backing.thenCompose(fn);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return backing.thenComposeAsync(fn);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return backing.thenComposeAsync(fn, executor);
    }

    @Override
    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return backing.handle(fn);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return backing.handleAsync(fn);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return backing.handleAsync(fn, executor);
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return backing.whenComplete(action);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return backing.whenCompleteAsync(action);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return backing.whenCompleteAsync(action, executor);
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return backing.exceptionally(fn);
    }

    /**
     * Return's a copy of this Reply's backing CompletableFuture: the completion of the backing CompletableFuture will
     * complete the returned copy, but modifications to the copy will not affect the backing CompletableFuture.
     */
    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return backing.copy();
    }

    ////////////////////////////
    // Future-like methods
    ////////////////////////////

    /** @see java.util.concurrent.CompletableFuture#isDone() */
    public boolean isDone() {
        return backing.isDone();
    }

    /** @see java.util.concurrent.CompletableFuture#get() */
    public T get() throws InterruptedException, ExecutionException {
        return backing.get();
    }

    /** @see java.util.concurrent.CompletableFuture#get(long, TimeUnit) */
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return backing.get(timeout, unit);
    }

    ////////////////////////////
    // CompletableFuture methods
    ////////////////////////////

    /** Returns the result of {@link CompletableFuture#getNow(Object)} on this NodeReply's backing CompletableFuture. */
    public T getNow(T valueIfAbsent) {
        return backing.getNow(valueIfAbsent);
    }

    /**
     * Returns the result of {@link CompletableFuture#getNumberOfDependents()} on this NodeReply's backing
     * CompletableFuture.
     */
    public int getNumberOfDependents() {
        return backing.getNumberOfDependents();
    }

    /**
     * Returns the result of {@link CompletableFuture#isCompletedExceptionally()} on this NodeReply's backing
     * CompletableFuture.
     */
    public boolean isCompletedExceptionally() {
        return backing.isCompletedExceptionally();
    }

    /** Returns the result of {@link CompletableFuture#join()} on this NodeReply's backing CompletableFuture. */
    public T join() {
        return backing.join();
    }

    @Override
    public String toString() {
        return super.toString() + "[backing=" + backing + resultToString() + "]";
    }

    private String resultToString() {
        if (isDone() && !isCompletedExceptionally()) {
            return ";result=" + Objects.toString(join());
        }
        return "";
    }

    ////////////////////////////
    // CompletableFuture-like methods to avoid toCompletableFuture calls
    ////////////////////////////

    /**
     * A convenience method to call {@link CompletableFuture#allOf(CompletableFuture...)} with the backing
     * CompletableFutures for the given list of CompletionStages. If a given CompletionStage is a Reply, then "backing"
     * means its backing CompletableFuture; otherwise, "backing" means the response from
     * {@link CompletionStage#toCompletableFuture()}. Clients could do something similar themselves by first calling
     * {@link #toCompletableFuture()} on each CompletionStage and then passing that to allOf, but that would wastefully
     * create a copy CompletableFuture for each CompletionStage that happened to be a Reply.
     */
    public static CompletableFuture<Void> allOfBacking(CompletionStage<?>... cs) {
        return CompletableFuture.allOf(arrayOfBacking(Arrays.stream(cs)));
    }

    private static CompletableFuture<?>[] arrayOfBacking(Stream<? extends CompletionStage<?>> cs) {
        return cs.map(Reply::convertToCompletableFuture).toArray(CompletableFuture<?>[]::new);
    }

    private static CompletableFuture<?> convertToCompletableFuture(CompletionStage<?> cs) {
        if (cs instanceof Reply) {
            return ((Reply<?>) cs).backing;
        }
        return cs.toCompletableFuture();
    }

    /**
     * Same thing as {@link #allOfBacking(CompletionStage...)}, except with a Collection instead.
     * 
     * @apiNote Although this method isn't originally present in CompletableFuture, I'm finding that I use it in Aggra
     *          almost all the time: I have a collection rather than an array.
     */
    public static CompletableFuture<Void> allOfBacking(Collection<? extends CompletionStage<?>> cs) {
        return CompletableFuture.allOf(arrayOfBacking(cs.stream()));
    }

    /**
     * Similar to {@link #allOfBacking(Collection)}, except assumes that most completion stages are already complete.
     * So, this method includes extra pre-checking before calling {@link CompletableFuture#allOf(CompletableFuture...)}
     * to minimize the number of arguments. This pre-checking accesses the stages' responses, so there's cost to it, but
     * by assuming that most are already complete, the hope is that the cost is paid for by avoiding the extra process
     * in allOf.
     * 
     * @return a CompletionStage that is complete either onces all completion stages passed in are complete or is
     *         already complete if they already are.
     * 
     * @apiNote package private, because I'm not sure if this is a useful concept, yet.
     */
    static CompletionStage<Void> allOfProbablyCompleteBacking(Collection<Reply<?>> rs) {
        CompletableFuture<?> incomplete[] = rs.stream()
                .map(r -> r.backing)
                .filter(b -> !b.isDone())
                .toArray(CompletableFuture<?>[]::new);
        return incomplete.length == 0 ? COMPLETION_STAGE_EMPTY_VOID : CompletableFuture.allOf(incomplete);
    }

    /**
     * I get the impression from looking at {@link CompletableFuture#completedStage(Object)} that its result can be used
     * as a constant. Looking at the source code gives more confirmation. I would *not* be comfortable doing this with a
     * CompletableFuture directly, since it's mutable. My only fear is a memory leak: that maybe chaining things off of
     * this variable will add compounding memory. In the worst case, I can replace this with a true, non-growing
     * constant CompletionStage (if this one is not).
     */
    private static final CompletionStage<Void> COMPLETION_STAGE_EMPTY_VOID = CompletableFuture.completedStage(null);

    /**
     * A convenience method to call {@link CompletableFuture#anyOf(CompletableFuture...)} with the backing
     * CompletableFutures for the given list of CompletionStages. If a given CompletionStage is a Reply, then "backing"
     * means its backing CompletableFuture; otherwise, "backing" means the response from
     * {@link CompletionStage#toCompletableFuture()}. Clients could do something similar themselves by first calling
     * {@link #toCompletableFuture()} on each CompletionStage and then passing that to anyOf, but that would wastefully
     * create a copy CompletableFuture for each CompletionStage that happened to be a Reply.
     */
    public static CompletableFuture<Object> anyOfBacking(CompletionStage<?>... cs) {
        return CompletableFuture.anyOf(arrayOfBacking(Arrays.stream(cs)));
    }

    ////////////////////////////
    // Unique methods
    ////////////////////////////

    public Node<?, T> getNode() {
        return node;
    }

    /**
     * Answers whether toCheck might have been produced by a Reply, either by calling {@link #join()} or {@link #get()}
     * and getting an exception... or from being passed around to other chained CompletionStages through
     * {@link #exceptionally(Function)}, {@link #handle(BiFunction)}, or {@link #whenComplete(BiConsumer)}. The answer
     * is whether toCheck is either a CompletionException (for calls to join or being passed to chained
     * CompletionStages) or an ExecutionException (for calls to get). For more details on exceptional responses, see the
     * class-level javadoc.
     */
    public static boolean maybeProducedByReply(Throwable toCheck) {
        boolean isCompletionOrExecution = toCheck instanceof CompletionException
                || toCheck instanceof ExecutionException;
        return isCompletionOrExecution && toCheck.getCause() instanceof CallException;
    }

    /**
     * Returns the internal representation exception if this reply {@link #isCompletedExceptionally()} ; otherwise,
     * returns an empty Optional. For more details on exceptional responses, see the class-level javadoc.
     * 
     * @apiNote unlike with {@link #getNow(Object)}, this method returns an Optional rather than accepting a
     *          valueIfAbsent. This is because creating an exception just as a marker object for valueIfAbsent can be
     *          expensive, and passing null as a marker just gets us back to an Optional-like API.
     */
    public Optional<CompletionException> getExceptionNow() {
        // Open question: what's the best way to get an exception from a CompletableFuture/Reply?
        // return Try.callCatchThrowable(reply::join).getNullableFailure();
        // return reply.handle((r, t) -> t).toCompletableFuture().join();
        try {
            getNow(null);
            return Optional.empty();
        } catch (CompletionException e) {
            return Optional.of(e);
        }
    }

    /**
     * Returns the internal representation's CallException if this reply {@link #isCompletedExceptionally()}; otherwise,
     * returns an empty Optional. For more details on exceptional responses, see the class-level javadoc.
     */
    public Optional<CallException> getCallExceptionNow() {
        return getExceptionNow().map(Reply::getCallException);
    }

    /**
     * The static counterpart to {@link #getCallExceptionNow()} with what is assumed to be an exception produced by a
     * Reply. The response is no longer optional, because we don't have to wait for the Reply to finish.
     * 
     * @throws IllegalArgumentException if ! {@link #maybeProducedByReply(Throwable)}.
     */
    public static CallException getCallException(Throwable producedByReply) {
        return (CallException) requireProducedByReply(producedByReply).getCause();
    }

    private static Throwable requireProducedByReply(Throwable toCheck) {
        if (!maybeProducedByReply(toCheck)) {
            throw new IllegalArgumentException(
                    "Expected an exception produced by a reply, but did not have the correct structure: " + toCheck);
        }
        return toCheck;
    }

    /**
     * Returns the internal representation's encountered exception if this reply {@link #isCompletedExceptionally()};
     * otherwise, returns an empty Optional. For more details on exceptional responses, see the class-level javadoc. As
     * a call-out, this method is probably not the one you're looking for, as it may non-predictably contain extraneous
     * wrapping CompletionExceptions. If you're looking for the Behavior-specific exception that was returned, see
     * {@link #getFirstNonContainerExceptionNow()} instead.
     */
    public Optional<Throwable> getEncounteredExceptionNow() {
        return getExceptionNow().map(Reply::getEncounteredException);
    }

    /**
     * The static counterpart to {@link #getEncounteredExceptionNow()} with what is assumed to be an exception produced
     * by a Reply. The response is no longer optional, because we don't have to wait for the Reply to finish.
     * 
     * @throws IllegalArgumentException if ! {@link #maybeProducedByReply(Throwable)}.
     */
    public static Throwable getEncounteredException(Throwable producedByReply) {
        return getCallException(producedByReply).getCause();
    }


    /**
     * Returns the internal representation's first non-container exception if this reply
     * {@link #isCompletedExceptionally()} and the exception has something other than container exceptions; otherwise,
     * returns an empty Optional. For more details on exceptional responses, see the class-level javadoc.
     * 
     * In an ideal world, this method wouldn't be necessary, but it's just too easy to introduce container exceptions in
     * a NodeReply's root exception. It would be brittle to assume a certain causal structure, which may be true at one
     * point in time only to change when the node is slightly altered (e.g. see
     * {@link io.github.graydavid.aggra.nodes.FunctionNodes} for how simply changing from a synchronous to an
     * asynchronous node can change the causal structure.
     */
    public Optional<Throwable> getFirstNonContainerExceptionNow() {
        return getExceptionNow().flatMap(Reply::getFirstNonContainerException);
    }

    /**
     * The static counterpart to {@link #getEncounteredExceptionNow()} with what is assumed to be an exception produced
     * by a Reply. The response is still optional, because although we don't have to wait for the Reply to finish, it's
     * possible that the internal representation contains nothing but container exceptions.
     * 
     * @throws IllegalArgumentException if ! {@link #maybeProducedByReply(Throwable)}.
     */
    public static Optional<Throwable> getFirstNonContainerException(Throwable producedByReply) {
        requireProducedByReply(producedByReply);
        return CausalChains.findFirst(producedByReply, IS_NOT_CONTAINER);
    }

    private static final Predicate<Throwable> IS_CONTAINER = throwable -> (throwable instanceof CompletionException)
            || (throwable instanceof ExecutionException) || (throwable instanceof CallException);
    private static final Predicate<Throwable> IS_NOT_CONTAINER = IS_CONTAINER.negate();

    /**
     * Similar to {@link #allOfProbablyCompleteBacking(Collection)}, except involves this node's nodeForAllSignal rather
     * than its backing future. See the javadoc for nodeForAllSignal to understand what its purpose is.
     * 
     * @apiNote package private, because I'm not sure if this is a useful concept, yet.
     */
    static CompletionStage<Void> allOfProbablyCompleteNodeForAllSignal(Collection<Reply<?>> replies) {
        CompletableFuture<?> nodeForAllSignals[] = replies.stream()
                .filter(r -> !r.canExcludeInConsumerNodeForAllSignal())
                .map(r -> r.nodeForAllSignal)
                .toArray(CompletableFuture[]::new);
        return nodeForAllSignals.length == 0 ? COMPLETION_STAGE_EMPTY_VOID : CompletableFuture.allOf(nodeForAllSignals);
    }

    private boolean canExcludeInConsumerNodeForAllSignal() {
        // If this node waits for all transitive dependencies and is already done, then we know all transitive
        // dependencies are done as well. That means consumers can exclude this node in their nodeForAllSignal
        return node.getMinimumDependencyLifetime().waitsForAllDependencies() && isDone();
    }

    /**
     * Starts the completion process for a Reply. This method makes the following decisions based on the
     * DependencyLifetime value: which dependency replies to consider incomplete (and so implicitly ignore them), which
     * dependency replies are complete (and so eligible for ExceptionStrategy), when to complete the Reply's backing
     * future, and when/if to complete the Reply's nodeForAllSignal.
     * 
     * @param result the (possibly null) value that the Reply should have on success. Ignored if throwable is non-null.
     * @param throwable the value the Reply should have on failure (after transformations). A non-null value for this
     *        parameter indicates that the node failed, in which case, result should be null and will be ignored.
     * @param device the DependencyCallingDevice that has been calling all of the Reply's Node's dependencies. This
     *        method will {@link DependencyCallingDevice#weaklyClose()} the device.
     * @param firstCallObserverAfterStop the ObserverAfterStop created by
     *        {@link CallObservers.Observer#observeBeforeFirstCall(Caller, Node, Memory)} upon this Reply's creation.
     *        This method will call {@link ObserverAfterStop#observe(Object, Throwable)} just before it completes the
     *        backing future. This observer is expected to be fault tolerant/not to throw exceptions.
     * 
     * @apiNote package private because only the Aggra framework (and specifically only Node#FirstCall) should complete
     *          this reply, not any clients (or any other classes in the Aggra framework).
     */
    void startComplete(T result, Throwable throwable, DependencyCallingDevice<?> device,
            ObserverAfterStop<? super T> firstCallObserverAfterStop) {
        DependencyCallingDevices.FinalState deviceFinalState = device.weaklyClose();
        node.getMinimumDependencyLifetime()
                .enforceLifetimeForReply(deviceFinalState,
                        completeReplies -> complete(result, throwable, completeReplies, firstCallObserverAfterStop),
                        () -> nodeForAllSignal.complete(null));
    }

    private void complete(T result, Throwable throwable, Set<Reply<?>> completeReplies,
            ObserverAfterStop<? super T> firstCallObserverAfterStop) {
        firstCallObserverAfterStop.observe(result, throwable);
        if (throwable == null) {
            backing.complete(result);
        } else {
            Supplier<Collection<CompletionException>> supplyDependencyExceptions = () -> completeReplies.stream()
                    .map(Reply::getExceptionNow)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toUnmodifiableList());
            CompletionException containerException = node.getExceptionStrategy()
                    .constructContainerException(firstCaller, node, throwable, supplyDependencyExceptions);
            backing.completeExceptionally(containerException);
        }
    }

    /**
     * Answers whether this node supports a custom cancel action. Currently, this just reflects the associated Node's
     * CancelMode, but theoretically, it could be different.
     */
    boolean supportsCustomCancelAction() {
        return node.getCancelMode().supportsCustomAction();
    }

    /**
     * Sets the custom cancel action that should be executed when the Reply is cancelled. If the Reply is already
     * cancelled, then calling this method will cause the action to run immediately. This method should only ever be
     * called once on any Reply instance. It's necessary that this state be set separately from Reply construction,
     * since the Reply is created before its Node's Behavior is run, which is what provides the cancel action.
     * 
     * The provided action is expected not to throw any exceptions; instead, it should be set up to suppress those
     * exceptions and make them available through {@link GraphCall.FinalState#getUnhandledExceptions()}. In this way,
     * neither setting the cancel action nor triggering the cancel signal (which may run the action) should throw
     * exceptions, as mentioned in {@link Node#call(Caller, Memory, GraphCall, Observer)}
     * 
     * @throws * any exception that the CustomCancelAction throws... except that, as noted in
     *         {@link Node#call(Caller, Memory, GraphCall, io.github.graydavid.aggra.core.CallObservers.Observer)},
     *         which is the only caller of this method, the action used will never throw an exception. In other words,
     *         effectively, this method will not encounter and so not propagate any exception from the cancel action.
     * @throws UnsupportedOperationException if the Reply does not support custom cancel action, as determined
     *         {@link #supportsCustomCancelAction()}
     * @throws IllegalStateException if this Reply detects that the custom action has already been set on it before.
     *         This detection is best-effort and not guaranteed.
     */
    abstract void setCustomCancelAction(CustomCancelAction cancelAction);

    /**
     * Triggers the Reply cancellation signal: signals that this Reply should cancel itself. This process may be passive
     * or active, depending on the type of cancellation hooks that this Reply's Node supports. If this Reply's Nodes
     * only support passive hooks, then this Reply will mark itself as cancelled and allow the hooks to poll that status
     * and take the appropriate action. If this Reply's Node supports active hooks, then this method will cause those
     * actions to be executed (in addition to the status being set) either during this method call or perhaps further in
     * the future, depending on the Reply's current state and properties... unless the Reply completes first before
     * either of those happen, in which case the cancel action may never be run (but still can, since there are no
     * protections in place to ensure that only either one or the other will happen).
     * 
     * This method may only be called when a consuming Node calls {@link DependencyCallingDevice#ignore(Reply)} with
     * this Reply *and* Aggra can prove that no other Node is interested in the response from the Reply.
     * 
     * For efficiency, this method may do nothing in response to the triggering of the Reply cancellation signal. First,
     * Reply is allowed to do this if both A. noone will read the signal status and B. there is no custom cancel action
     * for this Reply. In that case, then we know that triggering the signal would be a no-op. As such, this method can
     * simply do nothing. This can yield extra efficiency, as the MemoryScope doesn't have to track and update the
     * cancellation status (i.e. no need for a volatile boolean). Second, Reply is also allowed to do nothing in
     * response to the triggering if the Reply is already complete. In that case, we also know that triggering the
     * signal would be a no-op, since you can't cancel something that's already complete. This can yield extra
     * efficiency by avoiding execution of any custom cancel action, while still allowing consumers to call this method
     * (which MemoryScope will always do on cleanup for every tracked Reply). In these two ways, users only pay for the
     * features they use (cancellability being one of those features).
     * 
     * @throws * any exception that the Reply's CustomCancelAction throws... except that, as noted in
     *         {@link Node#call(Caller, Memory, GraphCall, io.github.graydavid.aggra.core.CallObservers.Observer)},
     *         which is the only caller of {@link #setCustomCancelAction(CustomCancelAction)}, the action used will
     *         never throw an exception. In other words, effectively, this method will not encounter and so not
     *         propagate any exception from the cancel action.
     * 
     * @apiNote although named similarly, and although Reply is like a CompletableFuture, this method is completely
     *          separate from {@link Future#cancel(boolean)}.
     */
    abstract void triggerCancelSignal();

    /**
     * Answers whether this instance is responsive to {@link #triggerCancelSignal()}: i.e. does calling
     * "triggerCancelSignal" do anything, even just updating the status returned by {@link #isCancelSignalTriggered()}.
     * Replys are allowed to be non-responsive if they can prove that any execution of {@link #triggerCancelSignal()}
     * would be a no-op (which is dependent upon properties of the Reply's Node).
     */
    abstract boolean isResponsiveToCancelSignal();

    /**
     * Answers whether the Reply cancellation signal has been triggered. See {@link #triggerCancelSignal()} for a full
     * discussion of when this could happen (and when that trigger may cause nothing to happen at all).
     * 
     * @apiNote although named similarly, and although Reply is like a CompletableFuture, this method is completely
     *          separate from {@link Future#isCancelled()}.
     */
    abstract boolean isCancelSignalTriggered();
}
