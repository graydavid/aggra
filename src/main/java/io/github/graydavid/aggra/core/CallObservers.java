/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.graydavid.naryfunctions.FourAryFunction;

/**
 * Houses definitions of different types of observers of Node/Graph calls and other tightly-related classes.
 */
public class CallObservers {
    private CallObservers() {}

    /**
     * Defines various ways that a call to a Node can be observed. This could be useful, for example, to gather timing
     * information for a Node's intrinsic behavior, to create a graph of all the Nodes executed during a specific call,
     * to create a graph of all the Memorys made during a specific call, etc.
     * 
     * All implementations should be thread safe, since each can be executed by multiple threads at once. The created
     * ObserverAfterStops need not be so, since they'll only be executed once in a single thread.
     * 
     * All implementations and the ObserverAfterStops they create should be quick executing, in order to avoid
     * interfering with the flow they're observing.
     * 
     * This interface is a facade to avoid passing along multiple observers in a given Node call. The default
     * implementations of each method do nothing.
     * 
     * @apiNote this interface doesn't follow the typical decorator pattern for observations (i.e. serially start
     *          observation, call decorated method, and then stop observation). First off, that would be impossible for
     *          {@link #observeBeforeFirstCall(Caller, Node, Memory)}, since something needs to be chained to the end of
     *          the Node's "main logic"'s execution chain. Second, I wanted to avoid giving clients too much
     *          control/responsibility over the "main logic" itself: that's going to be run either way. Lastly, although
     *          decoration would work for {@link #observeBeforeEveryCall(Caller, Node, Memory)}, a single pattern is
     *          also valuable.
     */
    public interface Observer {
        /**
         * Observes the start of the first, non-cached call to a Node. In other words, a Node can be called hundreds of
         * times in a given Memory, but all but one of those times will be cached. The goal of this method is to observe
         * the one uncached call.
         * 
         * @param caller the caller that called the Node
         * @param node the Node that was called
         * @param memory the Memory in which the Node was called
         * 
         * @return the ObserverAfterStop that will be called just before when the Node call/Reply is completed. When
         *         that happens, the arguments passed to the ObserverAfterStop represent the results of the Node call.
         *         The first parameter will be set to the response on success (which can be null), in which case the
         *         second parameter will be null. The second parameter will be set to the exception on failure, in which
         *         case the first parameter will be null.
         * 
         * @implSpec The default implementation does nothing and returns an ObserverAfterStop that does nothing.
         * 
         * @apiNote for the ultimate in flexibility, this method could be genericized: for T, it would accept a Node<?,
         *          T> and return an ObserverAfterStop<? super T>. That way, each Node with a different output type
         *          could theoretically return a differently-typed ObserverAfterStop. Benefits: the method signature
         *          becomes clearer what the ObserverAfterStop is tied to, and the method doesn't have to return a
         *          ObserverAfterStop capable of accepting types that it won't be receiving for a given Node type.
         *          Drawbacks: can't use a single ObserverBeforeStart class for both of this Observer's methods (since
         *          one would need ObserverAfterStop<? super T> and the other would need ObserverAfterStop<? super
         *          Reply<T>> for the same Node<?,T>), making the representation and building of a per-method Observer
         *          more cumbersome; plus, lambdas would be more difficult/impossible to use (since lambdas and generic
         *          methods don't mix). Although the benefits of genericizing are appealing, I just can't imagine a
         *          usecase where the way that you stop an observation is tied to the Node's return type. What's more, I
         *          don't even know how users could specify this difference in behavior by return type, since method
         *          overrides of this sort are illegal and since Node doesn't expose a way to get it's return type at
         *          runtime.
         */
        default ObserverAfterStop<Object> observeBeforeFirstCall(Caller caller, Node<?, ?> node, Memory<?> memory) {
            return ObserverAfterStop.doNothing();
        }

