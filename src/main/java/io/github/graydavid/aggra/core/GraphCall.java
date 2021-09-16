/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.CallObservers.ObservationException;
import io.github.graydavid.aggra.core.CallObservers.ObservationFailureObserver;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceInputFactory;

/**
 * Represents an ongoing call to a Graph. During this graph call, users can call root nodes within the graph. The same
 * memory will be used for all such calls. After making all desired node calls, users should then {@link #weaklyClose()}
 * this graph call.
 */
public class GraphCall<M extends Memory<?>> {
    private final Graph<M> graph;
    private final M memory;
    private final Queue<Throwable> nonIgnoredReplyUnhandledExceptions;
    private final Observer observer;
    private final Queue<Reply<?>> rootReplies;
    private final Queue<Reply<?>> ignoredReplies;
    // Only provides weak closure: not declared volatile to avoid costs of synchronization
    private boolean closed;

    private GraphCall(Graph<M> graph, M memory, Observer observer) {
        this.graph = graph;
        this.memory = Objects.requireNonNull(memory);
        this.nonIgnoredReplyUnhandledExceptions = new ConcurrentLinkedQueue<>();
        this.observer = makeFaultTolerantByRecording(observer, this.nonIgnoredReplyUnhandledExceptions);
        this.rootReplies = new ConcurrentLinkedQueue<>();
        this.ignoredReplies = new ConcurrentLinkedQueue<>();
    }

    private static Observer makeFaultTolerantByRecording(Observer observer, Collection<Throwable> recorder) {
        Observer faultTolerant = Observer.faultTolerant(observer, new ObservationFailureObserver() {
            @Override
            public void defaultObserve(ObservationException exception) {
                recorder.add(exception);
            }
        });
        return Objects.requireNonNull(faultTolerant);
    }

    /**
     * See {@link Graph#openCancellableCall(MemoryNoSourceInputFactory, Observer)}.
     * 
     * @throws MisbehaviorException if factory creates a Memory with a different scope than what's provided to it as an
     *         argument.
     */
    static <M extends Memory<?>> GraphCall<M> createCancellableCall(Graph<M> graph,
            MemoryNoSourceInputFactory<M> factory, Observer observer) {
        MemoryScope memoryScope = MemoryScope.createForGraph(graph);
        M newMemory = factory.createNew(memoryScope);
        MemoryBridges.requireMemoryHasScope(newMemory, memoryScope);
        return new GraphCall<>(graph, newMemory, observer);
    }

    /**
     * Calls the given root node. See {@link Node#call(Caller, Memory, Observer)} for a full description of behavior.
     * The only thing this method adds on top is checking that the provided node is actually a declared root node before
     * allowing the call to proceed. Users should only call nodes before this graph has been closed.
     * 
     * NOTE: remember to {@link #weaklyClose()} this GraphCall once you're done calling all of the nodes you care about.
     * See {@link #finalCallAndWeaklyClose(Node, FinalStateAndReplyHandler)} for a convenient way to unify this
     * weakly-closing step with the final call to a root node.
     * 
     * @throws MisbehaviorException if root is not actually a root of the Graph.
     * @throws MisbehaviorException if it's been detected (which is not a guarantee) that this graph call has been
     *         closed (through {@link #weaklyClose()}).
     */
    public <T> Reply<T> call(Node<M, T> root) {
        requireNotClosedCalling(root);
        requireIsRootNode(root);

        Reply<T> reply = root.call(graph, memory, this, observer);
        rootReplies.add(reply);
        return reply;
    }

    private void requireNotClosedCalling(Node<M, ?> root) {
        if (closed) {
            throw new MisbehaviorException(
                    "Cannot call a root node once a graph call is closed but found call to: " + root.getSynopsis());
        }
    }

    private void requireIsRootNode(Node<M, ?> root) {
        if (!graph.getRootNodes().contains(root)) {
            String message = String.format("'%s' tried to call '%s' without modeling it as a root node", graph, root);
            throw new MisbehaviorException(message);
        }
    }

