/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.Objects;

import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.MemoryBridges.AncestorMemoryAccessor;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;

/**
 * Creates simple nodes that are able to do one thing: take a trip to other memories... whether that means creating a
 * new memory or accessing an existing one, calling a Node there, and then coming back. {@link DependencyCallingDevice}
 * provides options to do the same things as a part of a Node's intrinsic behavior, but this class provides basic Nodes
 * that make simple use of those options, like perhaps creating a new memory once or accessing an ancestor memory once
 * and integrating the results in the current memory.
 * 
 * Note: for Nodes that can create memories multiple times, see {@link IterationNodes}.
 */
public class MemoryTripNodes {
    private MemoryTripNodes() {}

    /** TypeInstance for all nodes of type CREATE_MEMORY_TRIP_TYPE. */
    private static final TypeInstance CREATE_MEMORY_TRIP_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that create new Memorys will have this as their type. */
    public static final Type CREATE_MEMORY_TRIP_TYPE = new Type("CreateMemoryTrip") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == CREATE_MEMORY_TRIP_TYPE_INSTANCE;
        }
    };

    /** TypeInstance for all nodes of type ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE. */
    private static final TypeInstance ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class that access ancestor Memorys will have this as their type. */
    public static final Type ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE = new Type("AncestorAccessorMemoryTrip") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of a memory trip node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <SM extends Memory<?>> SpecifyTripTypeAndDependencies<SM> startNode(Role role,
            Class<SM> memoryClass) {
        return new SpecifyTripTypeAndDependencies<>(role, memoryClass);
    }

    /**
     * The second part of a two-part process to create a memory trip nodes. In this part, the trip type and dependencies
     * are provided, along with other optional settings.
     * 
     * @param <SM> the type of the source Node's memory (i.e. the Node you're creating).
     */
    public static class SpecifyTripTypeAndDependencies<SM extends Memory<?>> {
        private final Node.CommunalBuilder<SM> builder;

        private SpecifyTripTypeAndDependencies(Role role, Class<SM> memoryClass) {
            this.builder = Node.communalBuilder(memoryClass).role(role);
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#dependencyLifetime(DependencyLifetime)}. */
        public SpecifyTripTypeAndDependencies<SM> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
            return this;
        }

        /**
         * A builder-like method similar to
         * {@link Node.CommunalBuilder#graphValidatorFactory(ForNodeGraphValidatorFactory)}.
         */
        public SpecifyTripTypeAndDependencies<SM> graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            builder.graphValidatorFactory(factory);
            return this;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#clearGraphValidatorFactories()}. */
        public SpecifyTripTypeAndDependencies<SM> clearGraphValidatorFactories() {
            builder.clearGraphValidatorFactories();
            return this;
        }

        /**
         * Creates a Node that will create a new Memory (that will return the provided input) using this Node's Memory
         * and then call a node within that new Memory.
         */
        public <NI, NM extends Memory<NI>, T> Node<SM, T> createMemoryAndCall(MemoryFactory<SM, NI, NM> memoryFactory,
                Node<SM, NI> newInputNode, Node<NM, T> nodeInNew) {
            Objects.requireNonNull(memoryFactory);
            Objects.requireNonNull(newInputNode);

            builder.typeTypeInstance(CREATE_MEMORY_TRIP_TYPE, CREATE_MEMORY_TRIP_TYPE_INSTANCE);
            SameMemoryDependency<SM, NI> consumeNewInputNode = builder.primedDependency(newInputNode);
            NewMemoryDependency<NM, T> consumeNodeInNew = builder.newMemoryDependency(nodeInNew);
            return builder.build(device -> {
                Reply<NI> newInput = device.call(consumeNewInputNode);
                return device.createMemoryAndCall(memoryFactory, newInput, consumeNodeInNew);
            });
        }

        /**
         * Creates a Node that will create a new Memory using this Node's Memory and then call a node within that new
         * Memory.
         */
        public <NM extends Memory<?>, T> Node<SM, T> createMemoryNoInputAndCall(
                MemoryNoInputFactory<SM, NM> memoryFactory, Node<NM, T> nodeInNew) {
            Objects.requireNonNull(memoryFactory);

            builder.typeTypeInstance(CREATE_MEMORY_TRIP_TYPE, CREATE_MEMORY_TRIP_TYPE_INSTANCE);
            NewMemoryDependency<NM, T> consumeNodeInNew = builder.newMemoryDependency(nodeInNew);
            return builder.build(device -> {
                return device.createMemoryNoInputAndCall(memoryFactory, consumeNodeInNew);
            });
        }

        /**
         * Same as {@link #createMemoryNoInputAndCall(MemoryNoInputFactory, Node)}, except the memory factory is
         * supplied by a node rather than as a constant.
         */
        public <NM extends Memory<?>, T> Node<SM, T> createMemoryNoInputAndCall(
                Node<SM, ? extends MemoryNoInputFactory<SM, NM>> memoryFactoryNode, Node<NM, T> nodeInNew) {
            Objects.requireNonNull(memoryFactoryNode);

            builder.typeTypeInstance(CREATE_MEMORY_TRIP_TYPE, CREATE_MEMORY_TRIP_TYPE_INSTANCE);
            SameMemoryDependency<SM, ? extends MemoryNoInputFactory<SM, NM>> consumeMemoryFactory = builder
                    .primedDependency(memoryFactoryNode);
            NewMemoryDependency<NM, T> consumeNodeInNew = builder.newMemoryDependency(nodeInNew);
            return builder.build(device -> {
                MemoryNoInputFactory<SM, NM> memoryFactory = device.call(consumeMemoryFactory).join();
                return device.createMemoryNoInputAndCall(memoryFactory, consumeNodeInNew);
            });
        }

        /**
         * Creates a Node that will access an ancestor Memory of the current call's Memory and then calls a node within
         * that ancestor Memory.
         */
        public <AM extends Memory<?>, T> Node<SM, T> accessAncestorMemoryAndCall(
                AncestorMemoryAccessor<SM, AM> ancestorMemoryAccessor, Node<AM, T> nodeInAncestor) {
            Objects.requireNonNull(ancestorMemoryAccessor);

            builder.typeTypeInstance(ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE, ANCESTOR_ACCESSOR_MEMORY_TRIP_TYPE_INSTANCE);
            AncestorMemoryDependency<AM, T> consumeNodeInAncestor = builder.ancestorMemoryDependency(nodeInAncestor);
            return builder.build(device -> {
                return device.accessAncestorMemoryAndCall(ancestorMemoryAccessor, consumeNodeInAncestor);
            });
        }
    }
}