        /**
         * Observes the run of a Behavior. This is useful in addition to
         * {@link #observeBeforeFirstCall(Caller, Node, Memory)} for two reasons. First, a Node call has a few phases:
         * priming dependencies, running the Behavior, and then waiting for dependencies. By observing the Behavior
         * directly, we can get more information about that phase and infer how all the different phases contributed to
         * the overall call. Second, in case of some types of failures, the Node's Behavior isn't even run at all.
         * 
         * @param caller the caller that called the Node
         * @param node the Node that was called
         * @param memory the Memory in which the Node was called
         * 
         * @return the ObserverAfterStop that will be called when the Behavior run is completed. When that happens, the
         *         arguments passed to the ObserverAfterStop represent the results of the Behavior. The first parameter
         *         will be set to the response on success, in which case the second parameter will be null. The second
         *         parameter will be set to the exception on failure, in which case the first parameter will be null.
         * 
         * @implSpec The default implementation does nothing and returns an ObserverAfterStop that does nothing.
         * 
         * @apiNote see the note under {@link #observeBeforeFirstCall(Caller, Node, Memory)} for similar reasons as to
         *          why this method isn't genericized.
         */
        default ObserverAfterStop<Object> observeBeforeBehavior(Caller caller, Node<?, ?> node, Memory<?> memory) {
            return ObserverAfterStop.doNothing();
        }

        /**
         * Observes the run of a CustomCancelAction.
         * 
         * @param caller the caller that called the Node
         * @param node the Node that was called
         * @param memory the Memory in which the Node was called
         * 
         * @return the ObserverAfterStop that will be called when the CustomCancelAction is done. When that happens, the
         *         arguments passed to the ObserverAfterStop represent the results of the CustomCancelAction. The first
         *         parameter will always be null since CustomCancelAction has no return value itself. For the second
         *         parameter, it will be null when CustomCancelAction is successful and will be the exception thrown on
         *         failure.
         * 
         * @implSpec The default implementation does nothing and returns an ObserverAfterStop that does nothing.
         * 
         * @apiNote see the note under {@link #observeBeforeFirstCall(Caller, Node, Memory)} for similar reasons as to
         *          why this method isn't genericized.
         */
        default ObserverAfterStop<Object> observeBeforeCustomCancelAction(Caller caller, Node<?, ?> node,
                Memory<?> memory) {
            return ObserverAfterStop.doNothing();
        }

        /**
         * Observes every call to a Node, memoized or unmemoized alike.
         * 
         * @param caller the caller that called the Node
         * @param node the Node that was called
         * @param memory the Memory in which the Node was called
         * 
         * @return the ObserverAfterStop that will be called when the Node call is completed. When that happens, the
         *         arguments passed to the ObserverAfterStop represent the results of the Node call. The first parameter
         *         will be set to the response on success, in which case the second parameter will be null. The second
         *         parameter will be set to the exception on failure, in which case the first parameter will be null.
         *         Note: the NodeReply itself can be an exceptional response internally, but that's still falls under
         *         the successful observation case outlined above.
         * 
         * @implSpec The default implementation does nothing and returns an ObserverAfterStop that does nothing.
         * 
         * @apiNote see the note under {@link #observeBeforeFirstCall(Caller, Node, Memory)} for similar reasons as to
         *          why this method isn't genericized.
         */
        default ObserverAfterStop<? super Reply<?>> observeBeforeEveryCall(Caller caller, Node<?, ?> node,
                Memory<?> memory) {
            return ObserverAfterStop.doNothing();
        }