    /**
     * Adds an unhandled exception to this GraphCall, which will eventually be exposed in
     * {@link GraphCall.FinalState#unhandledExceptions}.
     * 
     * This method can still be called after {@link #weaklyClose()} has been called. However, any exception added after
     * all of GraphCall's Nodes complete will *not* be made available in the eventually returned FinalState from that
     * method. The easiest way to ensure all exceptions are available is to make sure there's a happens-before
     * relationship between the calling of this method and the completion of any Node's Reply.
     * 
     * @apiNote Calls to this method appear in critical code, which assumes that this method doesn't throw an exception.
     *          The implementation is so simple that they should not throw any exceptions except in very rare,
     *          the-program-is-doomed-anyway scenarios like OutOfMemoryError.
     */
    void addUnhandledException(Throwable throwable) {
        Objects.requireNonNull(throwable);
        nonIgnoredReplyUnhandledExceptions.add(throwable);
    }

    /**
     * Tells this GraphCall that it should "ignore" a Reply that it previously yielded through {@link #call(Node)}. This
     * call is a similar idea to {@link DependencyCallingDevice#ignore(Reply)}. This includes how this might escalate to
     * cancellation, the reason for a lack of direct cancellation of a Reply, conditions under which the escalation
     * might happen, and how exceptions through by any custom cancel actions are suppressed and made available through
     * {@link GraphCall.FinalState#getUnhandledExceptions()}. Differences are that GraphCalls have only one concept of
     * ignoring, which is most similar to DependencyCallingDevice's concept of explicit ignoring: there is no implicit
     * ignoring when it comes to GraphCalls, because there's no concept of a GraphCall's implicit, centralized behavior.
     * In addition, this method throws similar exceptions but for slightly different reasons.
     * 
     * This method can be run after {@link #weaklyClose()} is called... although, obviously, it will have no effect if
     * all of this GraphCall's Nodes are already complete.
     * 
     * @throws MisbehaviorException if this GraphCall can prove that it didn't call a root Node to yield this Reply. For
     *         efficiency's sake, this device simply checks that the Reply's Node is a root Node, which is 90% of the
     *         way there. However, in the future, DependencyCallingDevice is free to change the implementation to
     *         compare the provided reply with all of those root Replys that this device is tracking.
     */
    public void ignore(Reply<?> rootReply) {
        requireReplyToIgnoreYieldedByThisGraphCall(rootReply);
        explicitlyIgnore(rootReply);
    }

    /**
     * Does its best to verify that the replyToIgnore was yielded by {@link #call(Node)} . In practice, all this method
     * does is verify that the Reply's node is root Node. Ideally, this method would check rootReplies; however, that
     * would be inefficient: O(n) lookup or changing rootReplies to a Set (which would make the addition of new Replys
     * more inefficient), plus rootReplies (in cases where ignores would matter) is a concurrent collection (for which
     * there is an extra cost to accessing).
     * 
     * This is acceptable, since even if replyToIgnore was not yielded by a call from this GraphCall, there will not be
     * any heavy consequences. First, replyToIgnore would be added to GraphCall as a tracked ignored Reply, which is low
     * cost. Second, the only way this could yield an unintended cancellation is if another GraphCall instance for the
     * same Graph yielded the replyToIgnore. I find that highly unlikely, and even if true, somewhat acceptable, since
     * GraphCalls are tightly bound to their callingNodes: two GraphCalls for the same root Node conspiring to cancel a
     * Reply from one GraphCall in another GraphCall... is acceptable given the costs of protecting against it.
     */
    private void requireReplyToIgnoreYieldedByThisGraphCall(Reply<?> replyToIgnore) {
        if (!graph.getRootNodes().contains(replyToIgnore.getNode())) {
            String message = String.format("Tried to ignore '%s' without first calling it as a root node",
                    replyToIgnore);
            throw new MisbehaviorException(message);
        }
    }

