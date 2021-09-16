/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;

/**
 * Creates nodes that conditionally choose/select other nodes and call them. This is useful both for pruning a graph,
 * where some nodes won't be executed in certain cases, and more generally selecting a path in the graph to execute.
 */
public class ConditionNodes {
    private ConditionNodes() {};

    /** TypeInstance for all nodes of type CONDITION_TYPE. */
    private static final TypeInstance CONDITION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class will have this as their type. */
    public static final Type CONDITION_TYPE = new Type("Condition") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == CONDITION_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of a conditional node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyConditionAndDependencies<M> startNode(Role role, Class<M> memoryClass) {
        return new SpecifyConditionAndDependencies<>(role, memoryClass);
    }

    /**
     * The second part of a two-part process to create conditional nodes. In this part, the condition and dependencies
     * are specified, along with other optional settings.
     */
    public static class SpecifyConditionAndDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyConditionAndDependencies(Role role, Class<M> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass).role(role);
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#dependencyLifetime(DependencyLifetime)}. */
        public SpecifyConditionAndDependencies<M> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#exceptionStrategy(ExceptionStrategy)}. */
        public SpecifyConditionAndDependencies<M> exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            builder.exceptionStrategy(exceptionStrategy);
            return this;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#graphValidatorFactory(ForNodeGraphValidatorFactory)}.
         */
        public SpecifyConditionAndDependencies<M> graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            builder.graphValidatorFactory(factory);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#clearGraphValidatorFactories()}. */
        public SpecifyConditionAndDependencies<M> clearGraphValidatorFactories() {
            builder.clearGraphValidatorFactories();
            return this;
        }

        /**
         * Creates a node that will conditionally call ifDependency if shouldCallDependency evaluates to true and will,
         * otherwise, return an empty optional.
         */
        public <T> Node<M, Optional<T>> ifThen(Node<M, Boolean> shouldCallDependency, Node<M, T> ifDependency) {
            builder.typeTypeInstance(CONDITION_TYPE, CONDITION_TYPE_INSTANCE);
            SameMemoryDependency<M, Boolean> consumeShould = builder.primedDependency(shouldCallDependency);
            SameMemoryDependency<M, T> consumeIf = builder.sameMemoryUnprimedDependency(ifDependency);
            Behavior<M, Optional<T>> behavior = device -> {
                boolean shouldExecute = device.call(consumeShould).join();
                if (shouldExecute) {
                    return device.call(consumeIf).thenApply(Optional::ofNullable);
                }
                return completableStageEmptyOptional();
            };
            return builder.build(behavior);
        }

        // Suppress justification: like Collections.emptyList(), this is a constant representation of nothing
        @SuppressWarnings("unchecked")
        private static <T> CompletionStage<Optional<T>> completableStageEmptyOptional() {
            return (CompletableFuture<Optional<T>>) COMPLETION_STAGE_EMPTY_OPTIONAL;
        }

        /**
         * I get the impression from looking at {@link CompletableFuture#completedStage(Object)} that its result can be
         * used as a constant. Looking at the source code gives more confirmation. I would *not* be comfortable doing
         * this with a CompletableFuture directly, since it's mutable. My only fear is a memory leak: that maybe
         * chaining things off of this variable will add compounding memory. In the worst case, I can replace this with
         * a true, non-growing constant CompletionStage (if this one is not).
         */
        // Suppress justification: like Collections.emptyList(), this is a constant representation of nothing
        @SuppressWarnings("rawtypes")
        private static final CompletionStage COMPLETION_STAGE_EMPTY_OPTIONAL = CompletableFuture
                .completedStage(Optional.empty());