        /**
         * Decorates another Observer to make a fault-tolerant Observer: it guarantees that no exceptions are thrown as
         * a part of the observation process itself; and it guarantees that no observation method returns a null
         * ObserverAfterStop. Any exceptions in any method are swallowed and passed to observationFailureObserver. If
         * observationFailureObserver fails, those exceptions are swallowed completely. Similar behavior happens with
         * exceptions thrown by the ObserverAfterStop any of this Observer's methods creates.
         * 
         * Note: "Any exceptions in any method are swallowed" is within reason. E.g. it could be difficult to account
         * for terminal exceptions like OutOfMemoryError, StackOverflowError, etc. The point is that this method makes a
         * best effort to catch everything. If such a terminal exception is encountered, it implies that you're okay
         * with any client code utterly failing in these cases as well.
         * 
         * WARNING: this method appears in critical code in the Aggra framework. If it does not obey the rules outlined
         * above, node calls may stall and never complete.
         * 
         * @implSpec the default implementation componentizes each of this Observer's methods into ObserverBeforeStarts,
         *           calls {@link ObserverBeforeStart#faultTolerant(ObserverBeforeStart, ObservationFailureObserver)} on
         *           each, and then uses those in {@link CallObservers#builder()} to create a new Observer.
         * 
         * @apiNote the reason this method exists as a static factory method rather than on the interface is because
         *          it's used in framework-critical code. Even though it would be convenient to allow clients to define
         *          their own implementations of what it means to be fault-tolerant, the risk is too high. To get around
         *          some of the overhead of this method, there are in-built optimizations so that if the decorated
         *          observer is already known to be fault-tolerant, it's just returned as is.
         */
        static Observer faultTolerant(Observer decorated, ObservationFailureObserver observationFailureObserver) {
            Objects.requireNonNull(decorated);

            // Optimization: doing nothing already causes no faults
            if (decorated == DO_NOTHING) {
                return decorated;
            }

            // Optimization: IndividualMethodObserver has a slightly-more-efficient implementation of fault tolerance
            if (decorated instanceof IndividualMethodObserver) {
                return ((IndividualMethodObserver) decorated).toFaultTolerant(observationFailureObserver);
            }

            ObserverBeforeStart<Object> beforeFirstCall = (type, caller, node, memory) -> decorated
                    .observeBeforeFirstCall(caller, node, memory);
            beforeFirstCall = ObserverBeforeStart.faultTolerant(beforeFirstCall, observationFailureObserver);

            ObserverBeforeStart<Object> beforeBehavior = (type, caller, node, memory) -> decorated
                    .observeBeforeBehavior(caller, node, memory);
            beforeBehavior = ObserverBeforeStart.faultTolerant(beforeBehavior, observationFailureObserver);

            ObserverBeforeStart<Object> beforeCustomCancelAction = (type, caller, node, memory) -> decorated
                    .observeBeforeCustomCancelAction(caller, node, memory);
            beforeCustomCancelAction = ObserverBeforeStart.faultTolerant(beforeCustomCancelAction,
                    observationFailureObserver);

            ObserverBeforeStart<? super Reply<?>> beforeEveryCall = functionToObserverBeforeStart(
                    (type, caller, node, memory) -> {
                        return decorated.observeBeforeEveryCall(caller, node, memory);
                    });
            beforeEveryCall = ObserverBeforeStart.faultTolerant(beforeEveryCall, observationFailureObserver);

            return builder().observerBeforeFirstCall(beforeFirstCall)
                    .observerBeforeBehavior(beforeBehavior)
                    .observerBeforeCustomCancelAction(beforeCustomCancelAction)
                    .observerBeforeEveryCall(beforeEveryCall)
                    .build();
        }

        /** Returns an Observer that does nothing during observation either before or after. */
        static Observer doNothing() {
            return DO_NOTHING;
        }

        /**
         * Returns a builder that can create an Observer in parts: one {@link CallObservers.ObserverBeforeStart} per
         * method. The default ObserverBeforeStarts for each method do nothing. Client can then set behavior for only
         * the methods they care about.
         */
        static Builder builder() {
            return new Builder();
        }
    }

