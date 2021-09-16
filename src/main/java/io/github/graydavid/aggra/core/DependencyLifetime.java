/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.graydavid.aggra.core.DependencyCallingDevices.FinalState;

/**
 * Describes the maximum amount of (relative) time that a Node's (direct and/or transitive) dependencies can run for.
 * This idea helps support a feature that Nodes provide to users creating them: helping ensure that a Node's
 * dependencies live a certain amount of time, without the user having to implement that restriction themselves.
 */
public enum DependencyLifetime {
    /**
     * All of a Node's direct and transitive dependencies will complete before the Node itself completes. This is useful
     * for users if they need to be sure that all of a Node's dependencies are complete when they access the Node's
     * results, making sure there are no "background jobs" still running. The downside is that if a Node decides to
     * ignore a dependency call (either implicitly or explicitly, e.g. because it doesn't care about it anymore), the
     * Node will still wait around for it to complete before the Node itself can complete.
     */
    NODE_FOR_ALL {
        @Override
        public boolean waitsForAllDependencies() {
            return true;
        }

        @Override
        DependencyLifetime minimumLifetimeWithDependencies(Collection<DependencyLifetime> dependencyMinimumLifetimes) {
            return NODE_FOR_ALL;
        }

        @Override
        CompletableFuture<?> calculateReplyNodeForAllSignal(CompletableFuture<?> backing) {
            // The backing future already implements NODE_FOR_ALL, so just reuse it
            return backing;
        }

        @Override
        void enforceLifetimeForReply(DependencyCallingDevices.FinalState deviceFinalState,
                Consumer<Set<Reply<?>>> backingCompleter, Runnable nodeForAllSignalCompleter) {
            Reply.allOfProbablyCompleteNodeForAllSignal(deviceFinalState.getAllDependencyReplies())
                    .whenComplete((ignore1, ignore2) -> {
                        backingCompleter.accept(deviceFinalState.getAllDependencyReplies());

                        // No need to complete nodeForAllSignal separately, since it's the same as the backing.
                        // See #calculateReplyNodeForAllSignal
                    });
        }
    },
    /**
     * All of a Node's direct dependencies will complete before the Node itself completes. This value sits in the middle
     * of {@link #NODE_FOR_ALL} and {@link #GRAPH}, mixing the advantages and disadvantages of each.
     * 
     * This is the default value for Nodes, and here's why. First, if a Node with this value depends (both directly and
     * transitively) on other Nodes that only have this value, that effectively makes the node a NODE_FOR_ALL. The
     * necessary machinery to implement NODE_FOR_ALL is relatively cheap and is necessary anyway to implement
     * cancellability and resource nodes. Second, all of the common nodes in io.github.graydavid.aggra.nodes behave in
     * this way inherently: making sure that all of their dependencies are finished before proceeding forward. Third, if
     * a user does decide they want cancellable behavior and so use the GRAPH property for a Node, then consumers of
     * that Node (with the default NODE_FOR_DIRECT value) won't still sit around for any cancelled Nodes to complete. In
     * other words, each Node is modularly individually responsible for deciding how important its dependencies
     * finishing is to what it calculates. Consuming Nodes can always NODE_FOR_ALL and wait on those cancelled Nodes to
     * complete if they want.
     */
    NODE_FOR_DIRECT {
        @Override
        DependencyLifetime minimumLifetimeWithDependencies(Collection<DependencyLifetime> dependencyMinimumLifetimes) {
            return dependencyMinimumLifetimes.stream().allMatch(NODE_FOR_ALL::equals) ? NODE_FOR_ALL : NODE_FOR_DIRECT;
        }

        @Override
        void enforceLifetimeForReply(DependencyCallingDevices.FinalState deviceFinalState,
                Consumer<Set<Reply<?>>> backingCompleter, Runnable nodeForAllSignalCompleter) {
            Reply.allOfBacking(deviceFinalState.getIncompleteDependencyReplies()).whenComplete((ignore1, ignore2) -> {
                backingCompleter.accept(deviceFinalState.getAllDependencyReplies());

                Reply.allOfProbablyCompleteNodeForAllSignal(deviceFinalState.getAllDependencyReplies())
                        .whenComplete((ignore3, ignore4) -> nodeForAllSignalCompleter.run());
            });

        }
    },
    /**
     * All (direct and transitive) dependencies will complete before the overall Graph Call they're a part of completes.
     * This is useful for users who want to ignore dependencies and don't want the Node to wait around for them to
     * finish. That a Node would have this restriction at all (rather than, say, having an arbitrary lifetime) has a
     * couple of benefits: first, users can access any ignored replies that result in errors; and second, this helps
     * make sure any resources used by the Graph Call are freed up (e.g. threads, CPU), creating a kind of signal to
     * users that maybe they should wait before creating more Graph Calls. The downside of this value is consumers of a
     * given Node can't be sure that its dependencies are complete, which might be important for the consumer's logic.
     * The consumer could always create a direct dependency itself on whatever transient dependency it cares about, but
     * that might break abstraction.
     */
    GRAPH {
        public boolean waitsForDirectDependencies() {
            return false;
        }

        @Override
        DependencyLifetime minimumLifetimeWithDependencies(Collection<DependencyLifetime> dependencyMinimumLifetimes) {
            return dependencyMinimumLifetimes.isEmpty() ? NODE_FOR_ALL : GRAPH;
        }

        @Override
        void enforceLifetimeForReply(DependencyCallingDevices.FinalState deviceFinalState,
                Consumer<Set<Reply<?>>> backingCompleter, Runnable nodeForAllSignalCompleter) {
            backingCompleter.accept(deviceFinalState.getCompleteDependencyReplies());

            Reply.allOfProbablyCompleteNodeForAllSignal(deviceFinalState.getAllDependencyReplies())
                    .whenComplete((ignore1, ignore2) -> nodeForAllSignalCompleter.run());
        }
    };