    /**
     * Indicates the user has explicitly ignored the given dependencyReply. This happens when the user calls
     * {@link DependencyCallingDevice#ignore(Reply)}
     * 
     * This graph call will now track these replies. The graph call will not complete until the replies have completed.
     * The Reply will be will be made available in the response from {@link #weaklyClose()}. The same Reply can be added
     * in multiple calls (since different consumers may ignore the same Reply independently and users may both
     * explicitly and implicitly ignore the same Reply), but any exceptions from multiple of such calls will be
     * deduplicated in the FinalState.
     * 
     * In addition, ignoring the Reply may lead to its cancellation. For when and the meaning of cancellation, see
     * {@link DependencyCallingDevice#ignore(Reply)}. Any exception thrown by any run custom cancel action will be
     * suppressed and made available through {@link GraphCall.FinalState#getUnhandledExceptions()}.
     */
    void explicitlyIgnore(Reply<?> reply) {
        ignoredReplies.add(reply);
        cancelIgnoredReplyIfPossible(reply);
    }

    /**
     * As stated in {@link DependencyCallingDevice#ignore(Reply)}, ignoring can escalate to cancellation, but only if we
     * can prove that all potential consumers either won't yield the same Reply from that dependency or have already
     * ignored it themselves. The only proof we currently support is if there is only one consumer of the dependency and
     * that consumer relationship has a {@link Dependency#getConsumerCallToDependencyReplyCardinality()} of either
     * ONE_TO_ONE and ONE_TO_MANY (which is the same as saying that it's either a SameMemoryDependency or a
     * NewMemoryDependency). (Note: root nodes of the Graph have an implicit consumer: the top-level caller of the Graph
     * itself. As far as properties of that relationship go, you can consider it to be equivalent to a
     * SameMemoryDependency relationship.) Under these conditions, Aggra can easily prove that the single consumer call
     * has ignored and no longer needs the Dependency Reply, and so it can be cancelled.
     */
    void cancelIgnoredReplyIfPossible(Reply<?> reply) {
        // Optimization: if the reply won't do anything with the cancel signal, no need to compute whether to send it
        if (!reply.isResponsiveToCancelSignal()) {
            return;
        }

        if (graph.ignoringWillTriggerReplyCancelSignal(reply.getNode())) {
            reply.triggerCancelSignal();
        }
    }

    /**
     * Indicates the user has implicitly ignored the given collection of replies. This happens when a consuming Node's
     * behavior completes and the given dependencyReplies are still incomplete. This is determined before the Node's
     * DependencyLifetime is enforced (i.e. Replys can be implicitly ignored by DependencyLifetimes other than GRAPH).
     * 
     * This graph call will now track these replies. The graph call will not complete until the replies have completed.
     * The Reply will be will be made available in the response from {@link #weaklyClose()}. The same Reply can be added
     * in multiple calls (since different consumers may ignore the same Reply independently and users may both
     * explicitly and implicitly ignore the same Reply), but any exceptions from multiple of such calls will be
     * deduplicated in the FinalState.
     * 
     * In addition, ignoring the Reply may lead to its cancellation. For when and the meaning of cancellation, see
     * {@link DependencyCallingDevice#ignore(Reply)}. Any exception thrown by any run custom cancel action will be
     * suppressed and made available through {@link GraphCall.FinalState#getUnhandledExceptions()}.
     * 
     * @apiNote It's expected that this method can be called even after {@link #weaklyClose()} is called, since Nodes in
     *          the graph may be finishing. However, once the response from weaklyClose is complete, no more calls to
     *          this method should be made. This should automatically be true if Nodes add ignored dependency Replys
     *          before their own Reply is complete.
     * 
     * @apiNote this method is package private, because only the Aggra framework should be responsible for calling it.
     */
    void implicitlyIgnore(Collection<Reply<?>> dependencyReplies) {
        ignoredReplies.addAll(dependencyReplies);
        dependencyReplies.forEach(this::cancelIgnoredReplyIfPossible);
    }