    /**
     * Converts a function to an ObserverBeforeStart. This is needed, because
     * {@link Observer#observeBeforeEveryCall(Caller, Node, Memory)} returns a Type with a bounded wildcard, which is
     * not compatible with creating subclasses. This generic method uses a binding "trick" to get around that.
     */
    private static <T> ObserverBeforeStart<T> functionToObserverBeforeStart(
            FourAryFunction<ObservationType, Caller, Node<?, ?>, Memory<?>, ObserverAfterStop<T>> function) {
        return (type, caller, node, memory) -> function.apply(type, caller, node, memory);
    }

    /** An observer called just after the stop of a node call. */
    @FunctionalInterface
    public interface ObserverAfterStop<T> {
        /**
         * Observes the stop just after the Node call.
         * 
         * @param result the result of the observation. Will be set on success only (and may be null in this case as
         *        well); otherwise, it will be null on failure.
         * @param throwable the exception thrown during the observation on failure; otherwise, it will be null on
         *        successful observation.
         */
        void observe(T result, Throwable throwable);

        /** Returns an ObserverAfterStop<T> that does nothing. */
        static <T> ObserverAfterStop<T> doNothing() {
            return (result, throwable) -> {
            };
        }
    }

    private static final Observer DO_NOTHING = new Observer() {};

    /**
     * Describes the types of observations that can be performed/the individual methods on {@link Observer}. This helps
     * maintain useful debug information in spite of breaking an Observer down into its constituent parts/methods.
     * 
     * Note: to facilitate evolution of this enum, users should not rely on the absolute value of each enum's ordinal
     * value but can be assured that the relative ordering will be maintained.
     */
    public enum ObservationType {
        FIRST_CALL,
        BEHAVIOR,
        CUSTOM_CANCEL_ACTION,
        EVERY_CALL
    };

    /**
     * A functional interface describing each of the individual methods on {@link Observer}. Allows us to build an
     * Observer from parts.
     */
    @FunctionalInterface
    public interface ObserverBeforeStart<T> {

        /**
         * Starts an observation process for a Node call.
         *
         * @param caller the caller that called the Node
         * @param node the Node that was called
         * @param memory the Memory in which the Node was called
         * 
         * @return the ObserverAfterStop that will be called when the Node call is completed. When that happens, the
         *         arguments passed to the ObserverAfterStop are specific to each method on Observer, so see that
         *         javadoc for more. The general idea: the first parameter will be the value on successful observation
         *         and the second parameter will be the value when an uncaught exception happens during that
         *         observation.
         */
        ObserverAfterStop<T> observe(ObservationType type, Caller caller, Node<?, ?> node, Memory<?> memory);

        /** Returns a ObserverBeforeStart<T> that does nothing. */
        static <T> ObserverBeforeStart<T> doNothing() {
            return (type, caller, node, memory) -> ObserverAfterStop.doNothing();
        }

        /**
         * Creates a ObserverBeforeStart that decorates another observer to make it tolerant to fault: it guarantees
         * that no exceptions are thrown as a part of the observation process itself. Any exceptions in the decorated
         * observer are swallowed and passed to observationFailureObserver. If observationFailureObserver fails, those
         * exceptions are swallowed completely. Similar behavior happens with exceptions thrown by the ObserverAfterStop
         * the decorated observer creates.
         * 
         * @param decorated the observer to decorate.
         * @param observationFailureObserver the observer that consumes any failures in the decorated observer or the
         *        observerAfterStops it creates.
         */
        static <T> ObserverBeforeStart<T> faultTolerant(ObserverBeforeStart<T> decorated,
                ObservationFailureObserver observationFailureObserver) {
            // Optimization: if decorated is already fault tolerant, return it as is: no use in decorating further
            if (decorated instanceof FaultTolerantObserverBeforeStart) {
                return decorated;
            }

            return new FaultTolerantObserverBeforeStart<>(decorated, observationFailureObserver);
        }

        /**
         * Creates a composite ObserverBeforeStart that decorates a list of component observers, calling each in turn.
         * The decorated observers are called in the same order they were passed; the stopObservers they create are
         * called in the reverse order.
         */
        static <T> ObserverBeforeStart<T> composite(List<ObserverBeforeStart<? super T>> components) {
            return new CompositeObserverBeforeStart<>(components);
        }

