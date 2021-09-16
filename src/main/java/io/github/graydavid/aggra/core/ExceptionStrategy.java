/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.github.graydavid.aggra.core.Behaviors.Behavior;

/**
 * Controls how to create container exceptions to return from {@link Node#call(Caller, Memory, CallObservers.Observer)}
 * based on a root exception. A "container exception" means a CompletionException caused by a CallException. The "root
 * exception" is the exception returned from {@link Behavior}, for example, or the result of some other piece of
 * implementation in the call method. The "root exception" ends up being the cause of the CallException. The point of
 * setting up a scheme like this is to ensure that all exceptional responses returned from the call method are uniform.
 */
public enum ExceptionStrategy {
    /**
     * Adds dependency failures as suppressed exceptions to the container exception for the originalNodeFailure. There
     * are a few nuances to this.
     * 
     * First, if there are no dependency failures, then the minimally transformed container exception for the
     * originalNodeFailure is returned.
     * 
     * Second, if the originalNodeFailure is already a container exception and matches all of the dependency failures,
     * then it's returned as is. The idea here is to propagate exceptions when possible so as not to incur the
     * cost/confusion of always adding suppressed exceptions. Additionally, we want to avoid creating circular
     * suppression chains.
     * 
     * Third, if the originalNode contains the CallException portion of a container exception, then that CallException
     * undergoes a shallow copy. The minimally transformed container exception for the originalNodeException, along with
     * all dependency failures, will be added as suppressed exceptions to that shallow copy. A container exception for
     * this shallow copy will be returned. The idea here is to avoid creating circular suppression chains: suppressed
     * exceptions are only added to CallExceptions in this method if this call to ExceptionStrategy has created those
     * CallExceptions: we will not add suppressed exceptions to any pre-existing CallException passed into
     * ExceptionStrategy.
     * 
     * Fourth, if the originalNode does not contain any CallException portion, then it's minimally transformed and all
     * dependency failures are added as suppressed exceptions.
     * 
     * Note: whenever suppressed exceptions are added, they are added as a set together: in other words, they're
     * de-duped such that each suppressed exception is unique from the others.
     */
    SUPPRESS_DEPENDENCY_FAILURES {
        @Override
        protected CompletionException handleDependencyFailures(Throwable originalNodeFailure,
                CompletionException containerNodeFailure,
                Supplier<Collection<CompletionException>> dependencyFailuresSupplier) {
            // Check if we even need to suppress anything or if every failure already matches
            Collection<CompletionException> dependencyFailures = dependencyFailuresSupplier.get();
            boolean allDependencyFailuresMatchCallException = dependencyFailures.stream()
                    .allMatch(containerNodeFailure::equals);
            if (allDependencyFailuresMatchCallException) {
                return containerNodeFailure;
            }

            return computeFinalContainerFailureAndAddSuppressed(originalNodeFailure, containerNodeFailure,
                    dependencyFailures);
        }


        private CompletionException computeFinalContainerFailureAndAddSuppressed(Throwable originalNodeFailure,
                CompletionException containerNodeFailure, Collection<CompletionException> dependencyFailures) {
            // To avoid suppression loops, only add suppressed exceptions to completely new CallExceptions.
            // If we're reusing a callException, then shallow copy it for a new container exception.
            CallException containerCallException = (CallException) containerNodeFailure.getCause();
            boolean containerCallExceptionIsReusedFromOriginal = (containerCallException == originalNodeFailure
                    || containerNodeFailure == originalNodeFailure);
            CompletionException finalNodeFailure = containerCallExceptionIsReusedFromOriginal
                    ? shallowCopyContainerFailure(containerNodeFailure)
                    : containerNodeFailure;

            // Actually add the suppressed exceptions
            CallException finalCallException = (CallException) finalNodeFailure.getCause();
            Stream<CompletionException> failuresToSuppress = dependencyFailures.stream();
            if (containerNodeFailure != finalNodeFailure) {
                // If we made a shallow copy, also add the initial containerNodeFailure as suppressed, so that we
                // don't lose its information
                failuresToSuppress = Stream.concat(failuresToSuppress, Stream.of(containerNodeFailure));
            }
            failuresToSuppress.distinct().forEach(finalCallException::addSuppressed);
            return finalNodeFailure;
        }

        private CompletionException shallowCopyContainerFailure(CompletionException nodeFailure) {
            CallException callException = (CallException) nodeFailure.getCause();
            CallException copiedCallException = new CallException(callException.getCaller(),
                    callException.getFailedNode(), callException.getCause());
            return newCompletionException(copiedCallException, callException.getFailedNode());
        }
    },
    /**
     * Discards any dependency failures and simply returns the based constructed container exception given the
     * originalNodeFailure.
     */
    DISCARD_DEPENDENCY_FAILURES {
        @Override
        protected CompletionException handleDependencyFailures(Throwable originalNodeFailure,
                CompletionException containerNodeFailure,
                Supplier<Collection<CompletionException>> dependencyFailuresSupplier) {
            return containerNodeFailure;
        }
    };

