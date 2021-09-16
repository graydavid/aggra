/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
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
 * Adapts into node form functions that already return a CompletionStage (e.g. CompletableFuture). Adapting
 * FunctionNodes wait for their dependency nodes to complete and then execute the function against their results. This
 * class is similar to {@link FunctionNodes}, except that the functions already return CompletionStages and so don't
 * need that done for them.
 * 
 * @implNote The act of lingering on the same or jumping to a new thread could have been refactored out of this class to
 *           a new one: JumpingNode or something like that. The effect would have been the same. There would have been
 *           maybe a few more Objects in play, but that minor efficiency drop is not why I decided to keep this feature
 *           as part of this class. I did it, because I believe one of the major usecase of this class is to adapt
 *           frameworks for which the running thread should be protected (like service-calling clients). I believe users
 *           of this class should be confronted with this otherwise obscure decision and forced to choose. In addition,
 *           another major usecase is allowing for thread context propagation (i.e. propagation of thread local
 *           variables (e.g. metrics) to new threads). This can't always be done easily when the user doesn't have
 *           access to the framework thread/thread pools completing the produced CompletionStages. Forcing a thread jump
 *           to a better-controlled thread/thread pool before the Node completes allows users to take back this control.
 */
public class CompletionFunctionNodes {
    private CompletionFunctionNodes() {}