        /**
         * A helper method to create a composite list of faultTolerant observers.
         * 
         * @param observationFailureObserver the observationFailureObserver to use for each faultTolerant observer
         *        created for each component observer.
         * 
         * @see {@link #compositeObserverBeforeStart(List)}
         * @see {@link #faultTolerantObserverBeforeStart(ObserverBeforeStart, ObservationFailureObserver)
         */
        static <T> ObserverBeforeStart<T> compositeOfFaultTolerant(List<ObserverBeforeStart<? super T>> components,
                ObservationFailureObserver observationFailureObserver) {
            Objects.requireNonNull(observationFailureObserver);
            List<ObserverBeforeStart<? super T>> faultTolerantComponent = components.stream()
                    .map(component -> faultTolerant(component, observationFailureObserver))
                    .collect(Collectors.toList());
            return composite(faultTolerantComponent);
        }
    }

    /** A builder of Observers with ObserverBeforeStarts implementing each method. */
    public static class Builder {
        private ObserverBeforeStart<Object> observerBeforeFirstCall = ObserverBeforeStart.doNothing();
        private ObserverBeforeStart<? super Reply<?>> observerBeforeEveryCall = ObserverBeforeStart.doNothing();
        private ObserverBeforeStart<Object> observerBeforeBehavior = ObserverBeforeStart.doNothing();
        private ObserverBeforeStart<Object> observerBeforeCustomCancelAction = ObserverBeforeStart.doNothing();

        private Builder() {}

        /**
         * Sets the BeforeStartOberver to use for {@link Observer#observeBeforeFirstCall(Caller, Node, Memory)}
         */
        public Builder observerBeforeFirstCall(ObserverBeforeStart<Object> observerBeforeFirstCall) {
            this.observerBeforeFirstCall = observerBeforeFirstCall;
            return this;
        }

        /**
         * Sets the BeforeStartOberver to use for {@link Observer#observeBeforeBehavior(Caller, Node, Memory)}
         */
        public Builder observerBeforeBehavior(ObserverBeforeStart<Object> observerBeforeBehavior) {
            this.observerBeforeBehavior = observerBeforeBehavior;
            return this;
        }

        /**
         * Sets the BeforeStartOberver to use for {@link Observer#observeBeforeCustomCancelAction(Caller, Node, Memory)}
         */
        public Builder observerBeforeCustomCancelAction(ObserverBeforeStart<Object> observerBeforeCustomCancelAction) {
            this.observerBeforeCustomCancelAction = observerBeforeCustomCancelAction;
            return this;
        }

        /**
         * Sets the BeforeStartOberver to use for {@link Observer#observeBeforeEveryCall(Caller, Node, Memory)}
         */
        public Builder observerBeforeEveryCall(ObserverBeforeStart<? super Reply<?>> observerBeforeEveryCall) {
            this.observerBeforeEveryCall = observerBeforeEveryCall;
            return this;
        }

        public Observer build() {
            return new IndividualMethodObserver(observerBeforeFirstCall, observerBeforeEveryCall,
                    observerBeforeBehavior, observerBeforeCustomCancelAction);
        }
    }

    /** An Observer with ObserverBeforeStarts implementing each method. */
    private static class IndividualMethodObserver implements Observer {
        private final ObserverBeforeStart<Object> observerBeforeFirstCall;
        private final ObserverBeforeStart<? super Reply<?>> observerBeforeEveryCall;
        private final ObserverBeforeStart<Object> observerBeforeBehavior;
        private final ObserverBeforeStart<Object> observerBeforeCustomCancelAction;