    /**
     * Constructs a container exception for the given nodeFailure. This creation is being done as part of failedNode's
     * call, as initiated by callerOfFailedNode. Thus, the returned container exception will represent the exceptional
     * response from that call.
     * 
     * Each ExceptionStrategy enum value will have this basic logic: if nodeFailure is a CallException or a full
     * container exception itself, this method will try to propagate that CallException/container exception information
     * in some form in the response. Otherwise, a new CallException/container exception will be created. How each enum
     * value propagates that information is documented in individual values. However, for every enum value, the response
     * will definitely contain a CompletionException caused by a CallException caused by a root exception, where the
     * root exception is either nodeFailure (if it's not a CallException/container exception) or nodeFailure's root
     * exception (if it is).
     * 
     * Each enum value also has the opportunity to add dependency failure information to the returned container
     * exception. See the javadoc for individual values to see what decisions they make.
     * 
     * @param dependencyFailuresSupplier a supplier of the all of failedNode's dependencies' container exceptions
     *        encountered so far during failedNode's call. To emphasize, "container exception" means CompletionException
     *        caused by a CallException caused by a root exception, so each of the dependency failures must match that
     *        structure. This is a supplier, since not every ExceptionStrategy enum will care about the dependency
     *        failures and there may be a cost to constructing that list. Each enum value guarantees that this supplier
     *        will be called at most once during this method call, so the value of the dependency failures need not be
     *        consistent across calls to {@link Supplier#get()}. This supplier should realistically be free from
     *        throwing exceptions, but considering this is critical path code (executed right before completing a Reply,
     *        which must happen to keep the framework alive), if it does due to a mistake in the framework, this
     *        exception is swallowed and added as a suppressed exception to the returned value from this method.
     */
    CompletionException constructContainerException(Caller callerOfFailedNode, Node<?, ?> failedNode,
            Throwable nodeFailure, Supplier<Collection<CompletionException>> dependencyFailuresSupplier) {
        CompletionException containerNodeFailure = transformToContainerException(nodeFailure, callerOfFailedNode,
                failedNode);
        CompletionException finalNodeFailure = safeHandleDependencyFailures(nodeFailure, containerNodeFailure,
                dependencyFailuresSupplier);
        CallException finalCallException = (CallException) finalNodeFailure.getCause();
        finalCallException.addNodeCallStackTraceElement(callerOfFailedNode, failedNode);
        return finalNodeFailure;
    }

    /**
     * Dependency failure logic *should* be fine, but given its relative low importance and how it's used in critical
     * path code (right before completing Replys), protect against exceptions with a fallback
     */
    private CompletionException safeHandleDependencyFailures(Throwable originalNodeFailure,
            CompletionException containerNodeFailure,
            Supplier<Collection<CompletionException>> dependencyFailuresSupplier) {
        try {
            return handleDependencyFailures(originalNodeFailure, containerNodeFailure, dependencyFailuresSupplier);
        } catch (Throwable t) {
            containerNodeFailure.addSuppressed(t);
            return containerNodeFailure;
        }
    }

    /**
     * An abstract method that allows each enum value to define how it handles dependency failures. The response from
     * this method will be a container exception, which could either be containerNodeFailure or a completely new
     * container exception, depending on how the enum value wants to handle that. Either way, the response from this
     * method is what {@link #constructContainerException(Caller, Node, Throwable, Supplier)} will ultimately return.
     * 
     * @param originalNodeFailure the nodeFailure passed to
     *        {@link #constructContainerException(Caller, Node, Throwable, Supplier)}.
     * @param containerNodeFailure the transformed value of nodeFailure after having been passed through
     *        {@link #transformToContainerException(Throwable, Caller, Node)}.
     * @param dependencyFailuresSupplier the dependencyFailuresSupplier passed to
     *        {@link #constructContainerException(Caller, Node, Throwable, Supplier)}. Enum values are only allowed to
     *        call {@link Supplier#get()} a maximum of one time during this method call.
     */
    protected abstract CompletionException handleDependencyFailures(Throwable originalNodeFailure,
            CompletionException containerNodeFailure,
            Supplier<Collection<CompletionException>> dependencyFailuresSupplier);

    /**
     * Transforms the given nodeFailure into a containerException. High-level idea: propagate as many container parts of
     * nodeFailure as possible. Specifically, if nodeFailure is already a container exception, it's returned as is. If
     * nodeFailure is a CallException, then it's wrapped in a CompletionException and returned. Otherwise, nodeFailure
     * if wrapped in a CallException and then in a CompletionException.
     */
    private static CompletionException transformToContainerException(Throwable nodeFailure, Caller callerOfFailedNode,
            Node<?, ?> failedNode) {
        if ((nodeFailure instanceof CompletionException) && (nodeFailure.getCause() instanceof CallException)) {
            return (CompletionException) nodeFailure;
        }

        CallException callException = (nodeFailure instanceof CallException) ? (CallException) nodeFailure
                : new CallException(callerOfFailedNode, failedNode, nodeFailure);
        return newCompletionException(callException, failedNode);
    }

    private static CompletionException newCompletionException(CallException cause, Node<?, ?> failedNode) {
        // Provide message to constructor so that CompletionException doesn't reuse callException's current message as
        // its own. That would be out-dated, redundant, and confusing. See CallException's javadoc for more.
        return new CompletionException("Node call failed: " + failedNode.getSynopsis(), cause);
    }
}
