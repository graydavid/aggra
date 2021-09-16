/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.onemoretry.Try;

/**
 * Tracks the lifecycle for groups of Memorys (and Replies associated with them) created at the same time. As part of
 * creating new Memories, users may create ancestor Memories at the same time. Aggra would otherwise be unaware of these
 * ancestors, having been user-created, but there are certain things that Aggra may want to track about them, like
 * making sure Replies associated with them are cancelled. MemoryScope allows this tracking to take place: Memories
 * require MemoryScopes when they're created; users cannot create MemoryScopes on their own but are instead provided
 * with MemoryScopes by Aggra at creation time; and users can use these MemoryScopes to create Memories and their needed
 * ancestor Memories. In this way, when creating a new Memory, this new Memory and all simultaneously-created Memorys
 * all end up with the same MemoryScope.
 * 
 * Practically speaking, MemoryScope's job is to manage the triggering of the MemoryScope cancellation signal. There are
 * two aspects to this job. <br>
 * * First, there must be a way to cancel a MemoryScope when all "externally-accessible" Replies for a MemoryScope are
 * completed. Take {@link DependencyCallingDevice#createMemoryAndCall} as an example. The Reply from NewMemoryDependency
 * is the only "externally accessible" Reply from the newly created MemoryScope (and any other transitively-created
 * descendant MemoryScopes, but more on that in a second), because it's the only Reply directly consumable by any Reply
 * that existed before the MemoryScope was made. (In fact, the only consumer that can consume the Reply directly is the
 * one who invoked the DependencyCallingDevice call.) Because of that, once this Reply is complete, we know that every
 * other Reply still running as part of the MemoryScope (or any transitively-created descendant MemoryScopes) is now
 * "useless", in the sense that noone will consume the results. Because of that, all of these Replies can be cancelled.
 * MemoryScope helps manage that. <br>
 * * Second, as hinted about in the first aspect, there must be a way to cancel any child MemoryScopes of a MemoryScope
 * that itself has been cancelled. One can imagine MemoryScopes creating a hierarchy. The root of this hierarchy is the
 * MemoryScope created at the top level for the overall GraphCall itself. Every MemoryScope created by a node in this
 * root MemoryScope can be considered a child of that root scope... and so on recursively. Now, when a given MemoryScope
 * is cancelled, that means all of its Replies will be cancelled. By a similar argument as in the first aspect, that
 * means all possible consumers of any descendant MemoryScope will be cancelled, meaning the descendant MemoryScope and
 * all of their Replies can be cancelled as well.
 * 
 * Note: the above two aspects also allow GraphCall to use the root MemoryScope to manage GraphCall cancellation signal
 * triggering... but MemoryScope itself has no idea about this usage.
 */
public abstract class MemoryScope {

    private MemoryScope() {}

    /** Answers whether this MemoryScope supports active cancel hooks. */
    abstract boolean supportsActiveCancelHooks();

    /**
     * Adds a Reply that this MemoryScope will keep track of. This is necessary so that, when the MemoryScope
     * cancellation signal is triggered, this scope can actively reach out to all of the tracked Replys and trigger
     * their Reply cancellation signals. As long as all active-hook-supporting Replys for a MemoryScope are tracked by
     * this MemoryScope, this scheme helps ensure that the MemoryScope successfully executes its role in helping
     * implement active cancellation hooks.
     * 
     * If, when this method is called, the MemoryScope cancellation signal is already triggered, the scope will trigger
     * the Reply cancellation signal right away. The same can also happen if the signal is triggered during the course
     * of this method's execution. Either way, whether the Reply is officially still "being tracked" at the end of this
     * method, its Reply cancellation signal will be triggered.
     * 
     * @throws IllegalArgumentException if reply's Node's CancelMode doesn't support active hooks. This is for
     *         efficiency's sake. There's no reason for this MemoryScope to trigger a Reply's cancel signal if the Reply
     *         is not going to take any action. Even if reply supports the Reply signal's passive hook, there's still no
     *         reason to trigger it. The Reply signal is never read solely by itself but only in combination with the
     *         MemoryScope and GraphCall signals. So, since MemoryScope will already mark itself as cancelled, there's
     *         no reason to further mark Replys that are only responsive to passive hooks.
     * @throws UnsupportedOperationException if the MemoryScope doesn't support active cancel hooks, as determined by
     *         {@link #supportsActiveCancelHooks()}. This is an extension of the IllegalArgumentException above, just
     *         more extreme.
     * 
     * @apiNote Calls to this method appear in critical code, which assumes that this method doesn't throw an exception.
     *          The exceptions mentioned above are internal checks (since this is, afterall, an internal method) to make
     *          sure Aggra is behaving itself. Otherwise, the implementations are so simple that they should not throw
     *          any exceptions except in very rare, the-program-is-doomed-anyway scenarios like OutOfMemoryError.
     */
    abstract void addReplyToTrack(Reply<?> reply);