        IndividualMethodObserver(ObserverBeforeStart<Object> observerBeforeFirstCall,
                ObserverBeforeStart<? super Reply<?>> observerBeforeEveryCall,
                ObserverBeforeStart<Object> observerBeforeBehavior,
                ObserverBeforeStart<Object> observerBeforeCustomCancelAction) {
            this.observerBeforeFirstCall = observerBeforeFirstCall;
            this.observerBeforeEveryCall = observerBeforeEveryCall;
            this.observerBeforeBehavior = observerBeforeBehavior;
            this.observerBeforeCustomCancelAction = observerBeforeCustomCancelAction;
        }

        @Override
        public ObserverAfterStop<Object> observeBeforeFirstCall(Caller caller, Node<?, ?> node, Memory<?> memory) {
            return observerBeforeFirstCall.observe(ObservationType.FIRST_CALL, caller, node, memory);
        }

        @Override
        public ObserverAfterStop<Object> observeBeforeBehavior(Caller caller, Node<?, ?> node, Memory<?> memory) {
            return observerBeforeBehavior.observe(ObservationType.BEHAVIOR, caller, node, memory);
        }

        @Override
        public ObserverAfterStop<Object> observeBeforeCustomCancelAction(Caller caller, Node<?, ?> node,
                Memory<?> memory) {
            return observerBeforeCustomCancelAction.observe(ObservationType.CUSTOM_CANCEL_ACTION, caller, node, memory);
        }

        @Override
        public ObserverAfterStop<? super Reply<?>> observeBeforeEveryCall(Caller caller, Node<?, ?> node,
                Memory<?> memory) {
            return observerBeforeEveryCall.observe(ObservationType.EVERY_CALL, caller, node, memory);
        }

        /**
         * A slightly more efficient version of the default strategy in
         * {@link Observer#faultTolerant(Observer, ObservationFailureObserver)}.
         */
        public Observer toFaultTolerant(ObservationFailureObserver observationFailureObserver) {
            return Observer.builder()
                    .observerBeforeFirstCall(
                            ObserverBeforeStart.faultTolerant(observerBeforeFirstCall, observationFailureObserver))
                    .observerBeforeBehavior(
                            ObserverBeforeStart.faultTolerant(observerBeforeBehavior, observationFailureObserver))
                    .observerBeforeCustomCancelAction(ObserverBeforeStart
                            .faultTolerant(observerBeforeCustomCancelAction, observationFailureObserver))
                    .observerBeforeEveryCall(
                            ObserverBeforeStart.faultTolerant(observerBeforeEveryCall, observationFailureObserver))
                    .build();
        }
    }

    /**
     * An observer of failures in other observers. This is a useful concept for reporting but otherwise suppressing
     * errors in observations.
     * 
     * @implSpec by default, each method calls {@link #defaultObserve(ObservationException)}, which does nothing. This
     *           has two benefits:<br>
     *           1. Users who don't care about the specific ObservationException implementation can override only a
     *           single method.<br>
     *           2. If implementations want to be informed of a new type of ObservationException added in the future,
     *           they can override defaultObserve to throw an UnsupportedOperationException. The default implementation
     *           doesn't do this, because it can't be sure that throwing an exception to discover new
     *           ObservationException types is the appropriate thing to do for every usecase.
     */
    public interface ObservationFailureObserver {
        /**
         * Observes a failure in the "before start" phase of call observation.
         * 
         * @param exception the failure thrown during the observation.
         */
        default void observeBeforeStart(BeforeStartObservationException exception) {
            defaultObserve(exception);
        }

        /**
         * Observes a failure in the "after stop" phase of call observation.
         * 
         * @param exception the failure thrown during the observation.
         */
        default void observeAfterStop(AfterStopObservationException exception) {
            defaultObserve(exception);
        }

        /**
         * A convenient observation method that can handle both "before start" and "after stop" observation exceptions.
         */
        default void defaultObserve(ObservationException exception) {}

