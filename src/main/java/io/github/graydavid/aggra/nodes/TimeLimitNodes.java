/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.GraphCall;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.PrimingFailureStrategy;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;

/**
 * Creates nodes that will apply time limits when calling a potentially rogue dependency node. This protection includes
 * making sure the dependency doesn't take too long to<br>
 * * Return the response from its inherent behavior<br>
 * * Complete the response from its inherent behavior.
 * 
 * To further limit damage caused by rogue node, users can dedicate a new Memory for the time-limit node and its
 * dependency and pair it with a memory-creating {@link MemoryTripNodes}. E.g. let's say we create a DamageLimitMemory.
 * We create a memory-trip node that calls a time-limit node in that memory. The time-limit node calls the dependency.
 * Once the time-limit node times-out, it will complete, which will trigger the DamageLimitMemory's MemoryScope
 * cancellation signal (See {@link Node}'s "call" method for a description). That trigger will prevent the still-running
 * dependency from calling any new Nodes in the DamageLimitMemory or any new memory created from it: any such node calls
 * will be cancelled immediately, without their inherent behavior being run. This strategy is a way to set up "blast
 * doors" to limit the damage that the dependency node can do. Note: the dependency node can also can nodes in ancestor
 * memories who won't be affected by DamageLimitMemory's MemoryScope's cancel, so there are paths to "escape" the blast
 * doors. To prevent that, DamageLimitMemory could restrict access to those ancestor memories or pre-compute results
 * from nodes there and pass them as input to DamageLimitMemory (at the cost of potentially calling nodes that may not
 * be needed or blocking the dependency node until those inputs are available, which perhaps the dependency node could
 * have done more optimally if it were trustworthy).
 * 
 * @apiNote besides setting time limits, another possible protection would be to protect against exceptional responses.
 *          Although it would be nice to have that housed within the same node builder, the reason it's not here, is
 *          that it removes the ability to automatically metric responses using {@link Observer} when timeouts happen.
 *          Instead, take a look at {@link CaptureResponseNodes} for that protection.
 * @apiNote why expose timeouts through a common node type rather than on each node directly as a settable property? A
 *          settable property would allow any background tasks that are supposed to complete the behavior response
 *          normally to keep running while the orTimeout call completes them instead. Aggra would remain unaware that
 *          these background tasks are still running, since all it sees that the behavior response has completed, and so
 *          it thinks the node call is completely done. This set of affairs would encourage background tasks and their
 *          resource usage to build-up silently over time. With a common node type approach, while the time-limit node's
 *          reply will complete on the timeout, the dependency reply will not, and so Aggra remains aware of it and can
 *          report it to the user (by waiting for the dependency reply until completing the response from the
 *          GraphCall's weaklyClose method).
 */
public class TimeLimitNodes {
    private TimeLimitNodes() {}

    /** TypeInstance for all nodes of type TIME_LIMIT_TYPE. */
    private static final TypeInstance TIME_LIMIT_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class will have this as their type. */
    public static final Type TIME_LIMIT_TYPE = new Type("TimeLimit") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == TIME_LIMIT_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of a time-limit node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>, T> SpecifyCallingExecutor<M> startNode(Role role, Class<M> memoryClass) {
        return new SpecifyCallingExecutor<>(role, memoryClass);
    }