    /** TypeInstance for all nodes of type LINGERING_COMPLETION_FUNCTION_TYPE. */
    private static final TypeInstance LINGERING_COMPLETION_FUNCTION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that linger on the same thread will have this as their type. */
    public static final Type LINGERING_COMPLETION_FUNCTION_TYPE = new Type("LingeringCompletionFunction") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == LINGERING_COMPLETION_FUNCTION_TYPE_INSTANCE;
        }
    };

    /** TypeInstance for all nodes of type JUMPING_COMPLETION_FUNCTION_TYPE. */
    private static final TypeInstance JUMPING_COMPLETION_FUNCTION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that jump to another thread will have this as their type. */
    public static final Type JUMPING_COMPLETION_FUNCTION_TYPE = new Type("JumpingCompletionFunction") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == JUMPING_COMPLETION_FUNCTION_TYPE_INSTANCE;
        }
    };

    /**
     * First step in creating a node that will linger on whatever thread executed its function, taking no action to
     * change it. Compare this to {@link #threadJumping(String, Graph)}, which does take action to jump to a new thread.
     * 
     * Use this variant if the thread used to run the function is not in need of protection.
     * 
     * Note: remember to use {@link Reply#getFirstNonContainerExceptionNow(Throwable)} to access the first non container
     * exception for exceptional cases. Assuming a causal chain structure is especially brittle with this class, as
     * simply switching from a lingering to a jumping behavior, or vice-versa, can change the causal structure of the
     * root exception.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyFunctionAndDependencies<M> threadLingering(Role role,
            Class<M> memoryClass) {
        return new SpecifyJumpingExecutor<>(role, memoryClass).callerThreadExecutor();
    }

    /**
     * First step in creating a node that will jump to a "new" thread once the function is finished executing. The point
     * is to get to a "new" thread to execute consumers of this node rather than using the same one that executed the
     * function. "New" means either a thread determined by the jumpingExecutor passed into
     * {@link #CompletionFunctionNodes()} or the thread that adds consumers on this node, depending on whether this node
     * is not yet completed when a consumer adds the node as a dependency. "New" may actually mean "the same" if either
     * the jumpingExecutor executes on the same thread or if the function is itself synchronous and finishes before a
     * consumer is added to the Node.
     * 
     * This idea is useful for two major reasons:<br>
     * 1. If CompletableFuture-producing functions use a thread/thread pool that should be protected, like the async
     * variants of service-calling clients do, for example. The goal is to get off of those threads as soon as
     * possible.<br>
     * 2. If users want to allow for thread context propagation (i.e. propagating thread locals (e.g. metrics) from one
     * thread to another when a new thread is used). Users may not have access to the thread/thread pool that Completes
     * the CompletableFuture from the function, but they will have access to the thread/thread pools that this method
     * forces to be used.
     * 
     * Note: if the function throws an exception, then definitely no jumping will take place. A thrown exception means
     * the node will complete right away before any consumers can be attached to the Node's reply. So, this falls under
     * "the same" condition talked about above. This is different from the case where the function returns a failed
     * CompletableFuture response rather than throwing an exception. In that case, jumping still applies.
     * 
     * Note: remember to use {@link Reply#getFirstNonContainerExceptionNow(Throwable)} to access the first non container
     * exception for exceptional cases. Assuming a causal chain structure is especially brittle with this class, as
     * simply switching from a lingering to a jumping behavior, or vice-versa, can change the causal structure of the
     * root exception.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyJumpingExecutor<M> threadJumping(Role role, Class<M> memoryClass) {
        return new SpecifyJumpingExecutor<>(role, memoryClass);
    }

    /**
     * The second part of a three-part process to create jumping completion function nodes. In this part, the jumping
     * executor is specified.
     */
    public static class SpecifyJumpingExecutor<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyJumpingExecutor(Role role, Class<M> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass).role(role);
        }

        /**
         * Says to use the caller thread as the "jumping" executor (actually a lingering executor in this case).
         * 
         * @apiNote exists solely for collecting common implementation logic, since
         *          {@link CompletionFunctionNodes#threadLingering(Role, Class)} is the way this functionality is
         *          exposed to clients.
         */
        private SpecifyFunctionAndDependencies<M> callerThreadExecutor() {
            return new SpecifyFunctionAndDependencies<>(builder, lingeringThreadBindingModifier());
        }

        /** Directly specifies the jumping executor to use. */
        public SpecifyFunctionAndDependencies<M> executor(Executor jumpingExecutor) {
            return new SpecifyFunctionAndDependencies<>(builder,
                    new CreationTimeExecutorJumpingThreadBindingModifier<>(jumpingExecutor));
        }

        /** Specifies a node that provides the jumping executor to use. */
        public SpecifyFunctionAndDependencies<M> executorNode(Node<M, ? extends Executor> jumpingExecutorNode) {
            return new SpecifyFunctionAndDependencies<>(builder,
                    CallTimeExecutorJumpingThreadBindingModifier.from(builder, jumpingExecutorNode));
        }
    }

    /**
     * A convenient way to save the executor (available at Node creation time) between calls to create new jumping
     * completion function nodes: Creating an instance of this class with an executor and then calling
     * {@link #startNode(Role, Class)} is the same as calling
     * {@link CompletionFunctionNodes#threadJumping(Role, Class, Executor)} directly. This starter can be saved, though,
     * and multiple calls to startNode can be made to create multiple thread-jumping, completion function nodes.
     */
    public static class CreationTimeThreadJumpingStarter {
        private final Executor jumpingExecutor;

        private CreationTimeThreadJumpingStarter(Executor jumpingExecutor) {
            this.jumpingExecutor = Objects.requireNonNull(jumpingExecutor);
        }

        public static CreationTimeThreadJumpingStarter from(Executor jumpingExecutor) {
            return new CreationTimeThreadJumpingStarter(jumpingExecutor);
        }

        public <M extends Memory<?>> SpecifyFunctionAndDependencies<M> startNode(Role role, Class<M> memoryClass) {
            return CompletionFunctionNodes.threadJumping(role, memoryClass).executor(jumpingExecutor);
        }
    }

    /**
     * Similar to {@link CreationTimeThreadJumpingStarter}, except this class uses an Executor that's only available at
     * Node call time and produces results similar to {@link CompletionFunctionNodes#threadJumping(Role, Class, Node)}.
     */
    public static class CallTimeThreadJumpingStarter<M extends Memory<?>> {
        private final Node<M, ? extends Executor> jumpingExecutorNode;
        private final Class<M> memoryClass;

        private CallTimeThreadJumpingStarter(Node<M, ? extends Executor> jumpingExecutorNode, Class<M> memoryClass) {
            this.jumpingExecutorNode = Objects.requireNonNull(jumpingExecutorNode);
            this.memoryClass = Objects.requireNonNull(memoryClass);
        }

        public static <M extends Memory<?>> CallTimeThreadJumpingStarter<M> from(Class<M> memoryClass,
                Node<M, ? extends Executor> jumpingExecutorNode) {
            return new CallTimeThreadJumpingStarter<>(jumpingExecutorNode, memoryClass);
        }

        public SpecifyFunctionAndDependencies<M> startNode(Role role) {
            return CompletionFunctionNodes.threadJumping(role, memoryClass).executorNode(jumpingExecutorNode);
        }
    }

    /**
     * The last part of a to create (lingering or jumping) completion function nodes that evaluate functions against
     * dependency nodes. In this part, the function and dependencies are specified, along with other optional settings.
     */
    public static class SpecifyFunctionAndDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;
        private final ThreadBindingModifier<M> threadBindingModifier;

        private SpecifyFunctionAndDependencies(Node.CommunalBuilder<M> builder,
                ThreadBindingModifier<M> threadBindingModifier) {
            this.builder = builder.typeTypeInstance(threadBindingModifier.getType(),
                    threadBindingModifier.getTypeInstance());
            this.threadBindingModifier = threadBindingModifier;
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

        /** Creates a node that will return with the given result, with no dependencies. */
        public <T> Node<M, T> getValue(CompletionStage<T> suppliedValue) {
            return build(device -> suppliedValue);
        }

        private <T> Node<M, T> build(Behavior<M, T> behavior) {
            Behavior<M, T> behaviorToUse = threadBindingModifier.modifyBehavior(behavior);
            return builder.build(behaviorToUse);
        }

        /** Creates a node that will evaluate the given function to produce a result, with no dependencies. */
        public <T> Node<M, T> get(Supplier<? extends CompletionStage<T>> supplier) {
            return build(device -> supplier.get());
        }

        /**
         * Creates a node that will evaluate the 1-ary function against the result from the dependency node to produce a
         * result.
         */
        public <A, R> Node<M, R> apply(Function<A, ? extends CompletionStage<R>> function,
                Node<M, ? extends A> dependency) {
            SameMemoryDependency<M, ? extends A> consume = builder.primedDependency(dependency);
            return build(device -> function.apply(device.call(consume).join()));
        }

        /**
         * Creates a node that will evaluate the 2-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, R> Node<M, R> apply(BiFunction<A, B, ? extends CompletionStage<R>> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2) {
            SameMemoryDependency<M, ? extends A> consume1 = builder.primedDependency(dependency1);
            SameMemoryDependency<M, ? extends B> consume2 = builder.primedDependency(dependency2);
            return build(device -> function.apply(device.call(consume1).join(), device.call(consume2).join()));
        }

        /**
         * Creates a node that will evaluate the 3-ary function against the results from the dependency nodes to produce
         * a result.
         */
        public <A, B, C, R> Node<M, R> apply(ThreeAryFunction<A, B, C, ? extends CompletionStage<R>> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3) {
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
        public <A, B, C, D, R> Node<M, R> apply(FourAryFunction<A, B, C, D, ? extends CompletionStage<R>> function,
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
        public <A, B, C, D, E, R> Node<M, R> apply(
                FiveAryFunction<A, B, C, D, E, ? extends CompletionStage<R>> function, Node<M, ? extends A> dependency1,
                Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3, Node<M, ? extends D> dependency4,
                Node<M, ? extends E> dependency5) {
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
        public <A, B, C, D, E, F, R> Node<M, R> apply(
                SixAryFunction<A, B, C, D, E, F, ? extends CompletionStage<R>> function,
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
        public <A, B, C, D, E, F, G, R> Node<M, R> apply(
                SevenAryFunction<A, B, C, D, E, F, G, ? extends CompletionStage<R>> function,
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
        public <A, B, C, D, E, F, G, H, R> Node<M, R> apply(
                EightAryFunction<A, B, C, D, E, F, G, H, ? extends CompletionStage<R>> function,
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
        public <A, B, C, D, E, F, G, H, I, R> Node<M, R> apply(
                NineAryFunction<A, B, C, D, E, F, G, H, I, ? extends CompletionStage<R>> function,
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
                TenAryFunction<A, B, C, D, E, F, G, H, I, J, ? extends CompletionStage<R>> function,
                Node<M, ? extends A> dependency1, Node<M, ? extends B> dependency2, Node<M, ? extends C> dependency3,
                Node<M, ? extends D> dependency4, Node<M, ? extends E> dependency5, Node<M, ? extends F> dependency6,
                Node<M, ? extends G> dependency7, Node<M, ? extends H> dependency8, Node<M, ? extends I> dependency9,
                Node<M, ? extends J> dependency10) {
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
        public <A, R> Node<M, R> apply(NAryFunction<A, ? extends CompletionStage<R>> function,
                List<Node<M, ? extends A>> dependencies) {
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
     * Describes whether the function should linger on the current thread when complete or be forced to jump. This
     * abstraction is useful both to describe the node's type prefix and potentially to modify the root created behavior
     * to get the desired jumping behavior.
     */
    private interface ThreadBindingModifier<M extends Memory<?>> {
        Type getType();

        TypeInstance getTypeInstance();

        <T> Behavior<M, T> modifyBehavior(Behavior<M, T> behavior);
    }


    // Suppress justification: like Collections.emptyList(), this is a constant representation where the (in this case
    // Memory) type doesn't matter
    @SuppressWarnings("unchecked")
    private static <M extends Memory<?>> LingeringThreadBindingModifier<M> lingeringThreadBindingModifier() {
        return (LingeringThreadBindingModifier<M>) LingeringThreadBindingModifier.INSTANCE;
    }

    private static class LingeringThreadBindingModifier<M extends Memory<?>> implements ThreadBindingModifier<M> {
        // Suppress justification: like Collections.emptyList(), this is a constant representation where the (in this
        // case Memory) type doesn't matter
        @SuppressWarnings("rawtypes")
        public static LingeringThreadBindingModifier INSTANCE = new LingeringThreadBindingModifier();

        @Override
        public Type getType() {
            return LINGERING_COMPLETION_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return LINGERING_COMPLETION_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> Behavior<M, T> modifyBehavior(Behavior<M, T> behavior) {
            return behavior;
        }
    }

    private static class CreationTimeExecutorJumpingThreadBindingModifier<M extends Memory<?>>
            implements ThreadBindingModifier<M> {
        private final Executor executor;

        CreationTimeExecutorJumpingThreadBindingModifier(Executor executor) {
            this.executor = executor;
        }

        @Override
        public Type getType() {
            return JUMPING_COMPLETION_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return JUMPING_COMPLETION_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> Behavior<M, T> modifyBehavior(Behavior<M, T> behavior) {
            return device -> {
                return behavior.run(device).whenCompleteAsync((output, throwable) -> {
                }, executor);
            };
        }
    }

    private static class CallTimeExecutorJumpingThreadBindingModifier<M extends Memory<?>>
            implements ThreadBindingModifier<M> {
        private final SameMemoryDependency<M, ? extends Executor> consumeExecutor;

        private CallTimeExecutorJumpingThreadBindingModifier(
                SameMemoryDependency<M, ? extends Executor> consumeExecutor) {
            this.consumeExecutor = Objects.requireNonNull(consumeExecutor);
        }

        public static <M extends Memory<?>> CallTimeExecutorJumpingThreadBindingModifier<M> from(
                Node.CommunalBuilder<M> builder, Node<M, ? extends Executor> asynchronousExecutorNode) {
            SameMemoryDependency<M, ? extends Executor> consumeExecutor = builder
                    .primedDependency(asynchronousExecutorNode);
            return new CallTimeExecutorJumpingThreadBindingModifier<>(consumeExecutor);
        }

        @Override
        public Type getType() {
            return JUMPING_COMPLETION_FUNCTION_TYPE;
        }

        @Override
        public TypeInstance getTypeInstance() {
            return JUMPING_COMPLETION_FUNCTION_TYPE_INSTANCE;
        }

        @Override
        public <T> Behavior<M, T> modifyBehavior(Behavior<M, T> behavior) {
            return device -> {
                Executor executor = device.call(consumeExecutor).join(); // Okay to join, since is primed dependency
                return behavior.run(device).whenCompleteAsync((output, throwable) -> {
                }, executor);
            };
        }
    }
}