        /**
         * Creates an ObservationFailureObserver that decorates another observer, swallowing any exceptions from it.
         * Clients should be wary of using this, as it's destroying useful information... but perhaps as a last resort,
         * that makes sense (e.g. this is how faultTolerantObserverBeforeStart treats its ObservationFailureObserver).
         * 
         * @param decorated the ObservationFailureObserver to decorate.
         */
        static ObservationFailureObserver faultSwallowing(ObservationFailureObserver decorated) {
            return new FaultSwallowingObservationFailureObserver(decorated);
        }
    }

    /** A wrapper that describes exceptions experienced during call observations. */
    public abstract static class ObservationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ObservationType type;
        private final Caller caller;
        private final Node<?, ?> node;
        private final Memory<?> memory;

        private ObservationException(String message, Throwable cause, ObservationType type, Caller caller,
                Node<?, ?> node, Memory<?> memory) {
            super(message, cause);
            this.type = Objects.requireNonNull(type);
            this.caller = Objects.requireNonNull(caller);
            this.node = Objects.requireNonNull(node);
            this.memory = Objects.requireNonNull(memory);
        }

        public ObservationType getObservationType() {
            return type;
        }

        /** @apiNote protected in case new types of ObservationExceptions are added in the future without this field. */
        protected Caller getCaller() {
            return caller;
        }

        /** @apiNote protected in case new types of ObservationExceptions are added in the future without this field. */
        protected Node<?, ?> getNode() {
            return node;
        }

