/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Controls how to handle failures encountered while priming a Node's dependencies. "Failures" here means failures
 * *returned* by primed dependencies. If the priming phase itself fails by *throwing* an exception, then the priming
 * phase will always end early (before any primed node calls complete) and the node's behavior will never be run.
 */
public enum PrimingFailureStrategy {
    /**
     * Regardless of any failures, wait for all primed dependencies to complete, and then continue running the Node's
     * behavior. This is the default strategy.
     */
    WAIT_FOR_ALL_CONTINUE,
    /**
     * If any dependency fails during the priming phase, then immediately stop priming (regardless of whether any other
     * primed dependency is still running) and then fail the Node call with that failure (meaning don't run the Node
     * Behavior). The idea for wanting to use this is efficiency: if a primed dependency's failure is going to cause the
     * Node's behavior to fail with the same failure anyway, you might as well not wait for the other primed
     * dependencies to finish.
     * 
     * There are a couple of costs to using this. <br>
     * * First, by not waiting for all primed dependencies to finish, you may get an inconsistent set of suppressed
     * exceptions depending on which {@link ExceptionStrategy} is in use.<br>
     * * Second, using this will create some processing steps (proportional in number to the number of primed
     * dependencies) that may continue even after the response from {@link GraphCall#weaklyClose()} is complete
     * (although it's unlikely to do so). This is one of the only places in the Aggra framework where this is allowed to
     * happen (search for "AFTER_WEAKLY_CLOSE" for the others). The extra processing steps are lightweight in that
     * they're only a few lines long and they're no-ops. The extra cost of making sure they are done is more than the
     * cost to just let them run, especially considering the (un)likelihood of this happening.<br>
     * * Third, you're only allowed to use DepenedencyLifetimes where
     * {@link DependencyLifetime#waitsForDirectDependencies()} is false. Any other value wouldn't make sense with this
     * strategy, since if the Node is going to wait for all Dependencys anyway, there's no concept of failing fast.
     */
    FAIL_FAST_STOP {
        @Override
        void requireCompatibleWithDependencyLifetime(DependencyLifetime lifetime) {
            if (lifetime.waitsForDirectDependencies()) {
                String message = "FAIL_FAST_STOP is useless when the DependencyLifetime makes Nodes wait for their dependencies anyway: "
                        + lifetime;
                throw new IllegalArgumentException(message);
            }
        }

        @Override
        boolean shouldContinueOnPrimingFailures() {
            return false;
        }

        @Override
        void modifyPrimingCompletion(CompletableFuture<Void> allOfBackingPrimedReplies,
                Collection<Reply<?>> primedReplies) {
            primedReplies.forEach(reply -> reply.exceptionally(throwable -> {
                allOfBackingPrimedReplies.completeExceptionally(throwable);
                return null;
            }));
        }
    };

    /**
     * Checks that this value is compatible with the given DependencyLifetime.
     * 
     * @throws IllegalArgumentException if this value is not compatible.
     */
    void requireCompatibleWithDependencyLifetime(DependencyLifetime lifetime) {}

    /**
     * If there are any failures in the priming phase response, should we continue, executing the Behavior anyway, or
     * should we just stop and not execute the Node's behavior and return the failure for the Node response? Note: if
     * the priming phase itself throws an exception (e.g. a primed dependency *throws* an exception when called), then
     * this method doesn't apply, as execution will always stop. Instead, this method only applies when the priming
     * phase completes but the response from the priming phase is a failure (e.g. when a primed dependency *returns* a
     * response holding an exception).
     * 
     * The default answer is no, but implementations can override.
     */
    boolean shouldContinueOnPrimingFailures() {
        return true;
    }

    /**
     * Gives the opportunity for implementations to modify how the priming future works based on the Replys from the
     * primed Dependencys. The default behavior is to do nothing.
     * 
     * @param allOfBackingPrimedReplies the CompletableFuture that's already set up to complete when all of the
     *        primedReplies complete.
     * @apiNote it's unfortunate that I have to expose allOfBackingPrimedReplies (created in DependencyCallingDevice)
     *          here for modification. However, to me, this behavior belongs here in the PrimingFailureStrategy rather
     *          than inserting a switch statement based on PrimingFailureStrategy in the DependencyCallingDevice code.
     */
    void modifyPrimingCompletion(CompletableFuture<Void> allOfBackingPrimedReplies,
            Collection<Reply<?>> primedReplies) {}
}