    /**
     * (Weakly) closes this graph call so that no further node calls should be made through it. Users should call this
     * method after they've made all intended node calls. The response from this method will complete once all of this
     * Graph's Node calls have completed and will contain the {@link FinalState} at that time.
     * 
     * The "weak" part of the method name comes from the fact that this method puts the onus on the user of managing a
     * Graph Call's closed state. Specifically, this weaklyClose method must be called only once and all root Node calls
     * through the GraphCall interface must cease after that. While this method and other externally-facing GraphCall
     * methods *try* their best to verify that these conditions are met, neither method *guarantees* detection, and
     * behavior is undefined if either condition is not met. (Note: this is kind of like how the collections API tries
     * to detect concurrent modification and will throw {@link ConcurrentModificationException} but doesn't guarantee
     * that.)
     * 
     * @throws MisbehaviorException if it's been detected (which is not a guarantee) that this graph call has already
     *         been closed through this method before.
     * 
     * @apiNote Although it would be possible to detect perfectly/"strongly" the conditions for closing a Graph, it
     *          would require thread synchronization. Given the simplicity of the API and the (admittedly probably
     *          minor, although non-trivial) cost of thread synchronization, I've chosen to user a "best effort"
     *          approach instead. The guarantees in the documentation are lenient enough that I can switch approaches,
     *          if desired.
     * @implNote there are a couple of places in the Aggra framework that do kick off actions that could still be
     *           running after the response from this method completes. Search for the marker string
     *           "AFTER_WEAKLY_CLOSE". Each place justifies why this is acceptable, but the common answer is that the
     *           action is usually a simple, no-op.
     */
    public CompletableFuture<FinalState> weaklyClose() {
        requireNotReclosure();
        closed = true;

        Set<Reply<?>> finalRootReplies = rootReplies.stream().collect(Collectors.toSet());
        return Reply.allOfBacking(finalRootReplies)
                .exceptionally(t -> null) // Users have other means for accessing exceptional replies
                .thenRun(this::triggerCancelSignal)
                .thenCompose(ignore -> Reply.allOfProbablyCompleteNodeForAllSignal(finalRootReplies)
                        .handle((ignore1, ignore2) -> State.from(Set.copyOf(ignoredReplies),
                                nonIgnoredReplyUnhandledExceptions, FinalState::new))
                        .toCompletableFuture());
    }

    private void requireNotReclosure() {
        if (closed) {
            throw new MisbehaviorException("A graph call can only be closed once.");
        }
    }

    /**
     * The state of a Graph Call at a particular moment in time. The major intent here is to allow access to ignored
     * Replys and all exceptions the user had no opportunity to handle. See
     * {@link DependencyCallingDevice#ignore(Reply)} for more details about ignoring Replys.
     * 
     * There are two subclasses: FinalState and AbandonedState. FinalState is the state of the Graph Call once all of
     * its nodes have completed; in this case, the FinalState contains all of the ignored replies (which are guaranteed
     * to be complete) and unhandled exceptions over the course of the Graph Call. AbandonedState is the state of the
     * Graph Call immediately after it's been abandoned; in this case, the state either may or may not contain all of
     * the ignored replies (which may or may not be complete) and unhandled exceptions over the course of the Graph
     * Call.
     * 
     * Note: The user may have had a chance to handle Replys in one consuming Node that were ignored in another. It's
     * impractical for the Aggra framework to know this. So, *any* ignored Replys will be exposed here, even if they
     * *happen* to be handled somewhere else.
     * 
     * @apiNote goals: allow access to ignored Replys that the user might now care about; no hidden exceptions: the user
     *          must be able to see all exceptions encountered during a graph call (at least in FinalState, which is
     *          recommended).
     */
    public abstract static class State {
        private final Set<Reply<?>> ignoredReplies;
        private final Set<Throwable> unhandledExceptions;

        private State(Set<Reply<?>> ignoredReplies, Set<Throwable> unhandledExceptions) {
            this.ignoredReplies = ignoredReplies;
            this.unhandledExceptions = unhandledExceptions;
        }

