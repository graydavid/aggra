/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;

/** Creates nodes that iteratively create a new Memory, call a node there, and then collect the results. */
public class IterationNodes {
    private IterationNodes() {}

    /** TypeInstance for all nodes of type ITERATION_TYPE. */
    private static final TypeInstance ITERATION_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class will have this as their type. */
    public static final Type ITERATION_TYPE = new Type("Iteration") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == ITERATION_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of an iteration node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     * 
     * @param <SM> the type of the source Node's memory (i.e. the Node you're creating).
     */
    public static <SM extends Memory<?>, T> SpecifyIteration<SM> startNode(Role role, Class<SM> memoryClass) {
        return new SpecifyIteration<>(role, memoryClass);
    }

    /**
     * The second part of a (two or) three-part process to create iteration nodes. In this part, the iteration is
     * specified, along with any optional settings.
     */
    public static class SpecifyIteration<SM extends Memory<?>> {
        private final Node.CommunalBuilder<SM> builder;

        private SpecifyIteration(Role role, Class<SM> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass).role(role);
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#dependencyLifetime(DependencyLifetime)}. */
        public SpecifyIteration<SM> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#exceptionStrategy(ExceptionStrategy)}. */
        public SpecifyIteration<SM> exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            builder.exceptionStrategy(exceptionStrategy);
            return this;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#graphValidatorFactory(ForNodeGraphValidatorFactory)}.
         */
        public SpecifyIteration<SM> graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            builder.graphValidatorFactory(factory);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#clearGraphValidatorFactories()}. */
        public SpecifyIteration<SM> clearGraphValidatorFactories() {
            builder.clearGraphValidatorFactories();
            return this;
        }

        /**
         * Works towards creating a node that will call for a list of inputs; iterate over the inputs; and then, for
         * each input, create a new Memory with the given input and call another node in the new Memory. This will all
         * be done efficiently, in that memoryFactory will only be run after calculateInputs is complete; at which
         * point, all of the nodes in the new Memory will be called; and then only after all of those calls are complete
         * will the created Node itself complete. In other words, the created Node doesn't add any blocking behavior
         * beyond what its dependency nodes do.
         * 
         * Exceptional behavior: if calculateInputs fails, then no new Memory will be created, and the overall NodeReply
         * will indicate the failure from calculateInputs. If any call to the memoryFactory fails, it's undefined
         * whether all of the necessary memoryFactory calls will be made; either way, the overall NodeReply will
         * indicate one of the memoryFactory failures, but it's not defined which one. If there's a failure accessing
         * any of the results from any of the calls to the Nodes in the new Memorys, then the overall NodeReply will
         * indicate one of those access failures, but it's not defined which one.
         * 
         * @param calculateInputs the node providing the set of inputs to iterate over.
         * @param memoryFactory the factory to use for creating a new Memory for each input. The created Memory will
         *        return the input from {@link Memory#getInput()}.
         * @param nodeInNew the node in the new Memory to call for each input.
         */
        public <NI, NM extends Memory<NI>, T> SpecifyCollection<SM, NI, NM, T> iterate(
                Node<SM, ? extends Iterable<? extends NI>> calculateInputs, MemoryFactory<SM, NI, NM> memoryFactory,
                Node<NM, T> nodeInNew) {
            Objects.requireNonNull(calculateInputs);
            Objects.requireNonNull(memoryFactory);

            return new SpecifyCollection<>(builder, calculateInputs, memoryFactory, nodeInNew);
        }


        /**
         * Creates a node that will call for a list of no-input memory factories; iterate over them; and then, for each
         * factory, create a new Memory and call another node in the new Memory. The same guidelines and general idea
         * apply as in {@link #iterate(Node, MemoryFactory, Node)}. This is really just a limited convenience method in
         * case it's more convenient to have a list of memory factories rather than a list of inputs. It's "limited"
         * since, without inputs, there's only one way provided to produce output: a list.
         */
        public <NM extends Memory<?>, T> Node<SM, List<T>> iterateNoInputToList(
                Node<SM, ? extends Iterable<? extends MemoryNoInputFactory<SM, NM>>> memoryFactories,
                Node<NM, T> nodeInNew) {

            builder.typeTypeInstance(ITERATION_TYPE, ITERATION_TYPE_INSTANCE);
            SameMemoryDependency<SM, ? extends Iterable<? extends MemoryNoInputFactory<SM, NM>>> consumeFactories = builder
                    .primedDependency(memoryFactories);
            NewMemoryDependency<NM, T> consumeNodeInNew = builder.newMemoryDependency(nodeInNew);
            Behavior<SM, List<T>> behavior = device -> {
                Iterable<? extends MemoryNoInputFactory<SM, NM>> memoryFactoryIterable = device.call(consumeFactories)
                        .join();
                List<Reply<T>> results = StreamSupport.stream(memoryFactoryIterable.spliterator(), false)
                        .map(factory -> device.createMemoryNoInputAndCall(factory, consumeNodeInNew))
                        .collect(Collectors.toList());
                CompletableFuture<?> waitForAllResults = Reply.allOfBacking(results);
                return waitForAllResults
                        .thenApply(ignore -> results.stream().map(Reply::join).collect(Collectors.toList()));
            };
            return builder.build(behavior);
        }
    }

    /**
     * The third part of a three-part process to create iteration nodes. In this part, the collection mechanism is
     * specified.
     * 
     * @apiNote by forcing the node to collect all subGraph results before completion, the longest-running output will
     *          determine how long the node takes to complete. This could delay further calculation that depends on only
     *          a single output. As such, you should take care to put all such calculations inside the subGraph itself.
     */
    public static class SpecifyCollection<SM extends Memory<?>, NI, NM extends Memory<NI>, T> {
        private final Node.CommunalBuilder<SM> builder;
        private final SameMemoryDependency<SM, ? extends Iterable<? extends NI>> consumeInputs;
        private final NewMemoryDependency<NM, T> consumeNodeInNew;
        private final MemoryFactory<SM, NI, NM> memoryFactory;

        private SpecifyCollection(Node.CommunalBuilder<SM> builder,
                Node<SM, ? extends Iterable<? extends NI>> calculateInputs, MemoryFactory<SM, NI, NM> memoryFactory,
                Node<NM, T> nodeInNew) {
            this.builder = builder;
            this.consumeInputs = builder.primedDependency(calculateInputs);
            this.consumeNodeInNew = builder.newMemoryDependency(nodeInNew);
            this.memoryFactory = memoryFactory;
        }

        /**
         * Creates a node that returns a (unmodifiable) list of the output. The order of the output is guaranteed to
         * match the order of iteration for the inputs.
         */
        public Node<SM, List<T>> collectToOutputList() {
            builder.typeTypeInstance(ITERATION_TYPE, ITERATION_TYPE_INSTANCE);
            return build((input, output) -> output);
        }

        private <V> Node<SM, V> build(BiFunction<? super List<NI>, ? super List<T>, V> transform) {
            Behavior<SM, V> behavior = device -> {
                Iterable<? extends NI> inputIterable = device.call(consumeInputs).join();
                List<NI> inputList = StreamSupport.stream(inputIterable.spliterator(), false)
                        .collect(Collectors.toUnmodifiableList());
                List<Reply<T>> results = inputList.stream()
                        .map(input -> device.createMemoryAndCall(memoryFactory,
                                CompletableFuture.completedFuture(input), consumeNodeInNew))
                        .collect(Collectors.toList());
                CompletableFuture<?> waitForAllResults = Reply.allOfBacking(results);
                Supplier<List<T>> collectAllCallResults = () -> results.stream()
                        .map(Reply::join)
                        .collect(Collectors.toUnmodifiableList());
                return waitForAllResults.thenApply(ignore -> transform.apply(inputList, collectAllCallResults.get()));
            };
            return builder.build(behavior);
        }

        /**
         * Creates a nodes that returns a (unmodifiable) Map of input to output. The map is guaranteed to contain an
         * entry for each input. Besides the exceptional responses mentioned in previous phases of this Node builder
         * process, the node will complete with an IllegalStateException if there are multiple identical inputs (note:
         * remember to use {@link Reply#getFirstNonContainerExceptionNow(Throwable)} to access this exception rather
         * than assuming a causal chain structure), since there's no mechanism to reconcile two identical inputs that
         * map to two different outputs.
         * 
         * This type of node is useful when the inputs are all unique and looking up results by input is important for
         * further calculation. E.g. suppose for a set of objects, you want to make a service call. The service call's
         * input is based on one of the object's properties. Some of the objects may have duplicate values for this
         * property. So, you calculate a list of unique properties, use those as input, and then create a node to make
         * the service call for each input. You can then take the resulting map and use it to figure out the value of
         * the service call for each one of the original objects.
         */
        public Node<SM, Map<NI, T>> collectToInputToOutputMap() {
            builder.typeTypeInstance(ITERATION_TYPE, ITERATION_TYPE_INSTANCE);
            return build((input, output) -> {
                Map<NI, T> inputToOutput = new HashMap<>(input.size());
                for (int i = 0; i < input.size(); ++i) {
                    T oldValue = inputToOutput.put(input.get(i), output.get(i));
                    if (oldValue != null) {
                        String message = String.format("Duplicate key %s: <%s, %s>", input.get(i), oldValue,
                                output.get(i));
                        throw new IllegalStateException(message);
                    }
                }
                return Map.copyOf(inputToOutput);
            });
        }

        /**
         * Creates a node that returns a custom collection/result as specified by the given transformation function.
         * 
         * @param transform a BiFunction that can accept the list of input and list of output to produce a custom
         *        collection/result. This class guaranteed that the provided list of inputs will be in the same order in
         *        which this node iterated over them, that the provided list of inputs and outputs will be of the same
         *        size, and that the output in index "i" in its list is associated with the input in index "i" in its
         *        list. Furthermore, the input and output will both be unmodifiable Lists.
         */
        public <V> Node<SM, V> collectToCustom(BiFunction<? super List<NI>, ? super List<T>, V> transform) {
            Objects.requireNonNull(transform);

            builder.typeTypeInstance(ITERATION_TYPE, ITERATION_TYPE_INSTANCE);
            return build(transform);
        }
    }
}