    /**
     * Adds a child (to this scope) MemoryScope that this scope will keep track of. This is necessary so that, when this
     * scope's MemoryScope cancellation signal is triggered, this scope can actively reach out to all of the tracked
     * MemoryScopes and trigger their MemoryScope cancellation signals as well. As long as all active-hook-supporting
     * child MemoryScopes of this scope are tracked by this scope, this scheme helps ensure that the MemoryScope class
     * successfully executes its role in helping implement active cancellation hooks.
     * 
     * If, when this method is called, the MemoryScope cancellation signal is already triggered, this scope will trigger
     * the child MemoryScope's MemoryScope cancellation signal right away. The same can also happen if the signal is
     * triggered during the course of this method's execution. Either way, whether the child MemoryScope is officially
     * still "being tracked" at the end of this method, its MemoryScope cancellation signal will be triggered.
     * 
     * @throws IllegalArgumentException if the child MemoryScope support active hooks, as determined by its
     *         {@link #supportsActiveCancelHooks()}. This is for efficiency's sake. There's no reason for this
     *         MemoryScope to trigger a child's MemoryScope cancel signal if the child is not going to take any action.
     *         Even if the child {@link #isResponsiveToCancelSignal()} passively, there's still no reason to trigger it,
     *         since it will use {@link #getNearestAncestorOrSelfResponsiveToCancelSignal()} to answer that question
     *         passively.
     * @throws UnsupportedOperationException if this scope doesn't support active cancel hooks, as determined by
     *         {@link #supportsActiveCancelHooks()}. This is an extension of the IllegalArgumentException above, just
     *         more extreme.
     * 
     * @apiNote Calls to this method appear in critical code, which assumes that this method doesn't throw an exception.
     *          The exceptions mentioned above are internal checks (since this is, afterall, an internal method) to make
     *          sure Aggra is behaving itself. Otherwise, the implementations are so simple that they should not throw
     *          any exceptions except in very rare, the-program-is-doomed-anyway scenarios like OutOfMemoryError.
     */
    abstract void addChildMemoryScopeToTrack(MemoryScope childMemoryScope);

