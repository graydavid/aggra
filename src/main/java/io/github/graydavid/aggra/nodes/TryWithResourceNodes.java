/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.nodes;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.Edge;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.Memory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.core.TypeInstance;
import io.github.graydavid.onemoretry.Try;

/** Creates nodes that help manage Resources (aka. AutoCloseables) in a try-with-resources-like fashion. */
public class TryWithResourceNodes {
    private TryWithResourceNodes() {}

    /** Class for TypeInstance for all nodes of type TRY_WITH_RESOURCE_TYPE. */
    private static class TryWithResourceTypeInstance extends TypeInstance {
        private final Node<?, ? extends AutoCloseable> resourceNode;

        private TryWithResourceTypeInstance(Node<?, ? extends AutoCloseable> resourceNode) {
            this.resourceNode = resourceNode;
        }

        public Node<?, ? extends AutoCloseable> getResourceNode() {
            return resourceNode;
        }
    }

    /** All nodes created through this class will have this as their type. */
    public static final Type TRY_WITH_RESOURCE_TYPE = new Type("TryWithResource") {
        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return instance instanceof TryWithResourceTypeInstance;
        }
    };

    /**
     * Returns a validator factory that makes sure the passed-in node is targeted by exactly 1 TRY_WITH_RESOURCE_TYPE
     * consumer in the given GraphCandidate. Implicit in this statement is that the node is part of the GraphCandidate,
     * which if false, also throws an IllegalArgumentException.
     */
    public static ForNodeGraphValidatorFactory validateResourceConsumedByTryWithResource() {
        return ResourceConsumedByTryWithResourceGraphValidatorFactory.INSTANCE;
    }

    /** Validator for {@link #validateResourceConsumedByTryWithResource} */
    private static class ResourceConsumedByTryWithResourceGraphValidatorFactory
            implements ForNodeGraphValidatorFactory {
        private static final ResourceConsumedByTryWithResourceGraphValidatorFactory INSTANCE = new ResourceConsumedByTryWithResourceGraphValidatorFactory();

        private ResourceConsumedByTryWithResourceGraphValidatorFactory() {}

        @Override
        public GraphValidator create(Node<?, ?> node) {
            Objects.requireNonNull(node);
            return candidate -> {
                validateResourceConsumedByTryWithResource(candidate, node);
            };
        }

        private static void validateResourceConsumedByTryWithResource(GraphCandidate<?> candidate, Node<?, ?> node) {
            long targetingTryWithResourceNodeCount = candidate.getConsumingEdgesOf(node)
                    .stream()
                    .map(Edge::getConsumingNode)
                    .filter(consumer -> consumerIsTryWithResourceTargetingNode(consumer, node))
                    .count();
            if (targetingTryWithResourceNodeCount != 1) {
                Collection<Role> consumingRoles = candidate.getConsumingEdgesOf(node)
                        .stream()
                        .map(edge -> edge.getConsumingNode().getRole())
                        .collect(Collectors.toList());
                String message = String.format(
                        "Expected exactly 1 TryWithResource consumer targetting '%s' but found '%d' among consumers '%s'",
                        node.getRole(), targetingTryWithResourceNodeCount, consumingRoles);
                throw new IllegalArgumentException(message);
            }
        }

        private static boolean consumerIsTryWithResourceTargetingNode(Node<?, ?> consumer, Node<?, ?> node) {
            if (TRY_WITH_RESOURCE_TYPE.equals(consumer.getType())) {
                Node<?, ?> resourceNode = ((TryWithResourceTypeInstance) consumer.getTypeInstance()).getResourceNode();
                return resourceNode == node;
            }
            return false;
        }

        @Override
        public String getDescription() {
            return "Consumed by TryWithResource";
        }
    }

    /**
     * Starts the creation of a TryWithResource node.
     * 
     * Note: the returned object can be used to create only one Node. If an attempt is made to create any more, then an
     * IllegalStateException will be thrown.
     */
    public static <M extends Memory<?>> SpecifyDependencies<M> startNode(Role role, Class<M> memoryClass) {
        Node.CommunalBuilder<M> builder = Node.communalBuilder(memoryClass).role(role);
        return new SpecifyDependencies<>(builder);
    }

    /**
     * The second part of a two-part process to create a resource Node. In this part, the dependencies are specified,
     * along with any optional settings.
     */
    public static class SpecifyDependencies<M extends Memory<?>> {
        private final Node.CommunalBuilder<M> builder;

        private SpecifyDependencies(Node.CommunalBuilder<M> builder) {
            this.builder = builder.dependencyLifetime(DependencyLifetime.NODE_FOR_ALL);
        }

        /** A builder-like method similar to {@link Node.CommunalBuilder#exceptionStrategy(ExceptionStrategy)}. */
        public SpecifyDependencies<M> exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            builder.exceptionStrategy(exceptionStrategy);
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
         * Creates a try-with-resource-like node. The created node will call a Node to obtain a resource (i.e.
         * Autocloseable), call another Node (which presumably depends on the resource) to compute the output, and then
         * close the resource once the output node and all of its transitive dependencies are finished.
         * 
         * There are a couple of protections in play to make sure that the resource doesn't escape and is closed only
         * once all consumers are closed.<br>
         * 1. This method makes sure that the output node has a declared DependencyLifetime that guarantees that all of
         * its direct and transitive dependencies are complete when the output node itself completes (as of this
         * writing, that means NODE_FOR_ALL). This way, the TryWithResource node can be sure that all consumers of the
         * resource are finished before closing it.<br>
         * 2. The resourceNode is not passed in directly, but rather the factory that creates the resourceNode is. The
         * idea is that this resource Node should not be accessible outside of this TryWithResource Node. This may not
         * be sufficient by itself, though (e.g. if you're using a Dependency Injection Container).<br>
         * 3. Similarly, the output Node is also not provided directly but rather through a factory. In this case, the
         * output Node factory is provided with the created resource Node in order to create the output Node. This helps
         * make sure that the outputNode (and whatever other nodes are created as a part of the output Node factory) are
         * the only ones that have access to the Resource. As with #2, this protection may not be sufficient. <br>
         * 4. The returned node has a {@link GraphValidators#consumerEnvelopsDependency(Node, Node)} validator installed
         * on it. It makes sure that its Node "envelops" the resourceNode, which means that all paths in the Graph to
         * the resourceNode also go through the TryWithResource node first. This helps ensure what #2 and #3 are trying
         * to do (although not completely, since the user can still forget to use the TryWithResource Node at all in the
         * Graph and use the resource Node directly), but the user has to wait until Graph creation time for this
         * validation to kick in.<br>
         * 5. This method throws an IllegalArgumentException if the produced resourceNode doesn't have a
         * {@link TryWithResourceNodes#validateResourceConsumedByTryWithResource()} GraphValidatorFactory installed on
         * it. This makes sure the resourceNode is consumed by a targeted TryWithResource Node in any GraphCandidate.
         * This helps patch the hole in #4, where the user may reference only the ResourceNode directly instead of the
         * TryWithResourceNode that should surround it.
         * 
         * The exceptional behavior tries to match the try-with-resources exceptional behavior, with a few caveats (due
         * to needing to handle checked exceptions and extra exceptions that CompletableFuture adds):<br>
         * * If the resourceNode throws or returns an exception, that exception is propagated, the output node is never
         * called, and the resource is never closed.<br>
         * * If closing the resource throws an InterruptedException, then it is handled like any other resource close
         * checked exception, except that the Thread's interrupt status is set.<br>
         * * If closing the resource throws a checked exception and the output Node completed successfully, then the
         * closing exception is transformed as per resourceCloseCheckedExceptionTransformer. This transformed exception
         * is returned as the TryWithResource Node's response (assuming that resourceCloseCheckedExceptionTransformer
         * behaves as per spec and returns an unchecked exception; otherwise, its response is wrapped in an
         * IllegalArgumentException, and that's returned as the TryWithResource Node's response). (Note: this response
         * is possible wrapped in an extra CompletionException, so as always, use
         * {@link Reply#getFirstNonContainerExceptionNow()} if you want to access the root checked exception.<br>
         * * If closing the resource throws an unchecked exception and the output Node completed successfully, then the
         * TryWithResource Node returns the exception as the response (with the same qualification about possibly being
         * wrapped in a CompletionException, as in the last point).<br>
         * * If closing the resource throws an exception (checked or unchecked -- doesn't matter) and the output Node
         * returns an exception, then the closing exception is added to the output Node's CallException as a suppressed
         * exception.
         * 
         * @param resourceCloseCheckedExceptionTransformer used to transform any checked exceptions thrown during
         *        resource closure into unchecked exceptions (either a RuntimeException or an Error).
         * 
         * @throws IllegalArgumentException if outputNodeFactory produces a Node that doesn't wait for all direct and
         *         transitive dependencies.
         * @throws IllegalArgumentException if resourceNodeFactory produces a Node that doesn't have a
         *         {@link TryWithResourceNodes#validateResourceConsumedByTryWithResource()} GraphValidatorFactory
         *         installed on it.
         */
        public <A extends AutoCloseable, T> Node<M, T> tryWith(Supplier<Node<M, A>> resourceNodeFactory,
                Function<? super Node<M, A>, Node<M, T>> outputNodeFactory,
                Function<? super Throwable, ? extends Throwable> resourceCloseCheckedExceptionTransformer) {
            Objects.requireNonNull(resourceCloseCheckedExceptionTransformer);
            Node<M, A> resourceNode = requireResourceValidatesIsConsumedByTryWithResourceNode(
                    resourceNodeFactory.get());
            Node<M, T> outputNode = requireTransitiveWaitingOutputNode(outputNodeFactory.apply(resourceNode));
            SameMemoryDependency<M, A> consumeResource = builder.primedDependency(resourceNode);
            SameMemoryDependency<M, T> consumeOutput = builder.sameMemoryUnprimedDependency(outputNode);
            builder.typeTypeInstance(TRY_WITH_RESOURCE_TYPE, new TryWithResourceTypeInstance(resourceNode));
            builder.graphValidatorFactory(new TryWithResourceEnvelopsResourceGraphValidatorFactory(resourceNode));
            return builder.build(device -> {
                A resource = device.call(consumeResource).join();
                return device.call(consumeOutput)
                        .whenComplete(
                                (r, t) -> closeAfterOutput(resource, t, resourceCloseCheckedExceptionTransformer));
            });
        }

        /**
         * Same as {@link #tryWith(Supplier, Function, Function)}, except uses "RuntimeExeption::new" as the
         * resourceCloseCheckedExceptionTransformer (i.e. wrap the checked exception in a RuntimeException).
         */
        public <A extends AutoCloseable, T> Node<M, T> tryWith(Supplier<Node<M, A>> resourceNodeFactory,
                Function<? super Node<M, A>, Node<M, T>> outputNodeFactory) {
            return tryWith(resourceNodeFactory, outputNodeFactory, RuntimeException::new);
        }

        private <A extends AutoCloseable> Node<M, A> requireResourceValidatesIsConsumedByTryWithResourceNode(
                Node<M, A> resourceNode) {
            boolean validates = resourceNode.getGraphValidatorFactories()
                    .stream()
                    .anyMatch(factory -> factory instanceof ResourceConsumedByTryWithResourceGraphValidatorFactory);
            if (validates) {
                return resourceNode;
            }
            String message = "resourceNodeFactory must produce a node that validates it's consumed by a TryWithResource node (validator produced by TryWithResourceNodes#validateResourceConsumedByTryWithResource): "
                    + resourceNode;
            throw new IllegalArgumentException(message);
        }

        private <T> Node<M, T> requireTransitiveWaitingOutputNode(Node<M, T> outputNode) {
            // We could check minimum DependencyLifetime, but that could change due to non-local changes in transitive
            // dependencies. Best to force declared DependencyLifetime and avoid that issue.
            if (outputNode.getDeclaredDependencyLifetime().waitsForAllDependencies()) {
                return outputNode;
            }

            String message = "outputNodeFactory must produce a node with a declared DependencyLifetime that waits for all dependencies (direct and transitive) to complete: "
                    + outputNode;
            throw new IllegalArgumentException(message);
        }

        private <A extends AutoCloseable> void closeAfterOutput(A resource, Throwable outputThrowable,
                Function<? super Throwable, ? extends Throwable> resourceCloseCheckedExceptionTransformer) {
            Try<Void> tryClose = Try.runCatchThrowable(resource::close);
            if (outputThrowable == null) {
                tryClose.getOrThrowUnchecked(resourceCloseCheckedExceptionTransformer);
            } else {
                tryClose.getFailure()
                        .ifPresent(closeThrowable -> Reply.getCallException(outputThrowable)
                                .addSuppressed(closeThrowable));
            }
        }

        /** Creates a validator that makes sure TryWithResourceNode envelops ResourceNode. */
        private static class TryWithResourceEnvelopsResourceGraphValidatorFactory
                implements ForNodeGraphValidatorFactory {
            private final Node<?, ?> resourceNode;

            private <A extends AutoCloseable> TryWithResourceEnvelopsResourceGraphValidatorFactory(
                    Node<?, A> resourceNode) {
                this.resourceNode = resourceNode;
            }

            @Override
            public GraphValidator create(Node<?, ?> tryWithResourceNode) {
                return GraphValidators.consumerEnvelopsDependency(tryWithResourceNode, resourceNode);
            }

            @Override
            public String getDescription() {
                return "Envelops '" + resourceNode.getRole() + "'";
            }
        }
    }
}