        private static <T extends State> T from(Set<Reply<?>> ignoredReplies,
                Queue<Throwable> nonIgnoredReplyUnhandledExceptions,
                BiFunction<Set<Reply<?>>, Set<Throwable>, T> stateConstructor) {
            Stream<Throwable> ignoredReplyExceptions = ignoredReplies.stream()
                    .distinct()
                    .map(Reply::getExceptionNow)
                    .flatMap(Optional::stream);
            Set<Throwable> allUnhandledExceptions = Stream
                    .concat(ignoredReplyExceptions, nonIgnoredReplyUnhandledExceptions.stream())
                    .collect(Collectors.toUnmodifiableSet());
            return stateConstructor.apply(ignoredReplies, allUnhandledExceptions);
        }

        /** Is this a FinalState? */
        public abstract boolean isFinal();

        /** Is this an AbandonedState? */
        public boolean isAbandoned() {
            return !isFinal();
        }

        /**
         * Returns the Replys that have so far been ignored (either implicitly or explicitly) during the course of the
         * Graph Call. If {@link #isFinal()}, then these will be *all* of the ignored replies for the Graph Call, and
         * they're all guaranteed to be complete already; otherwise, the list may be partial and the state of the
         * Replies is not guaranteed.
         */
        public Set<Reply<?>> getIgnoredReplies() {
            return ignoredReplies;
        }

        /**
         * Returns the exceptions that the user (probably) didn't get a chance to handle during the Graph Call. If
         * {@link #isFinal()}, then these will be *all* of the unhandled exceptions for the Graph Call; otherwise, the
         * list may be partial.
         * 
         * Unhandled exceptions include:<br>
         * * Any exceptional results from ignored Replys (which themselves can also be accessed via
         * {@link #getIgnoredReplies()} as well).<br>
         * * Any exceptions thrown by the {@link Observer} associated with this GraphCall. These exceptions were
         * suppressed during the call as explained in
         * {@link Graph#openCancellableCall(MemoryNoSourceInputFactory, Observer)} and its variants<br>
         * * Any exceptions thrown while executing any {@link CustomCancelAction#run(boolean)} for any of the Graph's
         * Nodes.<br>
         * * Any exceptions throws by {@link BehaviorWithCustomCancelAction#clearCurrentThreadInterrupt()} at the end of
         * completing a Node's Reply (which is only called if a Node {@link CancelMode#supportsCustomActionInterrupt()})
         */
        public Set<Throwable> getUnhandledExceptions() {
            return unhandledExceptions;
        }
    }

    /**
     * The final state of a Graph Call once all of its Nodes have completed. This is guaranteed to return all of the
     * ignored replies (which are guaranteed to be completed) and unhandled exceptions for the entirety of the Graph
     * Call.
     */
    public static class FinalState extends State {
        private FinalState(Set<Reply<?>> ignoredReplies, Set<Throwable> unhandledExceptions) {
            super(ignoredReplies, unhandledExceptions);
        }

        @Override
        public boolean isFinal() {
            return true;
        }
    }


    /**
     * The abandoned state of a Graph Call. This is only guaranteed to return all of the ignored replies (which may or
     * may not be completed, yet) and unhandled exceptions for the Graph Call so far.
     */
    public static class AbandonedState extends State {
        private AbandonedState(Set<Reply<?>> ignoredReplies, Set<Throwable> unhandledExceptions) {
            super(ignoredReplies, unhandledExceptions);
        }

        @Override
        public boolean isFinal() {
            return false;
        }
    }