    /**
     * Triggers the MemoryScope cancellation signal for this specific MemoryScope: signals all Replies associated with
     * this MemoryScope that they should cancel themselves. This process may be passive or active, depending on the
     * types of cancellation hooks that any potential Nodes in the MemoryScope support. If all of the Nodes only support
     * passive hooks, then this MemoryScope will mark itself as cancelled and allow the hooks to poll that status and
     * take the appropriate action. If any potential Node in this or any descendant MemoryScope supports active hooks,
     * then this method will cause those actions to be executed (in addition to the status being set).
     * 
     * This method may only be called when all externally-accessible Replies for a MemoryScope are completed, when an
     * ancestor MemoryScope's cancel signal is triggered, or when GraphCall decides (either through a user request or
     * something else) to trigger its root MemoryScopes cancel signal.
     * 
     * For efficiency, this method may do nothing. This is only allowable for non-root MemoryScopes that don't support
     * active-cancellation hooks. In addition all externally-accessible Nodes for the MemoryScope must effectively have
     * {@link DependencyLifetime#waitsForAllDependencies()}. To see why, let's run through all possible triggering
     * scenarios:<br>
     * * All externally-accessible Replies for the MemoryScope are completed -- because all externally-accessible Nodes
     * wait for all of their dependencies, then we know that there will be no Replies still running when this method is
     * called in this scenario. Furthermore, we know that, because this scope will list itself as not
     * {@link #isResponsiveToCancelSignal()}, none of its descendants will query it to determine their triggering
     * status.<br>
     * * An ancestor MemoryScope's cancel signal is triggered -- the MemoryScope can delegate
     * {@link #isResponsiveToCancelSignal()} to {@link #getNearestAncestorOrSelfResponsiveToCancelSignal()}. So, in this
     * case, the cancellation status will still be changed as far as the client is concerned, but the implementation of
     * this method still does nothing.<br>
     * * GraphCall decides to trigger its root MemoryScopes cancel signal -- explicitly excluded from situations where
     * this method is allowed to do nothing.
     * 
     * Doing nothing can yield extra efficiency, as the MemoryScope doesn't have to track and update the cancellation
     * status (i.e. no need for a volatile boolean). As such, users only pay for the features they use (cancellability
     * being one of those features).
     * 
     * @apiNote Calls to this method appear in critical code, which assumes that this method doesn't throw an exception.
     *          The implementations are so simple that they should not throw any exceptions except in very rare,
     *          the-program-is-doomed-anyway scenarios like OutOfMemoryError.
     */
    abstract void triggerCancelSignal();

    /**
     * Answers whether this instance is responsive to {@link #triggerCancelSignal()}: i.e. does calling
     * "triggerCancelSignal" do anything, even just updating the status returned by {@link #isCancelSignalTriggered()}.
     * MemoryScopes are allowed to be non-responsive if they can prove that any execution of
     * {@link #triggerCancelSignal()} would be a no-op.
     */
    abstract boolean isResponsiveToCancelSignal();

    /**
     * Answers whether the MemoryScope cancellation signal has been triggered. See {@link #triggerCancelSignal()} for a
     * full discussion of when this could happen (and when that trigger may cause nothing to happen at all).
     * 
     * Note: because of the note for {@link GraphCall#triggerCancelSignal()}, this method will also return true if the
     * GraphCall cancellation signal has been triggered.
     */
    abstract boolean isCancelSignalTriggered();

    /**
     * Of this scope's ancestors or self, returns the nearest one such that {@link #isResponsiveToCancelSignal()}.
     * "Nearest" means distance from this scope (e.g. 0 for self, 1 for parent, 2 for grandparent, etc.).
     */
    abstract MemoryScope getNearestAncestorOrSelfResponsiveToCancelSignal();

    /**
     * Creates a MemoryScope for a Graph, where the root nodes determine the externally-accessible nodes in play.
     * 
     * @apiNote By targeting graph specifically, we can take advantage of values that it caches. Compare that to a
     *          method that accepts an arbitrary set of externally-accessible nodes, where we have to iterate over those
     *          nodes to compute the relevant properties. The former is preferable, especially since there is no other
     *          way currently to create a MemoryScope for an arbitrary set of externally-accessible Nodes outside of a
     *          Graph.
     */
    static MemoryScope createForGraph(Graph<?> graph) {
        if (graph.shadowSupportsActiveCancelHooks()) {
            return new ResponsiveToCancelMemoryScope();
        }

        return new RootResponsiveToPassiveCancelMemoryScope();
    }

