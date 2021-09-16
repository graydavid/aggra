/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.PrimingFailureStrategy;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;
import io.github.graydavid.naryfunctions.EightAryFunction;
import io.github.graydavid.naryfunctions.FiveAryFunction;
import io.github.graydavid.naryfunctions.FourAryFunction;
import io.github.graydavid.naryfunctions.NAryFunction;
import io.github.graydavid.naryfunctions.NineAryFunction;
import io.github.graydavid.naryfunctions.SevenAryFunction;
import io.github.graydavid.naryfunctions.SixAryFunction;
import io.github.graydavid.naryfunctions.TenAryFunction;
import io.github.graydavid.naryfunctions.ThreeAryFunction;

/**
 * Creates nodes to execute a function against a set of dependency nodes. FunctionNodes wait for their dependency nodes
 * to complete and then execute the function against their results.
 */
public class FunctionNodes {
    /** @param asynchronousExecutor the executor to use to run asynchronous FunctionNodes. */
    private FunctionNodes() {}

    /** TypeInstance for all nodes of type SYNCHRONOUS_FUNCTION_TYPE. */
    private static final TypeInstance SYNCHRONOUS_FUNCTION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that run synchronously will have this as their type. */
    public static final Type SYNCHRONOUS_FUNCTION_TYPE = new Type("SynchronousFunction") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == SYNCHRONOUS_FUNCTION_TYPE_INSTANCE;
        }
    };

    /** TypeInstance for all nodes of type ASYNCHRONOUS_FUNCTION_TYPE. */
    private static final TypeInstance ASYNCHRONOUS_FUNCTION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that run asynchronously will have this as their type. */
    public static final Type ASYNCHRONOUS_FUNCTION_TYPE = new Type("AsynchronousFunction") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == ASYNCHRONOUS_FUNCTION_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of a synchronous function node. The creation is finished in SpecifyFunctionAndDependencies,
     * which allows a variety of different function signatures to be used.
     * 
     * "Synchronous" means that the function is executed in the already-running thread, not a new thread. The
     * "already-running thread" means the thread that happens to be running when this node is called for the first time.
     * That could be either the last-completing dependy's thread of execution or potentially the thread that creates
     * this node, if all the dependencies are already completed.
     * 
     * You would create a synchronous node if the function to be run is cheap and/or fast.
     * 
     * Note: remember to use {@link Reply#getFirstNonContainerExceptionNow(Throwable)} to access the first non container
     * exception for exceptional cases. Assuming a causal chain structure is especially brittle with this class, as
     * simply switching from a synchronous to an asynchronous behavior, or vice-versa, can change the causal structure
     * of the root exception.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyFunctionAndDependencies<M> synchronous(Role role, Class<M> memoryClass) {
        return new SpecifyAsynchronousExecutor<>(role, memoryClass).callerThreadExecutor();
    }

    /**
     * Starts the creation of an asynchronous function node. The creation is finished in SpecifyFunctionAndDependencies,
     * which allows a variety of different function signatures to be used.
     * 
     * "Asynchronous" means that the function is executed in a different thread, as determined by the
     * asynchronousExecutor specified in the next step.
     * 
     * You would choose an asynchronous node when the function to be run "takes a lot of time" (e.g. blocks on I/O, does
     * a lot of complex computation, etc.).
     * 
     * Note: remember to use {@link Reply#getFirstNonContainerExceptionNow(Throwable)} to access the first non container
     * exception for exceptional cases. Assuming a causal chain structure is especially brittle with this class, as
     * simply switching from a synchronous to an asynchronous behavior, or vice-versa, can change the causal structure
     * of the root exception.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyAsynchronousExecutor<M> asynchronous(Role role, Class<M> memoryClass) {
        return new SpecifyAsynchronousExecutor<>(role, memoryClass);
    }

    /**
     * The second part of a three-part process to create asynchronous function nodes. In this part, the asynchronous
     * executor is specified.
     */
    public static class SpecifyAsynchronousExecutor<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyAsynchronousExecutor(Role role, Class<M> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass).role(role);
        }

        /**
         * Says to use the caller thread as the "asynchronous" executor (actually a synchronous executor in this case).
         * 
         * @apiNote exists solely for collecting common implementation logic, since
         *          {@link FunctionNodes#synchronous(Role, Class)} is the way this functionality is exposed to
         *          clients`1.
         */
        private SpecifyFunctionAndDependencies<M> callerThreadExecutor() {
            return new SpecifyFunctionAndDependencies<>(builder, synchronousCompletableExecutor());
        }

        /** Directly specifies the asynchronous executor to use. */
        public SpecifyFunctionAndDependencies<M> executor(Executor asynchronousExecutor) {
            return new SpecifyFunctionAndDependencies<>(builder,
                    CreationTimeCompletableExecutor.from(asynchronousExecutor));
        }


        /** Specifies a node that provides the asynchronous executor to use. */
        public SpecifyFunctionAndDependencies<M> executorNode(Node<M, ? extends Executor> asynchronousExecutorNode) {
            return new SpecifyFunctionAndDependencies<>(builder,
                    CallTimeCompletableExecutor.from(builder, asynchronousExecutorNode));
        }
    }

    /**
     * A convenient way to save the executor (available at Node creation time) between calls to create new asynchronous
     * function nodes: Creating an instance of this class with an executor and then calling
     * {@link #startNode(Role, Class)} is the same as calling {@link FunctionNodes#asynchronous(Role, Class)} followed
     * by {@link SpecifyAsynchronousExecutor#executor(Executor)} directly. This starter can be saved, though, and
     * multiple calls to startNode can be made to create multiple asynchronous function nodes.
     */
    public static class CreationTimeExecutorAsynchronousStarter {
        private final Executor asynchronousExecutor;

        private CreationTimeExecutorAsynchronousStarter(Executor asynchronousExecutor) {
            this.asynchronousExecutor = Objects.requireNonNull(asynchronousExecutor);
        }

        public static CreationTimeExecutorAsynchronousStarter from(Executor asynchronousExecutor) {
            return new CreationTimeExecutorAsynchronousStarter(asynchronousExecutor);
        }

        public <M extends Memory<?>> SpecifyFunctionAndDependencies<M> startNode(Role role, Class<M> memoryClass) {
            return FunctionNodes.asynchronous(role, memoryClass).executor(asynchronousExecutor);
        }
    }

    /**
     * Similar to {@link CreationTimeExecutorAsynchronousStarter}, except this class uses an Executor that's only
     * available at Node call time and produces results similar to {@link FunctionNodes#asynchronous(Role, Class)}
     * followed by {@link SpecifyAsynchronousExecutor#executorNode(Node)}.
     */
    public static class CallTimeExecutorAsynchronousStarter<M extends Memory<?>> {
        private final Node<M, ? extends Executor> asynchronousExecutorNode;
        private final Class<M> memoryClass;

        private CallTimeExecutorAsynchronousStarter(Node<M, ? extends Executor> asynchronousExecutorNode,
                Class<M> memoryClass) {
            this.asynchronousExecutorNode = Objects.requireNonNull(asynchronousExecutorNode);
            this.memoryClass = Objects.requireNonNull(memoryClass);
        }

        public static <M extends Memory<?>> CallTimeExecutorAsynchronousStarter<M> from(Class<M> memoryClass,
                Node<M, ? extends Executor> asynchronousExecutorNode) {
            return new CallTimeExecutorAsynchronousStarter<>(asynchronousExecutorNode, memoryClass);
        }

        public SpecifyFunctionAndDependencies<M> startNode(Role role) {
            return FunctionNodes.asynchronous(role, memoryClass).executorNode(asynchronousExecutorNode);
        }
    }

    /**
     * The last part of a process to create (synchronous or asynchronous) function nodes that evaluate functions against
     * dependency nodes. In this part, the function and dependencies are specified, along with other optional settings.
     */
    public static class SpecifyFunctionAndDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;
        private final CompletableExecutor<M> completableExecutor;

        private SpecifyFunctionAndDependencies(Node.CommunalBuilder<M> builder,
                CompletableExecutor<M> completableExecutor) {
            this.builder = builder.typeTypeInstance(completableExecutor.getType(),
                    completableExecutor.getTypeInstance());
            this.completableExecutor = completableExecutor;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#primingFailureStrategy(PrimingFailureStrategy, DependencyLifetime)}.
         */
        public SpecifyFunctionAndDependencies<M> primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy,
                DependencyLifetime dependencyLifetime) {
            builder.primingFailureStrategy(primingFailureStrategy, dependencyLifetime);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#dependencyLifetime(DependencyLifetime)}. */
        public SpecifyFunctionAndDependencies<M> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#exceptionStrategy(ExceptionStrategy)}. */
        public SpecifyFunctionAndDependencies<M> exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            builder.exceptionStrategy(exceptionStrategy);
            return this;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#graphValidatorFactory(ForNodeGraphValidatorFactory)}.
         */
        public SpecifyFunctionAndDependencies<M> graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            builder.graphValidatorFactory(factory);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#clearGraphValidatorFactories()}. */
        public SpecifyFunctionAndDependencies<M> clearGraphValidatorFactories() {
            builder.clearGraphValidatorFactories();
            return this;
        }

        /** Creates a node that will evaluate the given function, with no dependencies. */
        public Node<M, Void> run(Runnable function) {
            Function<DependencyCallingDevice<M>, Void> runFunction = device -> {
                function.run();
                return null;
            };
            return build(runFunction);
        }

        private <T> Node<M, T> build(Function<DependencyCallingDevice<M>, T> runFunction) {
            Behavior<M, T> runFunctionOnExecutor = device -> {
                Supplier<T> supplyResult = () -> runFunction.apply(device);
                return completableExecutor.execute(device, supplyResult);
            };
            return builder.build(runFunctionOnExecutor);
        }

        /** Creates a node that will return with the given result, with no dependencies. */
        public <T> Node<M, T> getValue(T suppliedValue) {
            return build(device -> suppliedValue);
        }

        /** Creates a node that will evaluate the given function to produce a result, with no dependencies. */
        public <T> Node<M, T> get(Supplier<T> supplier) {
            return build(device -> supplier.get());
        }

        /**
         * Creates a node that will evaluate the 1-ary function against the result from the dependency node to produce a
         * result.
         */
        public <A, R> Node<M, R> apply(Function<A, R> function, Node<M, ? extends A> dependency) {
            SameMemoryDependency<M, ? extends A> consume = builder.primedDependency(dependency);
            return build(device -> function.apply(device.call(consume).join()));
        }

        /**
         * Creates a node that will evaluate the 2-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, R> Node<M, R> apply(BiFunction<A, B, R> function, Node<M, ? extends A> dependency1,
                Node<M, ? extends B> dependency2) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join()));
        }

        /**
         * Creates a node that will evaluate the 3-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, R> Node<M, R> apply(ThreeAryFunction<A, B, C, R> function, Node<M, ? extends A> dependency1,
                Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join()));
        }

        /**
         * Creates a node that will evaluate the 4-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, R> Node<M, R> apply(FourAryFunction<A, B, C, D, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join()));
        }

        /**
         * Creates a node that will evaluate the 5-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, E, R> Node<M, R> apply(FiveAryFunction<A, B, C, D, E, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join()));
        }

        /**
         * Creates a node that will evaluate the 6-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, E, F, R> Node<M, R> apply(SixAryFunction<A, B, C, D, E, F, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            SameMemoryDependency<M, ? extends F> consume6 = builder.primedDependency(dependency6);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join(),
                    device.call(consume6).join()));
        }

        /**
         * Creates a node that will evaluate the 7-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, E, F, G, R> Node<M, R> apply(SevenAryFunction<A, B, C, D, E, F, G, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6,
                Node<M, ? extends G> dependency7) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            SameMemoryDependency<M, ? extends F> consume6 = builder.primedDependency(dependency6);
            SameMemoryDependency<M, ? extends G> consume7 = builder.primedDependency(dependency7);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join(),
                    device.call(consume6).join(), device.call(consume7).join()));
        }

        /**
         * Creates a node that will evaluate the 8-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, E, F, G, H, R> Node<M, R> apply(EightAryFunction<A, B, C, D, E, F, G, H, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6,
                Node<M, ? extends G> dependency7, Node<M, ? extends H> dependency8) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            SameMemoryDependency<M, ? extends F> consume6 = builder.primedDependency(dependency6);
            SameMemoryDependency<M, ? extends G> consume7 = builder.primedDependency(dependency7);
            SameMemoryDependency<M, ? extends H> consume8 = builder.primedDependency(dependency8);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join(),
                    device.call(consume6).join(), device.call(consume7).join(), device.call(consume8).join()));
        }

        /**
         * Creates a node that will evaluate the 9-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, D, E, F, G, H, I, R> Node<M, R> apply(NineAryFunction<A, B, C, D, E, F, G, H, I, R> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6,
                Node<M, ? extends G> dependency7, Node<M, ? extends H> dependency8, Node<M, ? extends I> dependency9) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            SameMemoryDependency<M, ? extends F> consume6 = builder.primedDependency(dependency6);
            SameMemoryDependency<M, ? extends G> consume7 = builder.primedDependency(dependency7);
            SameMemoryDependency<M, ? extends H> consume8 = builder.primedDependency(dependency8);
            SameMemoryDependency<M, ? extends I> consume9 = builder.primedDependency(dependency9);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join(),
                    device.call(consume6).join(), device.call(consume7).join(), device.call(consume8).join(),
                    device.call(consume9).join()));
        }

        /**
         * Creates a node that will evaluate the 10-ary function against the results from the dependency nodes to
         * produce a result.
         */
        public <A, B, C, D, E, F, G, H, I, J, R> Node<M, R> apply(
                TenAryFunction<A, B, C, D, E, F, G, H, I, J, R> function, Node<M, ? extends A> dependency1,
                Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3, Node<M, ? extends D> dependency4,
                Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6, Node<M, ? extends G> dependency7,
                Node<M, ? extends H> dependency8, Node<M, ? extends I> dependency9, Node<M, ? extends J> dependency10) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            SameMemoryDependency<M, ? extends C> consume3 = builder.primedDependency(dependency3);
            SameMemoryDependency<M, ? extends D> consume4 = builder.primedDependency(dependency4);
            SameMemoryDependency<M, ? extends E> consume5 = builder.primedDependency(dependency5);
            SameMemoryDependency<M, ? extends F> consume6 = builder.primedDependency(dependency6);
            SameMemoryDependency<M, ? extends G> consume7 = builder.primedDependency(dependency7);
            SameMemoryDependency<M, ? extends H> consume8 = builder.primedDependency(dependency8);
            SameMemoryDependency<M, ? extends I> consume9 = builder.primedDependency(dependency9);
            SameMemoryDependency<M, ? extends J> consume10 = builder.primedDependency(dependency10);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join(),
                    device.call(consume3).join(), device.call(consume4).join(), device.call(consume5).join(),
                    device.call(consume6).join(), device.call(consume7).join(), device.call(consume8).join(),
                    device.call(consume9).join(), device.call(consume10).join()));
        }

        /**
         * Creates a node that will evaluate the n-ary function against the results from all dependency nodes to produce
         * a result.
         */
        public <A, R> Node<M, R> apply(NAryFunction<A, R> function, List<Node<M, ? extends A>> dependencies) {
            List<SameMemoryDependency<M, ? extends A>> consumes = dependencies.stream()
                    .map(builder::primedDependency)
                    .collect(Collectors.toUnmodifiableList());
            return build(device -> {
                List<A> results = consumes.stream()
                        .map(consume -> device.call(consume).join())
                        .collect(Collectors.toList());
                return function.apply(results);
            });
        }
    }

    /**
     * A helper interface for executing a supplier function and producing a CompletableFuture, rather than just an
     * ordinary Future.
     */
    private interface CompletableExecutor<M extends Memory<?>> {
        Type getType();

        TypeInstance getTypeInstance();

        <T> CompletableFuture<T> execute(DependencyCallingDevice<M> device, Supplier<T> supplier);
    }

    // Suppress justification: like Collections.emptyList(), this is a constant representation where the (in this case
    // Memory) type doesn't matter
    @SuppressWarnings("unchecked")
    private static <M extends Memory<?>> CompletableExecutor<M> synchronousCompletableExecutor() {
        return (CompletableExecutor<M>) SYNCHRONOUS_COMPLETABLE_EXECUTOR;
    }

    /** A singleton instance for synchronous CompletableExecutors. */
    private static final CompletableExecutor<Memory<?>> SYNCHRONOUS_COMPLETABLE_EXECUTOR = new CompletableExecutor<Memory<?>>() {
        @Override
        public Type getType() {
            return SYNCHRONOUS_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return SYNCHRONOUS_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> CompletableFuture<T> execute(DependencyCallingDevice<Memory<?>> device, Supplier<T> supplier) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable t) {
                // Note: it's justified to catch Throwable above, rather than RuntimeException; because that's what
                // CompletableFuture.supplyAsync does for the asynchronous version of this executor. There's no need to
                // apply side effects of this catching (e.g. setting the Thread interrupt flag), since nothing in the
                // try block can throw an exception that would warrant that (e.g. InterruptedException).
                return CompletableFuture.failedFuture(t);
            }
        }
    };

    /** Adapts a creation-time-available Executor into a CompletableExecutor. */
    private static class CreationTimeCompletableExecutor<M extends Memory<?>> implements CompletableExecutor<M> {
        private final Executor executor;

        private CreationTimeCompletableExecutor(Executor executor) {
            this.executor = Objects.requireNonNull(executor);
        }

        public static <M extends Memory<?>> CreationTimeCompletableExecutor<M> from(Executor executor) {
            return new CreationTimeCompletableExecutor<>(executor);
        }

        @Override
        public Type getType() {
            return ASYNCHRONOUS_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return ASYNCHRONOUS_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> CompletableFuture<T> execute(DependencyCallingDevice<M> device, Supplier<T> supplier) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }
    }

    /** Adapts a call-time-available Executor into a CompletableExecutor. */
    private static class CallTimeCompletableExecutor<M extends Memory<?>> implements CompletableExecutor<M> {
        private final SameMemoryDependency<M, ? extends Executor> consumeExecutor;

        private CallTimeCompletableExecutor(SameMemoryDependency<M, ? extends Executor> consumeExecutor) {
            this.consumeExecutor = Objects.requireNonNull(consumeExecutor);
        }

        public static <M extends Memory<?>> CallTimeCompletableExecutor<M> from(Node.CommunalBuilder<M> builder,
                Node<M, ? extends Executor> asynchronousExecutorNode) {
            SameMemoryDependency<M, ? extends Executor> consumeExecutor = builder
                    .primedDependency(asynchronousExecutorNode);
            return new CallTimeCompletableExecutor<>(consumeExecutor);
        }

        @Override
        public Type getType() {
            return ASYNCHRONOUS_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return ASYNCHRONOUS_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> CompletableFuture<T> execute(DependencyCallingDevice<M> device, Supplier<T> supplier) {
            Executor executor = device.call(consumeExecutor).join(); // Okay to join, since is primed dependency
            return CompletableFuture.supplyAsync(supplier, executor);
        }
    }
}