    /**
     * Utility method to combine the final {@link #call(Node)} to a root node followed by a {@link #weaklyClose()}. This
     * is convenient, common combination when you know when the final call will be (e.g. when there's only one call,
     * period) and you only want to consume the FinalState (e.g. to log unhandled exceptions). If you need more
     * flexibility than this method provides, you should call the methods separately.
     * 
     * @param stateHandler the handler to execute against this GraphCall's FinalState after being weakly closed.
     * 
     * @return a CompletableFuture that results in the Reply for the provided root Node's call. This Future completes
     *         after all of this Graph's Node calls complete (including, obviously, the provided root node for this
     *         call) and stateHandler has been run. The Future will be exceptional if stateHandler throws an exception.
     * 
     * @throws MisbehaviorException if root is not actually a root of the Graph.
     * @throws MisbehaviorException if it's been detected (which is not a guarantee) that this graph call has been
     *         closed (through {@link #weaklyClose()}).
     */
    public <T> CompletableFuture<Reply<T>> finalCallAndWeaklyClose(Node<M, T> root,
            StateAndReplyHandler<? super FinalState, T> stateHandler) {
        Objects.requireNonNull(stateHandler);

        Reply<T> finalReply = call(root);
        return weaklyClose().whenComplete((r, t) -> stateHandler.handle(r, t, finalReply))
                .thenApply(finalState -> finalReply);
    }

    /** A handler class used to process a GraphCall's state after either being closed or abandoned. */
    @FunctionalInterface
    public interface StateAndReplyHandler<S extends State, T> {
        /**
         * @param state the GraphCall's State after having been successfully {@link GraphCall#weaklyClose()}-ed or
         *        {@link GraphCall#abandon()}-ed. Could be null if the closure or abandonment returned an exceptional
         *        response (unlikely to happen, but users should account for it, just in case).
         * @param throwable the underlying throwable if the weak closure or abandonment returned an exceptional
         *        response.
         * @param finalReply the Reply for the final Node call before the GraphCall was weakly closed.
         */
        void handle(S state, Throwable throwable, Reply<T> finalReply);
    }

    /**
     * Abandons this graph call. That means immediately triggering the GraphCall cancellation signal and gathering up
     * any ignored dependencies and unhandled exceptions encountered so far.
     * 
     * Warning: this is an emergency lever that should only be pulled in the worst case. Instead, you should call
     * {@link #weaklyClose()} (or some similar variant) and wait for the Graph Call (and all nodes in it) to finish
     * naturally. All nodes should be architected such that they will complete within your expected timeframe. This
     * process will give you a complete FinalState view for the Graph Call.
     * 
     * Abandoning a Graph Call, on the other hand, is a protection against rogue nodes that don't behave properly and
     * may complete too late or maybe never at all. It doesn't wait for any nodes to finish and returns a capture of the
     * Graph Call's current state (in an AbandonedState object). The reason this can be dangerous is that some
     * background logic may still be running, taking up resources that you won't account for. Instead, you'll move onto
     * the next request to process, which will take up more resources still. At the very least, if you do abandon a
     * Graph Call, then you should record some metric and alarm on it to detect the problem.
     * 
     * Unlike with {@link #weaklyClose()}, this method can be called as many times as desired, even after the Graph Call
     * has been weakly closed (meaning you don't have to worry about race conditions).
     */
    public AbandonedState abandon() {
        triggerCancelSignal();
        return State.from(Set.copyOf(ignoredReplies), nonIgnoredReplyUnhandledExceptions, AbandonedState::new);
    }

    /**
     * A reasonable combination of {@link #weaklyClose()} and {@link #abandon()}. This method calls weaklyClose and then
     * waits for the timeout for the response to complete. If it does, then the response from weaklyClose is returned;
     * otherwise, calls abandon() and returns the response for it. Either way, the response from this method will be
     * complete at the earliest of weaklyClose completing or the timeout expiring.
     */
    public CompletableFuture<State> weaklyCloseOrAbandonOnTimeout(long timeout, TimeUnit unit) {
        // Assumes all exceptions from weaklyClose().orTimeout(...) are TimeoutExceptions from timing out.
        return weaklyClose().orTimeout(timeout, unit).<State>thenApply(x -> x).exceptionally(throwable -> abandon());
    }