        /** @apiNote protected in case new types of ObservationExceptions are added in the future without this field. */
        protected Memory<?> getMemory() {
            return memory;
        }
    }

    /**
     * A wrapper that describes exceptions experienced during "before start" phase of call observations. That means any
     * of the {@link Observer} methods or {@link ObserverBeforeStart#observe(Caller, Node, Memory)}.
     */
    public static final class BeforeStartObservationException extends ObservationException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates a new BeforeStartObservationException, where cause is the exception encountered during the
         * observation and the other parameters describe the observation itself.
         */
        public BeforeStartObservationException(Throwable cause, ObservationType type, Caller caller, Node<?, ?> node,
                Memory<?> memory) {
            super(calculateMessage(type, caller, node, memory), cause, type, caller, node, memory);
        }

        private static String calculateMessage(ObservationType type, Caller caller, Node<?, ?> node, Memory<?> memory) {
            return String.format("Failed to observe before start observing %s: <%s, %s, %s>", type, caller, node,
                    memory);
        }

        public Caller getCaller() {
            return super.getCaller();
        }

        public Node<?, ?> getNode() {
            return super.getNode();
        }

        public Memory<?> getMemory() {
            return super.getMemory();
        }
    }

    /**
     * A wrapper that describes exceptions experienced during "after stop" phase of call observations. That means
     * {@link ObserverAfterStop#observe(Object, Throwable)}.
     */
    public static final class AfterStopObservationException extends ObservationException {
        private static final long serialVersionUID = 1L;

        private final Object observedResult;
        private final Throwable observedThrowable;

        /**
         * Creates a new AfterStopObservationException, where cause is the exception encountered during the observation
         * and the other parameters describe the observation itself.
         */
        public AfterStopObservationException(Throwable cause, Caller caller, ObservationType type, Node<?, ?> node,
                Memory<?> memory, Object observedResult, Throwable observedThrowable) {
            super(calculateMessage(type, caller, node, memory, observedResult, observedThrowable), cause, type, caller,
                    node, memory);
            this.observedResult = observedResult;
            this.observedThrowable = observedThrowable;
        }

        private static String calculateMessage(ObservationType type, Caller caller, Node<?, ?> node, Memory<?> memory,
                Object result, Throwable throwable) {
            return String.format("Failed to observe after stop observing %s: <%s, %s, %s, %s, %s>", type, caller, node,
                    memory, result, throwable);
        }

        public Caller getCaller() {
            return super.getCaller();
        }

        public Node<?, ?> getNode() {
            return super.getNode();
        }

        public Memory<?> getMemory() {
            return super.getMemory();
        }

        public Object getObservedResult() {
            return observedResult;
        }

        public Throwable getObservedThrowable() {
            return observedThrowable;
        }
    }

    /** An observer that doesn't allow observation to interfere with any node-memory interaction */
    private static class FaultTolerantObserverBeforeStart<T> implements ObserverBeforeStart<T> {
        private final ObserverBeforeStart<T> decorated;
        private final ObservationFailureObserver observationFailureObserver;

        FaultTolerantObserverBeforeStart(ObserverBeforeStart<T> decorated,
                ObservationFailureObserver observationFailureObserver) {
            this.decorated = Objects.requireNonNull(decorated);
            this.observationFailureObserver = ObservationFailureObserver.faultSwallowing(observationFailureObserver);
        }

        @Override
        public ObserverAfterStop<T> observe(ObservationType type, Caller caller, Node<?, ?> node, Memory<?> memory) {
            try {
                return faultTolerant(decorated.observe(type, caller, node, memory), type, caller, node, memory);
            } catch (Throwable t) {
                observationFailureObserver
                        .observeBeforeStart(new BeforeStartObservationException(t, type, caller, node, memory));
                return ObserverAfterStop.doNothing();
            }
        }

        private ObserverAfterStop<T> faultTolerant(ObserverAfterStop<T> stopObserver, ObservationType type,
                Caller caller, Node<?, ?> node, Memory<?> memory) {
            return (result, throwable) -> {
                try {
                    stopObserver.observe(result, throwable);
                } catch (Throwable t) {
                    observationFailureObserver.observeAfterStop(
                            new AfterStopObservationException(t, caller, type, node, memory, result, throwable));
                }
            };
        }
    }

    /** Decorates another ObservationFailureObserver to swallow any exceptions produced by it. */
    private static class FaultSwallowingObservationFailureObserver implements ObservationFailureObserver {
        private final ObservationFailureObserver decorated;

        FaultSwallowingObservationFailureObserver(ObservationFailureObserver decorated) {
            this.decorated = Objects.requireNonNull(decorated);
        }

        @Override
        public void observeBeforeStart(BeforeStartObservationException exception) {
            try {
                decorated.observeBeforeStart(exception);
            } catch (Throwable t) {
                // Doing nothing is intentional and the whole point of this class. The user has been warned in javadoc.
            }
        }

        @Override
        public void observeAfterStop(AfterStopObservationException exception) {
            try {
                decorated.observeAfterStop(exception);
            } catch (Throwable t) {
                // Doing nothing is intentional and the whole point of this class. The user has been warned in javadoc.
            }
        }

        @Override
        public void defaultObserve(ObservationException exception) {
            try {
                decorated.defaultObserve(exception);
            } catch (Throwable t) {
                // Doing nothing is intentional and the whole point of this class. The user has been warned in javadoc.
            }
        }
    }

    /** The composite design pattern as applied to ObserverBeforeStarts. */
    private static class CompositeObserverBeforeStart<T> implements ObserverBeforeStart<T> {
        private final List<ObserverBeforeStart<? super T>> components;

        CompositeObserverBeforeStart(List<ObserverBeforeStart<? super T>> components) {
            this.components = List.copyOf(components);
        }

        @Override
        public ObserverAfterStop<T> observe(ObservationType type, Caller caller, Node<?, ?> node, Memory<?> memory) {
            List<ObserverAfterStop<? super T>> componentStopObservers = components.stream()
                    .map(component -> component.observe(type, caller, node, memory))
                    .collect(Collectors.toList());
            Collections.reverse(componentStopObservers);
            return composite(componentStopObservers);
        }

        private ObserverAfterStop<T> composite(List<ObserverAfterStop<? super T>> componentStopObservers) {
            return (result, throwable) -> componentStopObservers
                    .forEach(observer -> observer.observe(result, throwable));
        }
    }
}