    /**
     * Creates a MemoryScope for a single externally-accessible Node for that MemoryScope within a given parent
     * MemoryScope.
     */
    static MemoryScope createForExternallyAccessibleNode(Node<?, ?> node, MemoryScope parent) {
        if (node.shadowSupportsActiveCancelHooks()) {
            MemoryScope responsive = new ResponsiveToCancelMemoryScope();
            parent.addChildMemoryScopeToTrack(responsive);
            return responsive;
        }

        MemoryScope nearestAncestorResponsiveToCancellationSignal = parent
                .getNearestAncestorOrSelfResponsiveToCancelSignal();
        boolean allExternalNodesWaitForAll = node.getMinimumDependencyLifetime().waitsForAllDependencies();
        return allExternalNodesWaitForAll
                ? new NonresponsiveToCancelMemoryScope(nearestAncestorResponsiveToCancellationSignal)
                : new NonRootResponsiveToPassiveCancelMemoryScope(nearestAncestorResponsiveToCancellationSignal);
    }

    /**
     * A Memory scope that is non-responsive to the MemoryScope cancellation signal. See
     * {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is appropriate.
     */
    private static class NonresponsiveToCancelMemoryScope extends MemoryScope {
        private final MemoryScope nearestAncestorResponsiveToCancellationSignal;

        protected NonresponsiveToCancelMemoryScope(MemoryScope nearestAncestorResponsiveToCancellationSignal) {
            this.nearestAncestorResponsiveToCancellationSignal = nearestAncestorResponsiveToCancellationSignal;
        }

        @Override
        boolean supportsActiveCancelHooks() {
            return false;
        }

        @Override
        void addReplyToTrack(Reply<?> reply) {
            throw new UnsupportedOperationException();
        }

        @Override
        void addChildMemoryScopeToTrack(MemoryScope childMemoryScope) {
            throw new UnsupportedOperationException();
        }

        @Override
        void triggerCancelSignal() {}

        @Override
        boolean isCancelSignalTriggered() {
            return nearestAncestorResponsiveToCancellationSignal.isCancelSignalTriggered();
        }

        @Override
        boolean isResponsiveToCancelSignal() {
            return false;
        }

        @Override
        MemoryScope getNearestAncestorOrSelfResponsiveToCancelSignal() {
            return nearestAncestorResponsiveToCancellationSignal;
        }
    }

    /**
     * A Memory scope that is responsive to passive hooks for the MemoryScope cancellation signal. See
     * {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is appropriate.
     */
    private abstract static class ResponsiveToPassiveCancelMemoryScope extends MemoryScope {
        private volatile boolean cancelled;

        protected ResponsiveToPassiveCancelMemoryScope() {
            this.cancelled = false;
        }

        @Override
        boolean supportsActiveCancelHooks() {
            return false;
        }

        @Override
        void addReplyToTrack(Reply<?> reply) {
            throw new UnsupportedOperationException();
        }

        @Override
        void addChildMemoryScopeToTrack(MemoryScope childMemoryScope) {
            throw new UnsupportedOperationException();
        }

        @Override
        void triggerCancelSignal() {
            cancelled = true;
        }

        @Override
        boolean isCancelSignalTriggered() {
            return cancelled;
        }

        @Override
        boolean isResponsiveToCancelSignal() {
            return true;
        }

        @Override
        MemoryScope getNearestAncestorOrSelfResponsiveToCancelSignal() {
            return this;
        }
    }

    /** A ResponsiveToPassiveCancelMemoryScope for root MemoryScopes, which don't have any ancestors. */
    private static class RootResponsiveToPassiveCancelMemoryScope extends ResponsiveToPassiveCancelMemoryScope {
        protected RootResponsiveToPassiveCancelMemoryScope() {}
    }

    /** A ResponsiveToPassiveCancelMemoryScope for non-root MemoryScopes, which do have any ancestors. */
    private static class NonRootResponsiveToPassiveCancelMemoryScope extends ResponsiveToPassiveCancelMemoryScope {
        private final MemoryScope nearestAncestorResponsiveToCancellationSignal;

        protected NonRootResponsiveToPassiveCancelMemoryScope(
                MemoryScope nearestAncestorResponsiveToCancellationSignal) {
            this.nearestAncestorResponsiveToCancellationSignal = nearestAncestorResponsiveToCancellationSignal;
        }

