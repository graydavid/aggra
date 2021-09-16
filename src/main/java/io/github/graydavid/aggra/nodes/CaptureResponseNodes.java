/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;
import io.github.graydavid.onemoretry.Try;

/**
 * Creates nodes that capture the response from other nodes, usually to expose exceptional information in a standard
 * response form (rather than having it thrown).
 */
public class CaptureResponseNodes {
    private CaptureResponseNodes() {}

    /** TypeInstance for all nodes of type CAPTURE_RESPONSE_TYPE. */
    private static final TypeInstance CAPTURE_RESPONSE_TYPE_INSTANCE = new TypeInstance() {};
    /** All nodes created through this class will have this as their type. */
    public static final Type CAPTURE_RESPONSE_TYPE = new Type("CaptureResponse") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance == CAPTURE_RESPONSE_TYPE_INSTANCE;
        }
    };

    /**
     * Starts the creation of a capture-response Node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyDependencies<M> startNode(Role role, Class<M> memoryClass) {
        Node.CommunalBuilder<M> builder = Node.communalBuilder(memoryClass)
                .typeTypeInstance(CAPTURE_RESPONSE_TYPE, CAPTURE_RESPONSE_TYPE_INSTANCE)
                .role(role);
        return new SpecifyDependencies<>(builder);
    }

    /**
     * The second part of a two-part process to create an exception node. In this part, the dependencies are specified,
     * along with any optional settings.
     */
    public static class SpecifyDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyDependencies(Node.CommunalBuilder<M> builder) {
            this.builder = builder;
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#dependencyLifetime(DependencyLifetime)}. */
        public SpecifyDependencies<M> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            builder.dependencyLifetime(dependencyLifetime);
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
         * Creates a node that captures the response from a dependency, whether or not that response is a success or
         * failure.
         * 
         * The created node returns a Try object representing the response from the dependency. The Try is a success or
         * failure depending on whether the dependency's response is a success or failure. In the latter case, remember
         * to use {@link Reply#getFirstNonContainerException(Throwable)} if you want to interact with the failure.
         * 
         * The created node can throw all of the exceptions listed at
         * {@link DependencyCallingDevice#call(SameMemoryDependency)} if the call itself fails to the dependency.
         * 
         * @apiNote There was originally also another factory method called "captureCall" that would capture any
         *          exceptions made calling the dependency as well, rather than propagating them. However, these types
         *          of errors indicate programming or serious errors, which even the returned method here either doesn't
         *          control or could just as easily throw. So, providing "captureCall" would have been a false choice,
         *          serving only to increase confusion.
         */
        public <T> Node<M, Try<T>> captureResponse(Node<M, T> dependency) {
            SameMemoryDependency<M, T> consumeDependency = builder.primedDependency(dependency);
            // The "call" method doesn't throw interrupt exceptions, and if a Reply returns them, then it's the user's
            // choice to do so, so they should be the one dealing with any interrupt behavior. I.e. swallowing is okay.
            return builder.build(device -> device.call(consumeDependency).handle(Try::ofSwallowingInterrupt));
        }

    }
}