    /**
     * Utility method to combine the final {@link #call(Node)} to a root node followed by a
     * {@link #weaklyCloseOrAbandonOnTimeout(long, TimeUnit)}. This is convenient, common combination when you know when
     * the final call will be (e.g. when there's only one call, period) and you only want to consume the (Final or
     * Abandoned) State (e.g. to log unhandled exceptions). If you need more flexibility than this method provides, you
     * should call the methods separately.
     * 
     * @param stateHandler the handler to execute against this GraphCall's (Final or Abandoned) State after being weakly
     *        closed or abandoned.
     * 
     * @return A CompletableFuture that completes after weaklyCloseOrAbandonOnTimeout completes and stateHandler has
     *         been run. The Future will be exceptional if stateHandler throws an exception. Otherwise, the Future will
     *         contain the Reply for the provided root Node's call, provided that the Reply is complete by the time the
     *         stateHandler has run. This condition will automatically be true if weak-closure finished; otherwise, if
     *         there was a timeout, it's not guaranteed whether the Reply will be complete. If the Reply is not
     *         complete, then the returned Future will represent an exception response of
     *         {@link AbandonedAfterTimeoutReplyException} holding the reply (wrapped in a CompletionException, which
     *         CompletableFuture's get methods may peel away). The overall intent here is that the response should
     *         represent a single opportunity to retrieve the Reply. As mentioned above, if this is insufficient for
     *         your usecase, you should call the individual methods that this method combines.
     * 
     * @throws MisbehaviorException if root is not actually a root of the Graph.
     * @throws MisbehaviorException if it's been detected (which is not a guarantee) that this graph call has been
     *         closed (through {@link #weaklyClose()}).
     */
    public <T> CompletableFuture<Reply<T>> finalCallAndWeaklyCloseOrAbandonOnTimeout(Node<M, T> root, long timeout,
            TimeUnit unit, StateAndReplyHandler<State, T> stateHandler) {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(stateHandler);

        Reply<T> finalReply = call(root);
        return weaklyCloseOrAbandonOnTimeout(timeout, unit)
                .whenComplete((r, t) -> stateHandler.handle(r, t, finalReply))
                .thenApply(finalState -> {
                    if (finalReply.isDone()) {
                        return finalReply;
                    }
                    throw AbandonedAfterTimeoutReplyException.from(finalReply, timeout, unit);
                });
    }

    /** Indicates that a given reply was abandoned after a timeout. */
    public static class AbandonedAfterTimeoutReplyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reply<?> reply;

        private AbandonedAfterTimeoutReplyException(Reply<?> reply, String message) {
            super(message);
            this.reply = reply;
        }

        public static AbandonedAfterTimeoutReplyException from(Reply<?> reply, long timeout, TimeUnit unit) {
            String message = String.format("Reply '%s' was abandonded after '%s' '%s'", reply, timeout, unit);
            return new AbandonedAfterTimeoutReplyException(reply, message);
        }