        /**
         * Creates a node that will conditionally call ifDependency if shouldCallDependency evaluates to true and,
         * otherwise, will call elseDependency.
         */
        public <T> Node<M, T> ifThenElse(Node<M, Boolean> shouldExecuteOther, Node<M, ? extends T> ifDependency,
                Node<M, ? extends T> elseDependency) {
            builder.typeTypeInstance(CONDITION_TYPE, CONDITION_TYPE_INSTANCE);
            SameMemoryDependency<M, Boolean> consumeShould = builder.primedDependency(shouldExecuteOther);
            SameMemoryDependency<M, ? extends T> consumeIf = builder.sameMemoryUnprimedDependency(ifDependency);
            SameMemoryDependency<M, ? extends T> consumeElse = builder.sameMemoryUnprimedDependency(elseDependency);
            Behavior<M, T> behavior = device -> {
                boolean shouldExecute = device.call(consumeShould).join();
                Reply<? extends T> extendedResult = shouldExecute ? device.call(consumeIf) : device.call(consumeElse);
                return extendedResult.thenApply(f -> f);
            };
            return builder.build(behavior);
        }

        /**
         * Creates a node that will call selectDependencyToCall to figure out which, of all its possibleDependencies,
         * the node will call. selectDependencyToCall must return a non-null response, or expect the Node created from
         * this method to return a failed result caused by a NullPointerException.
         * 
         * @throws IllegalArgumentException if possibleDependencies is empty. This method must execute a dependency. If
         *         you may have no dependencies, then look into {@link #optionallySelect(Node, Collection)}, which does
         *         allow an empty list of dependencies to be passed.
         */
        public <T> Node<M, T> select(Node<M, ? extends Node<M, ? extends T>> selectDependencyToCall,
                Set<? extends Node<M, ? extends T>> possibleDependencies) {
            if (possibleDependencies.isEmpty()) {
                throw new IllegalArgumentException("Must specify at least one dependency to select from.");
            }
            builder.typeTypeInstance(CONDITION_TYPE, CONDITION_TYPE_INSTANCE);
            SameMemoryDependency<M, ? extends Node<M, ? extends T>> consumeSelect = builder
                    .primedDependency(selectDependencyToCall);
            possibleDependencies.stream().forEach(dependency -> {
                builder.sameMemoryUnprimedDependency(dependency);
            });
            Behavior<M, T> behavior = device -> {
                Node<M, ? extends T> dependencyToCall = device.call(consumeSelect).join();
                SameMemoryDependency<M, ? extends T> consumeDependency = Dependencies
                        .newSameMemoryDependency(dependencyToCall, PrimingMode.UNPRIMED);
                return device.call(consumeDependency).thenApply(f -> f);
            };
            return builder.build(behavior);
        }

        /**
         * Creates a node that will call selectDependencyToCall to figure out which, of all its possibleDependencies,
         * the node will call. selectDependencyToCall may also return an empty Optional, in which case the created node
         * will call nothing and will return an empty Optional itself. selectDependencyToCall must return a non-null
         * response, or expect the Node created from this method to return a failed result caused by a
         * NullPointerException.
         */
        public <T> Node<M, Optional<T>> optionallySelect(
                Node<M, ? extends Optional<? extends Node<M, ? extends T>>> selectDependencyToCall,
                Set<? extends Node<M, ? extends T>> possibleDependencies) {
            builder.typeTypeInstance(CONDITION_TYPE, CONDITION_TYPE_INSTANCE);
            SameMemoryDependency<M, ? extends Optional<? extends Node<M, ? extends T>>> consumeSelect = builder
                    .primedDependency(selectDependencyToCall);
            possibleDependencies.stream().forEach(dependency -> {
                builder.sameMemoryUnprimedDependency(dependency);
            });
            Behavior<M, Optional<T>> behavior = device -> {
                Optional<? extends Node<M, ? extends T>> dependencyToCall = device.call(consumeSelect).join();
                Optional<CompletionStage<Optional<T>>> result = dependencyToCall.map(dependency -> {
                    SameMemoryDependency<M, ? extends T> consumeDependency = Dependencies
                            .newSameMemoryDependency((Node<M, ? extends T>) dependency, PrimingMode.UNPRIMED);
                    return device.call(consumeDependency);
                }).map(future -> future.thenApply(f -> Optional.ofNullable(f)));
                return result.orElse(completableStageEmptyOptional());
            };
            return builder.build(behavior);
        }
    }
}