        @Override
        boolean isCancelSignalTriggered() {
            return super.isCancelSignalTriggered()
                    || nearestAncestorResponsiveToCancellationSignal.isCancelSignalTriggered();
        }
    }

    /**
     * A Memory scope that is fully-responsive, both to passive and active hooks, for the MemoryScope cancellation
     * signal. See {@link #isResponsiveToCancelSignal()} and {@link #triggerCancelSignal()} javadoc for when this is
     * appropriate.
     */
    private static class ResponsiveToCancelMemoryScope extends MemoryScope {
        // VarHandle mechanics
        private static final VarHandle CANCELLED;
        static {
            // Note: used Try paradigm to avoid impossible catch block artificially lowering code coverage. If the try
            // block fails, it will explode, and I don't care how.
            CANCELLED = Try.callCatchException(() -> {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                return lookup.findVarHandle(ResponsiveToCancelMemoryScope.class, "cancelled", boolean.class);
            }).getOrThrowUnchecked(ExceptionInInitializerError::new);
        }

        private volatile boolean cancelled;
        private final Queue<Reply<?>> trackedReplies;
        private final Queue<MemoryScope> trackedChildMemoryScopes;

        protected ResponsiveToCancelMemoryScope() {
            this.cancelled = false;
            this.trackedReplies = new ConcurrentLinkedQueue<>();
            this.trackedChildMemoryScopes = new ConcurrentLinkedQueue<>();
        }

        @Override
        boolean supportsActiveCancelHooks() {
            return true;
        }

        @Override
        void addReplyToTrack(Reply<?> reply) {
            if (!reply.getNode().getCancelMode().supportsActiveHooks()) {
                throw new IllegalArgumentException(
                        "Can't/Shouldn't add a reply whose Node doesn't support active cancel hooks: " + reply);
            }

            // Note: could add a branch above this to optimize the cancelled case: if cancelled, trigger reply's cancel
            // signal and return, avoiding the need to add it to trackedReplies and then remove it. However, that would
            // be an extra read of a volatile variable in the non-cancelled case, which I consider to be more common.
            trackedReplies.add(reply);

            if (cancelled) {
                drainAndCancelTrackedReplies();
            }
        }

        private void drainAndCancelTrackedReplies() {
            Reply<?> reply;
            while ((reply = trackedReplies.poll()) != null) {
                reply.triggerCancelSignal();
            }
        }

        @Override
        void addChildMemoryScopeToTrack(MemoryScope childMemoryScope) {
            if (!childMemoryScope.supportsActiveCancelHooks()) {
                throw new IllegalArgumentException(
                        "Can't/Shouldn't add a memory scope that doesn't support active cancel hooks: "
                                + childMemoryScope);
            }

            // Note: could add a branch above this to optimize the cancelled case: if cancelled, trigger memoryScope's
            // cancel signal and return, avoiding the need to add it to trackedMemoryScopes and then remove it.
            // However, that would be an extra read of a volatile variable in the non-cancelled case, which I consider
            // to be more common.
            trackedChildMemoryScopes.add(childMemoryScope);

            if (cancelled) {
                drainAndCancelTrackedChildMemoryScopes();
            }
        }

        private void drainAndCancelTrackedChildMemoryScopes() {
            MemoryScope memoryScope;
            while ((memoryScope = trackedChildMemoryScopes.poll()) != null) {
                memoryScope.triggerCancelSignal();
            }
        }

        @Override
        void triggerCancelSignal() {
            if (CANCELLED.compareAndSet(this, false, true)) {
                drainAndCancelTrackedReplies();
                drainAndCancelTrackedChildMemoryScopes();
            }
        }

        @Override
        boolean isResponsiveToCancelSignal() {
            return true;
        }

        @Override
        boolean isCancelSignalTriggered() {
            return cancelled;
        }

        @Override
        MemoryScope getNearestAncestorOrSelfResponsiveToCancelSignal() {
            return this;
        }
    }
}