    /**
     * Does this lifetime indicate that the owning Node will wait for all dependencies (both direct and transitive) to
     * complete before completing itself? The default answer is no, but implementations can redefine.
     */
    public boolean waitsForAllDependencies() {
        return false;
    }

    /**
     * Does this lifetime indicate that the owning Node will wait for direct dependencies (i.e. not including
     * transitive) to complete before completing itself? The default answer is yes, but implementations can redefine.
     */
    public boolean waitsForDirectDependencies() {
        return true;
    }

    /**
     * Answers what the effective minimum DependencyLifetime of this value would be for a node with this value and the
     * given effective minimum lifetimes of dependency Nodes.
     */
    abstract DependencyLifetime minimumLifetimeWithDependencies(
            Collection<DependencyLifetime> dependencyMinimumLifetimes);

    /**
     * Calculates a Reply's NODE_FOR_ALL signal given the Node's backing future. (See Reply#nodeForAllSignal for more
     * details on what this signal means.) Implementations can reuse the backing future, if appropriate. The default
     * implementation simply creates a new CompletableFuture and returns it. Implementations are not allowed to modify
     * the backing future or expose it to external code (which could modify it).
     * 
     * Together, this method and
     * {@link #startCompleteForReply(FinalState, io.github.graydavid.aggra.core.GraphCall, Consumer, Runnable)} help
     * Reply manage its lifecycle.
     */
    CompletableFuture<?> calculateReplyNodeForAllSignal(CompletableFuture<?> backing) {
        return new CompletableFuture<>();
    }

    /**
     * Enforces the dependency lifetime of for a Node Call based on its DependencyCallingDevice's FinalState. This
     * method's job is to make sure the Reply's backing future and NODE_FOR_ALL signal complete when expected.
     * 
     * @param deviceFinalState the FinalState of the DependencyCallingDevice after being weakly closed, which happens
     *        after a Node's behavior has completed (or on exception).
     * @param backingCompleter calling this will complete the Reply's backing future. It accepts the collection of
     *        dependency Replys that are eligible to evaluate for exceptions to pass to a Node's exception strategy.
     * @param nodeForAllSignalCompleter running this will complete the Reply's NODE_FOR_ALL signal.
     */
    abstract void enforceLifetimeForReply(DependencyCallingDevices.FinalState deviceFinalState,
            Consumer<Set<Reply<?>>> backingCompleter, Runnable nodeForAllSignalCompleter);
}