    /**
     * The second part of a four-part process to create asynchronous calling nodes. In this part, the executor to use
     * for calling the dependency node is specified.
     */
    public static class SpecifyCallingExecutor<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyCallingExecutor(Role role, Class<M> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass)
                    .typeTypeInstance(TIME_LIMIT_TYPE, TIME_LIMIT_TYPE_INSTANCE)
                    .role(role);
        }

        /**
         * Says to call the dependency on the caller thread directly. This means that you trust the dependency node not
         * to take too long to return their response, as the caller thread will wait for that forever.
         */
        public SpecifyTimeout<M> callerThreadExecutor() {
            return new SpecifyTimeout<>(builder, callerThreadDependencyCaller());
        }

        /**
         * Directly specifies the executor to use for calling the dependency. By calling the dependency on a thread as
         * determined by the specified executor, this provides protection in case the dependency takes too long to
         * return its response.
         */
        public SpecifyTimeout<M> executor(Executor callingExecutor) {
            return new SpecifyTimeout<>(builder, CreationTimeExecutorDependencyCaller.from(callingExecutor));
        }


        /**
         * Same as {@link #executor(Executor)}, but uses a Node to provide the caller instead of a pre-determined
         * executor.
         */
        public SpecifyTimeout<M> executorNode(Node<M, ? extends Executor> callingExecutorNode) {
            return new SpecifyTimeout<>(builder, CallTimeExecutorDependencyCaller.from(builder, callingExecutorNode));
        }
    }

    /**
     * A convenient way to save the executor (available at Node creation time) between calls to create new protection
     * nodes: Creating an instance of this class with an executor and then calling {@link #startNode(Role, Class)} is
     * the same as calling {@link TimeLimitNodes#startNode(Role, Class)} followed by
     * {@link SpecifyCallingExecutor#executor(Executor)} directly. This starter can be saved, though, and multiple calls
     * to startNode can be made to create multiple protection function nodes.
     */
    public static class CreationTimeExecutorStarter {
        private final Executor executor;

        private CreationTimeExecutorStarter(Executor executor) {
            this.executor = Objects.requireNonNull(executor);
        }

        public static CreationTimeExecutorStarter from(Executor executor) {
            return new CreationTimeExecutorStarter(executor);
        }

        public <M extends Memory<?>> SpecifyTimeout<M> startNode(Role role, Class<M> memoryClass) {
            return TimeLimitNodes.startNode(role, memoryClass).executor(executor);
        }
    }

    /**
     * Similar to {@link CreationTimeExecutorStarter}, except this class uses an Executor that's only available at Node
     * call time and produces results similar to {@link TimeLimitNodes#startNode(Role, Class)} followed by
     * {@link SpecifyCallingExecutor#executorNode(Node)}.
     */
    public static class CallTimeExecutorStarter<M extends Memory<?>> {
        private final Node<M, ? extends Executor> executorNode;
        private final Class<M> memoryClass;

        private CallTimeExecutorStarter(Node<M, ? extends Executor> executorNode, Class<M> memoryClass) {
            this.executorNode = Objects.requireNonNull(executorNode);
            this.memoryClass = Objects.requireNonNull(memoryClass);
        }

        public static <M extends Memory<?>> CallTimeExecutorStarter<M> from(Class<M> memoryClass,
                Node<M, ? extends Executor> executorNode) {
            return new CallTimeExecutorStarter<>(executorNode, memoryClass);
        }

        public SpecifyTimeout<M> startNode(Role role) {
            return TimeLimitNodes.startNode(role, memoryClass).executorNode(executorNode);
        }
    }

    /**
     * The third part of a four-part process to create protection nodes. In this part, the timeout is specified.
     */
    public static class SpecifyTimeout<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;
        private final DependencyCaller<M> dependencyCaller;

        private SpecifyTimeout(Node.CommunalBuilder<M> builder, DependencyCaller<M> dependencyCaller) {
            this.builder = builder;
            this.dependencyCaller = dependencyCaller;
        }

        /**
         * Says to wait for the dependency forever, without any timeout. This means that you trust the dependency to
         * complete its returned response in a timely manner.
         */
        public SpecifyDependencies<M> neverTimeout() {
            return new SpecifyDependencies<>(builder, dependencyCaller, device -> Timeout.infinite());
        }

        /**
         * Same as {@link #neverTimeout()}, but allows setting the {@link DependencyLifetime} at the same time. Allowing
         * a separate method to set the DependencyLifetime by itself would potentially introduce conflicts with
         * {@link #timeout(long, TimeUnit)}, for which the only DependencyLifetime that makes sense is
         * {@link DependencyLifetime#GRAPH}. That's why the timeout and DependencyLifetime need to be set at the same
         * time.
         */
        public SpecifyDependencies<M> neverTimeout(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
            return neverTimeout();
        }

        /**
         * Sets the timeout after which the current node should complete itself if the dependency node hasn't already
         * finished. Beware that this will also change the node's {@link DependencyLifetime} to
         * {@link DependencyLifetime#GRAPH}, since by setting a finite timeout, you're indicating that you want to
         * continue even after the dependency node times out.
         * 
         * @apiNote this method is inspired by {@link CompletableFuture#orTimeout(long, TimeUnit)}. There is
         *          intentionally no direct equivalent
         *          {@link CompletableFuture#completeOnTimeout(Object, long, TimeUnit)}-like method because it would
         *          give users no chance to detect/metric on timeouts.
         */
        public SpecifyDependencies<M> timeout(long timeout, TimeUnit unit) {
            builder.dependencyLifetime(DependencyLifetime.GRAPH);
            Timeout finiteTimeout = FiniteTimeout.of(timeout, unit);
            return new SpecifyDependencies<>(builder, dependencyCaller, device -> finiteTimeout);
        }

        /**
         * Same as {@link #timeout(Node)}, but sets the timeout through another node. Although the provided timeout can
         * be infinite or finite, this method will always set the node's {@link DependencyLifetime} to
         * {@link DependencyLifetime#GRAPH}, which means there's no opportunity to change it, as there is in
         * {@link #neverTimeout(DependencyLifetime)} (i.e. when the infinite timeout value is specified at node build
         * time).
         */
        public SpecifyDependencies<M> timeoutNode(Node<M, ? extends Timeout> timeoutNode) {
            SameMemoryDependency<M, ? extends Timeout> consumeTimeoutNode = builder.primedDependency(timeoutNode);
            builder.dependencyLifetime(DependencyLifetime.GRAPH);
            return new SpecifyDependencies<>(builder, dependencyCaller, device -> {
                return device.call(consumeTimeoutNode).join();// Okay to join, since is primed dependency
            });
        }
    }

    /**
     * The fourth part of a four-part process to create protection nodes. In this part, the dependencies are specified,
     * along with any optional settings.
     */
    public static class SpecifyDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;
        private final DependencyCaller<M> dependencyCaller;
        private final Function<DependencyCallingDevice<M>, Timeout> timeoutFunction;

        private SpecifyDependencies(Node.CommunalBuilder<M> builder, DependencyCaller<M> dependencyCaller,
                Function<DependencyCallingDevice<M>, Timeout> timeoutFunction) {
            this.builder = builder;
            this.dependencyCaller = dependencyCaller;
            this.timeoutFunction = Objects.requireNonNull(timeoutFunction);
        }

        /**
         * A builder-like method similar to {@link Node.CommunalBuilder#primingFailureStrategy(PrimingFailureStrategy)}.
         */
        public SpecifyDependencies<M> primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy) {
            builder.primingFailureStrategy(primingFailureStrategy);
            return this;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#graphValidatorFactory(ForNodeGraphValidatorFactory)}.
         */
        public SpecifyDependencies<M> graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            builder.graphValidatorFactory(factory);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#clearGraphValidatorFactories()}. */
        public SpecifyDependencies<M> clearGraphValidatorFactories() {
            builder.clearGraphValidatorFactories();
            return this;
        }

        /**
         * Creates a node which calls the given dependency with two possible features:<br>
         * 1. The dependency call is made on a thread determined by a provided executor. By making the call on another
         * thread, you protect against the dependency taking too long to return the response from its inherent
         * behavior.<br>
         * 2. A timeout is set against the response from the dependency's inherent behavior. If the timeout passes, then
         * the created node will complete with a TimeoutException. Note: if you do specify this feature, then the
         * created node will have {@link DependencyLifetime#GRAPH} set, since by using this feature, you're indicating
         * that you want to continue with other work after the dependency node takes too long to complete.
         * 
         * Note: other parts of the Aggra framework will still wait on the dependency node to finish before completing
         * themselves (e.g. the response from {@link GraphCall#weaklyClose()}. So, setting time limits here isn't the
         * only thing you should be doing. Ideally, you'd make the dependency reliable, but there's a whole write up on
         * handling unreliable nodes at https://github.com/graydavid/aggra-guide/blob/gh-pages/advanced/advanced.html
         */
        public <T> Node<M, T> timeLimitedCall(Node<M, T> dependency) {
            SameMemoryDependency<M, T> consumeDependency = builder.sameMemoryUnprimedDependency(dependency);
            return builder.build(device -> {
                CompletionStage<T> original = dependencyCaller.call(device, consumeDependency);
                Timeout timeout = timeoutFunction.apply(device);
                return timeout.applyTo(original.toCompletableFuture());
            });
        }
    }

    /** A helper interface for deciding how to call a dependency. */
    private interface DependencyCaller<M extends Memory<?>> {
        <T> CompletionStage<T> call(DependencyCallingDevice<M> device, SameMemoryDependency<M, T> dependency);

        default <T> CompletionStage<T> callWithExecutor(Executor executor, DependencyCallingDevice<M> device,
                SameMemoryDependency<M, T> dependency) {
            return device.call(dependency, executor);
        }
    }

    // Suppress justification: like Collections.emptyList(), this is a constant representation where the (in this case
    // Memory) type doesn't matter
    @SuppressWarnings("unchecked")
    private static <M extends Memory<?>> DependencyCaller<M> callerThreadDependencyCaller() {
        return (DependencyCaller<M>) CALLING_THREAD_DEPENDENCY_CALLER;
    }

    /** A singleton instance for calling a dependency on the caller's thread. */
    private static final DependencyCaller<Memory<?>> CALLING_THREAD_DEPENDENCY_CALLER = new DependencyCaller<Memory<?>>() {
        @Override
        public <T> CompletionStage<T> call(DependencyCallingDevice<Memory<?>> device,
                SameMemoryDependency<Memory<?>, T> dependency) {
            return device.call(dependency);
        }
    };

    /** A dependency caller that users an Executor that's available at creation time (of the Node). */
    private static class CreationTimeExecutorDependencyCaller<M extends Memory<?>> implements DependencyCaller<M> {
        private final Executor executor;

        private CreationTimeExecutorDependencyCaller(Executor executor) {
            this.executor = Objects.requireNonNull(executor);
        }

        public static <M extends Memory<?>> CreationTimeExecutorDependencyCaller<M> from(Executor executor) {
            return new CreationTimeExecutorDependencyCaller<>(executor);
        }

        @Override
        public <T> CompletionStage<T> call(DependencyCallingDevice<M> device, SameMemoryDependency<M, T> dependency) {
            return callWithExecutor(executor, device, dependency);
        }
    }

    /** A dependency caller that users an Executor that's available at call time (of the Node). */
    private static class CallTimeExecutorDependencyCaller<M extends Memory<?>> implements DependencyCaller<M> {
        private final SameMemoryDependency<M, ? extends Executor> consumeExecutor;

        private CallTimeExecutorDependencyCaller(SameMemoryDependency<M, ? extends Executor> consumeExecutor) {
            this.consumeExecutor = Objects.requireNonNull(consumeExecutor);
        }

        public static <M extends Memory<?>> CallTimeExecutorDependencyCaller<M> from(Node.CommunalBuilder<M> builder,
                Node<M, ? extends Executor> executorNode) {
            SameMemoryDependency<M, ? extends Executor> consumeExecutor = builder.primedDependency(executorNode);
            return new CallTimeExecutorDependencyCaller<>(consumeExecutor);
        }

        @Override
        public <T> CompletionStage<T> call(DependencyCallingDevice<M> device, SameMemoryDependency<M, T> dependency) {
            Executor executor = device.call(consumeExecutor).join(); // Okay to join, since is primed dependency
            return callWithExecutor(executor, device, dependency);
        }
    }

    /**
     * Represents a timeout after which a CompletableFuture, if not already complete, will complete with a
     * TimeoutException.
     */
    public abstract static class Timeout {
        private Timeout() {}

        /** Returns an infinite Timeout: the CompletableFuture will never be completed with a TimeoutException. */
        public static Timeout infinite() {
            return INFINITE_TIMEOUT;
        }

        /** Singleton infinite timeout value. */
        private static Timeout INFINITE_TIMEOUT = new Timeout() {
            @Override
            public boolean isInfinite() {
                return true;
            }

            @Override
            protected <T> CompletableFuture<T> applyTo(CompletableFuture<T> original) {
                return original;
            }

        };

        /** Does the timeout reference an infinite amount of time: will the timeout never expire? */
        public abstract boolean isInfinite();

        /** Is the timeout finite in extent (as opposed to infinite)? */
        public boolean isFinite() {
            return !isInfinite();
        }

        /**
         * Applies this timeout to the original future: the returned future, if not complete by the timeout represented
         * by this class, will complete with a TimeoutException. This method is allowed to modify the original future
         * and return it.
         */
        protected abstract <T> CompletableFuture<T> applyTo(CompletableFuture<T> original);
    }

    /**
     * Represents a finite timeout: the CompletableFuture will be completed with a TimeoutException if its not already
     * by the specified finite timeout.
     */
    public static class FiniteTimeout extends Timeout {
        private final long magnitude;
        private final TimeUnit unit;

        private FiniteTimeout(long magnitude, TimeUnit unit) {
            this.magnitude = magnitude;
            this.unit = Objects.requireNonNull(unit);
        }

        /** Creates a finite timeout. Zero and negative timeouts are valid and simply mean "timeout now". */
        public static FiniteTimeout of(long magnitude, TimeUnit unit) {
            return new FiniteTimeout(magnitude, unit);
        }

        @Override
        public boolean isInfinite() {
            return false;
        }

        @Override
        protected <T> CompletableFuture<T> applyTo(CompletableFuture<T> original) {
            return original.orTimeout(magnitude, unit);
        }
    }
}