        public Reply<?> getReply() {
            return reply;
        }
    }

    /**
     * Triggers the GraphCall cancellation signal: signals all Replies associated with this GraphCall that they should
     * cancel themselves. This method delegates responsibility by triggering all MemoryScope cancellation signals. This
     * process may be passive or active, depending on the types of cancellation hooks that Nodes in the graph support.
     * If all of the Nodes only support passive hooks, then those MemoryScopes will update their status and allow the
     * hooks to poll that status and take the appropriate action. If any Node supports active hooks, then those
     * MemoryScopes will cause those actions to be executed (in addition to triggering the MemoryScope cancellation
     * signal).
     * 
     * This method can be called explicitly by users and implicitly by the Aggra framework. The latter happens after
     * {@link GraphCall#weaklyClose()} and root Node Replies are completed or as a part of {@link #abandon()}.
     * 
     * Any exception thrown by any run custom cancel action will be suppressed and made available through
     * {@link GraphCall.FinalState#getUnhandledExceptions()}.
     * 
     * This method can be run after {@link #weaklyClose()} is called... although, obviously, it will have no effect if
     * all of this GraphCall's Nodes are already complete.
     * 
     * @apiNote Calls to this method appear in critical code, which assumes that this method doesn't throw an exception.
     *          The implementations are so simple that they should not throw any exceptions except in very rare,
     *          the-program-is-doomed-anyway scenarios like OutOfMemoryError.
     * @implNote Because of the delegation to MemoryScope, if {@link #isCancelSignalTriggered} returns true, then
     *           {@link MemoryScope#isCancelSignalTriggered()} will also return true.
     */
    public void triggerCancelSignal() {
        // Delegate triggering of the GraphCall cancellation signal to the root MemoryScope, which because of the way it
        // works, is capable of implementing all GraphCall cancellation signal features.
        memory.getScope().triggerCancelSignal();
    }

    /**
     * Answers whether the GraphCall cancellation signal has been triggered. See {@link #triggerCancelSignal()} for a
     * full discussion of when this could happen (and when that trigger may be blocked).
     */
    public boolean isCancelSignalTriggered() {
        return memory.getScope().isCancelSignalTriggered();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    }

    /**
     * A utility class to provide more of an input-focused API for invoking graph nodes. The idea is that this class
     * could be stored between requests and then clients could invoke the {@link Factory#openCall(CompletableFuture)}
     * API for every time they want to create a call using only the input needed to create the target Memory.
     */
    public static class Factory<I, M extends Memory<I>> {
        private final Graph<M> graph;
        private final MemoryNoSourceFactory<I, M> factory;

        private Factory(Graph<M> graph, MemoryNoSourceFactory<I, M> factory) {
            this.graph = Objects.requireNonNull(graph);
            this.factory = Objects.requireNonNull(factory);
        }

        /**
         * @param factory the factory that will be invoked to create the returned Call object's Memory every time
         *        {@link #openCall(CompletableFuture)} is called.
         */
        public static <I, M extends Memory<I>> Factory<I, M> from(Graph<M> graph, MemoryNoSourceFactory<I, M> factory) {
            return new Factory<>(graph, factory);
        }

        /**
         * Same as {@link Graph#openCancellableCall(MemoryNoSourceInputFactory, Observer))}, except more input-focused.
         * Input will be used to create the desired Memory for the Call from the Memory factory used in the constructor.
         * 
         * @throws in addition to the exceptions thrown by {@link Graph#openCancellableCall(MemoryNoSourceInputFactory,
         *         Observer))}, this method also throws MisbehaviorException if the Memory created by the factory passed
         *         to the constructor is different from the input argument.
         */
        public GraphCall<M> openCancellableCall(CompletionStage<I> input, Observer observer) {
            return graph.openCancellableCall(MemoryNoSourceFactory.toNoSourceInputFactory(factory, input), observer);
        }

        /**
         * Same as {@link #openCancellableCall(CompletionStage, Observer)}, except accepts the input directly, which
         * this method will conveniently wrap in a CompletableFuture before passing it along.
         */
        public GraphCall<M> openCancellableCall(I input, Observer observer) {
            CompletableFuture<I> inputFuture = CompletableFuture.completedFuture(input);
            return openCancellableCall(inputFuture, observer);
        }
    }

    /**
     * Same as {@link Factory}, except instead of being input focused, this API assumes that no input is needed to
     * create the desired Call object. This would be useful, for example, with Graphs that require no input to execute.
     */
    public static class NoInputFactory<M extends Memory<?>> {
        private final Graph<M> graph;
        private final MemoryNoSourceInputFactory<M> factory;

        private NoInputFactory(Graph<M> graph, MemoryNoSourceInputFactory<M> factory) {
            this.graph = Objects.requireNonNull(graph);
            this.factory = Objects.requireNonNull(factory);
        }

        public static <M extends Memory<?>> NoInputFactory<M> from(Graph<M> graph,
                MemoryNoSourceInputFactory<M> factory) {
            return new NoInputFactory<>(graph, factory);
        }

        /**
         * Same as {@link Graph#openCancellableCall(MemoryNoSourceInputFactory, Observer))}, except using the factory
         * passed to the constructor as the argument.
         */
        public GraphCall<M> openCancellableCall(Observer observer) {
            return graph.openCancellableCall(factory, observer);
        }
    }
}
