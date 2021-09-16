/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.ConsumerCallToDependencyReplyCardinality;
import io.github.graydavid.aggra.core.Dependencies.Dependency;

/** A utility class for holding graph validation and related classes. */
public class GraphValidators {
    private GraphValidators() {}

    /**
     * A candidate for being a graph. This candidate is one step away from becoming a Graph: it just needs to undergo
     * validation first.
     */
    public static class GraphCandidate<M extends Memory<?>> {
        private final Set<Node<M, ?>> roots;
        private final Map<Node<?, ?>, Set<Edge>> nodeToConsumingEdges;
        private final Set<Node<?, ?>> nodes;
        private final Set<Edge> edges;

        private GraphCandidate(Set<Node<M, ?>> roots) {
            this.roots = Set.copyOf(roots);
            this.nodeToConsumingEdges = captureConsumingEdges(roots);
            this.nodes = nodeToConsumingEdges.keySet().stream().collect(Collectors.toUnmodifiableSet());
            this.edges = nodeToConsumingEdges.values()
                    .stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toUnmodifiableSet());
        }

        /**
         * @param roots all of the root nodes in the desired Graph. The Graph will be comprised of all the roots, their
         *        dependencies, the dependencies of those dependencies, etc. Only the root nodes will be callable
         *        through the Graph.
         */
        public static <M extends Memory<?>> GraphCandidate<M> fromRoots(Set<Node<M, ?>> roots) {
            return new GraphCandidate<>(roots);
        }

        private static Map<Node<?, ?>, Set<Edge>> captureConsumingEdges(Set<? extends Node<?, ?>> roots) {
            Map<Node<?, ?>, Set<Edge>> nodeToConsumingEdges = new HashMap<>();
            Set<Node<?, ?>> visitedNodes = new HashSet<>();
            roots.stream().forEach(root -> nodeToConsumingEdges.put(root, new HashSet<>()));
            roots.stream().forEach(root -> recursiveCaptureConsumingEdges(root, nodeToConsumingEdges, visitedNodes));
            nodeToConsumingEdges.replaceAll((node, consumers) -> Set.copyOf(consumers));
            return Map.copyOf(nodeToConsumingEdges);
        }

        private static void recursiveCaptureConsumingEdges(Node<?, ?> nodeToCheck,
                Map<Node<?, ?>, Set<Edge>> nodeToConsumingEdges, Set<Node<?, ?>> visitedNodes) {
            // Used for efficiency. The graph doesn't have cycles => keeping track of visitedNodes not strictly
            // necessary.
            if (visitedNodes.contains(nodeToCheck)) {
                return;
            }

            visitedNodes.add(nodeToCheck);
            Set<? extends Dependency<?, ?>> dependencies = nodeToCheck.getDependencies();
            dependencies.stream()
                    .forEach(dependency -> nodeToConsumingEdges
                            .computeIfAbsent(dependency.getNode(), d -> new HashSet<>())
                            .add(new Edge(nodeToCheck, dependency)));
            dependencies.stream()
                    .forEach(dependency -> recursiveCaptureConsumingEdges(dependency.getNode(), nodeToConsumingEdges,
                            visitedNodes));
        }

        /** Returns only the root nodes in this candidate. */
        public Set<Node<M, ?>> getRootNodes() {
            return roots;
        }

        /** Returns all of the nodes in this candidate. */
        public Set<Node<?, ?>> getAllNodes() {
            return nodes;
        }

        /**
         * Returns the set of edges in this candidate that consume the given node: i.e. those edges where node is a
         * dependency.
         * 
         * @throws IllegalArgumentException if node is not part of this graph.
         */
        public Set<Edge> getConsumingEdgesOf(Node<?, ?> node) {
            Set<Edge> consumingEdges = nodeToConsumingEdges.get(node);
            if (consumingEdges == null) {
                throw nodeNotPartOfGraphException(node);
            }
            return consumingEdges;
        }

        private IllegalArgumentException nodeNotPartOfGraphException(Node<?, ?> node) {
            String message = String.format(
                    "Tried to retrieve consumers of node '%s', but that node is not part of this graph. ", node);
            return new IllegalArgumentException(message);
        }

        /** Returns all of the edges in this candidate. */
        public Set<Edge> getAllEdges() {
            return edges;
        }

        /** See {@link Graph#ignoringWillTriggerReplyCancelSignal(Node)} */
        public boolean ignoringWillTriggerReplyCancelSignal(Node<?, ?> node) {
            if (!nodes.contains(node)) {
                throw nodeNotPartOfGraphException(node);
            }

            Set<Edge> consumingEdges = getConsumingEdgesOf(node);
            boolean isRootNode = getRootNodes().contains(node);
            int numTotalConsumers = consumingEdges.size() + (isRootNode ? 1 : 0);

            // If there are multiple consumers, we can't cancel
            if (numTotalConsumers > 1) {
                return false;
            }

            // If the sole consumer is the top-level caller of the Graph, this relationship has a
            // cancellation-compatible
            // cardinality of ONE_TO_ONE, so we can cancel
            if (isRootNode) {
                return true;
            }

            // If the sole consumer is another node, we can only cancel if it has the right cardinality
            Dependency<?, ?> dependency = consumingEdges.iterator().next().getDependency();
            ConsumerCallToDependencyReplyCardinality callToReplyCardinality = dependency
                    .getConsumerCallToDependencyReplyCardinality();
            return CANCEL_COMPATIBLE_CALL_TO_REPLY_CARDINALITIES.contains(callToReplyCardinality);
        }

        // Note: ConsumerCallToDependencyReplyCardinality#MANY_TO_ONE (i.e AncestorMemoryDependency) is not here,
        // because multiple calls for the same consumer Node can yield the same Reply. So, if one consumer call ignores
        // the Reply, we can't be sure whether any potential other consumer calls (even from the same Node) will need
        // the Reply intact.
        private static final Set<ConsumerCallToDependencyReplyCardinality> CANCEL_COMPATIBLE_CALL_TO_REPLY_CARDINALITIES = Set
                .of(ConsumerCallToDependencyReplyCardinality.ONE_TO_ONE,
                        ConsumerCallToDependencyReplyCardinality.ONE_TO_MANY);

        @Override
        public String toString() {
            return roots.stream()
                    .map(node -> node.getRole().toString())
                    .collect(Collectors.joining(", ", "roots=(", ")"));
        }
    }

    /**
     * Validates a GraphCandidate, seeing whether it should be promoted to a full-fledged Graph or not. This concept is
     * useful for a couple of reasons. First, it can perform validation that a Node (builder) can't do itself at
     * creation time. This usually involves consumer information that the Node doesn't have access to. Second, this
     * concept is also useful for performing validation at a Graph level that doesn't really belong to any Node.
     */
    @FunctionalInterface
    public interface GraphValidator {
        /**
         * Checks whether the GraphCandidate is valid.
         * 
         * @throws IllegalArgumentException if the candidate is invalid and should not be promoted to a Graph. The lack
         *         of this exception means the candidate is valid.
         */
        void validate(GraphCandidate<?> candidate);
    }

    /**
     * Creates GraphValidators for a specific node. This concept is useful, because Nodes sometimes want to validate
     * Graphs based on relationships to themselves but must declare that validation with objects created before the Node
     * is created.
     */
    public interface ForNodeGraphValidatorFactory {
        GraphValidator create(Node<?, ?> node);

        /**
         * Returns a description of the factory to help users understand what it does. The word "validates" is
         * implicitly in front of the description, and so the implementation does not need to included it. E.g. "Is
         * surrounded by <consumer.getRole>" would be a good description for dependency's factory if it invoked
         * {@link #consumerSurroundsDependency}.
         */
        String getDescription();
    }

    /**
     * Returns a graph validator that checks whether the consumer node "envelops" the dependency node. What this means
     * is that all paths from a root in the GraphCandidate to dependency must pass through consumer. There may be
     * intervening nodes between consumer and dependency, and that's fine. It's just that every path must path through
     * consumer first.
     * 
     * Note: Implicit in this check is that dependency is a part of the candidate; if it's not, then an
     * IllegalArgumentException will also be thrown then.
     * 
     * Note: a nodes is considered to envelop itself: all paths from a root to X pass through X.
     */
    public static GraphValidator consumerEnvelopsDependency(Node<?, ?> consumer, Node<?, ?> dependency) {
        return new ConsumerEnvelopsDependencyGraphValidator(consumer, dependency);
    }

    /** Validator for {@link #consumerEnvelopsDependency} */
    private static class ConsumerEnvelopsDependencyGraphValidator implements GraphValidator {
        private final Node<?, ?> consumer;
        private final Node<?, ?> dependency;

        private ConsumerEnvelopsDependencyGraphValidator(Node<?, ?> consumer, Node<?, ?> dependency) {
            this.consumer = Objects.requireNonNull(consumer);
            this.dependency = Objects.requireNonNull(dependency);
        }

        @Override
        public void validate(GraphCandidate<?> candidate) {
            if (!candidate.getAllNodes().contains(dependency)) {
                String message = String.format("GraphCandidate '%s' doesn't contain dependency '%s'", candidate,
                        dependency.getSynopsis());
                throw new IllegalArgumentException(message);
            }

            checkNodeEnvelopedByConsumer(dependency, candidate, new HashSet<>(), new ArrayDeque<>());
        }

        private void checkNodeEnvelopedByConsumer(Node<?, ?> node, GraphCandidate<?> candidate,
                Set<Node<?, ?>> alreadyCheckedNodes, Deque<Node<?, ?>> currentPath) {
            currentPath.addFirst(node);
            checkNodeEnvelopedByConsumerWithUpdatedPath(node, candidate, alreadyCheckedNodes, currentPath);
            currentPath.removeFirst();
        }

        private void checkNodeEnvelopedByConsumerWithUpdatedPath(Node<?, ?> node, GraphCandidate<?> candidate,
                Set<Node<?, ?>> alreadyCheckedNodes, Deque<Node<?, ?>> currentPath) {
            // Base case 1 (Pass): we've already checked this node (optimization)
            boolean wasAdded = alreadyCheckedNodes.add(node);
            boolean alreadyChecked = !wasAdded;
            if (alreadyChecked) {
                return;
            }

            // Base case 2 (Pass): we've reached the consumer
            if (node == consumer) {
                return;
            }

            // Base case 3 (Fail): we've reached a root
            if (candidate.getRootNodes().contains(node)) {
                String currentPathString = currentPath.stream()
                        .map(n -> n.getRole().toString())
                        .collect(Collectors.joining("->", "(", ")"));
                String message = String.format(
                        "Dependency '%s' is exposed through path '%s' from a root which does not pass through consumer '%s' first",
                        dependency.getSynopsis(), currentPathString, consumer.getSynopsis());
                throw new IllegalArgumentException(message);
            }

            // Recurse
            candidate.getConsumingEdgesOf(node)
                    .forEach(edge -> checkNodeEnvelopedByConsumer(edge.getConsumingNode(), candidate,
                            alreadyCheckedNodes, currentPath));
        }
    }

    /**
     * Returns a for-node graph validator factory that checks whether
     * {@link Graph#ignoringWillTriggerReplyCancelSignal(Node)} for the passed-in node.
     * 
     * Note: Implicit in this check is that node is a part of the candidate; if it's not, then an
     * IllegalArgumentException will also be thrown then.
     */
    public static ForNodeGraphValidatorFactory ignoringWillTriggerReplyCancelSignal() {
        return IGNORE_WILL_TRIGGER_CANCEL_SIGNAL;
    }

    private static final ForNodeGraphValidatorFactory IGNORE_WILL_TRIGGER_CANCEL_SIGNAL = new ForNodeGraphValidatorFactory() {
        @Override
        public String getDescription() {
            return "Ignores will trigger Reply cancel signal";
        }

        @Override
        public GraphValidator create(Node<?, ?> node) {
            Objects.requireNonNull(node);
            return candidate -> {
                if (!candidate.ignoringWillTriggerReplyCancelSignal(node)) {
                    String message = String.format(
                            "Ignoring will not trigger Reply cancellation signal for node's Replies: '%s'", node);
                    throw new IllegalArgumentException(message);
                }
            };
        }
    };

    /**
     * Returns a graph validator that checks whether ancestor Memory relationship have a cycle in them. E.g. If a Node
     * in Memory A depends on a Node in ancestor Memory B, and then a Node in Memory B depends on a Node in ancestor
     * Memory A; then that would be a cycle. Cycles are not allowed under {@link Memory}'s definition. Memory
     * relationships should be organized in a static DAG structure: i.e. directed *acyclic* graph. Ideally, this would
     * be a compile-time check, but given the flexibility afforded to Memory in order to facilitate reuse, this check
     * has to be pushed off until graph creation/validation time.
     */
    public static GraphValidator ancestorMemoryRelationshipsDontCycle() {
        return ANCESTOR_MEMORY_NO_CYCLES;
    }

    private static final AncestorMemoryRelationshipsDontCycleGraphValidator ANCESTOR_MEMORY_NO_CYCLES = new AncestorMemoryRelationshipsDontCycleGraphValidator();

    /**
     * Validator for {@link #ancestorMemoryRelationshipsDontCycle}.
     * 
     * @apiNote package private to facilitate testing on MemoryEdge. Not intended for other use.
     */
    static class AncestorMemoryRelationshipsDontCycleGraphValidator implements GraphValidator {

        @Override
        public void validate(GraphCandidate<?> candidate) {
            Map<Class<? extends Memory<?>>, Set<MemoryEdge>> consumerMemoryNodeToAncestralMemoryEdges = candidate
                    .getAllEdges()
                    .stream()
                    .filter(edge -> edge.getDependency() instanceof AncestorMemoryDependency)
                    .map(MemoryEdge::fromEdge)
                    .collect(Collectors.groupingBy(memoryEdge -> memoryEdge.consumer, Collectors.toUnmodifiableSet()));
            MemoryEdges ancestralMemoryEdges = MemoryEdges
                    .fromConsumerMemoryNodeToEdges(consumerMemoryNodeToAncestralMemoryEdges);

            Set<Class<? extends Memory<?>>> alreadyCheckedMemoryNodes = new HashSet<>();
            Set<Class<? extends Memory<?>>> currentPathMemoryNodes = new LinkedHashSet<>();
            ancestralMemoryEdges.participatingMemoryNodes().forEach(memoryNode -> {
                checkMemoryNode(memoryNode, ancestralMemoryEdges, currentPathMemoryNodes, alreadyCheckedMemoryNodes);
            });
        }

        private void checkMemoryNode(Class<? extends Memory<?>> memoryNode, MemoryEdges ancestralMemoryEdges,
                Set<Class<? extends Memory<?>>> currentPathMemoryNodes,
                Set<Class<? extends Memory<?>>> alreadyCheckedMemoryNodes) {
            // Base case 1 (Fail): we've already encountered this node in the current path
            boolean wasAddedToCurrentPath = currentPathMemoryNodes.add(memoryNode);
            boolean alreadyInCurrentPath = !wasAddedToCurrentPath;
            if (alreadyInCurrentPath) {
                String path = Stream.concat(currentPathMemoryNodes.stream(), Stream.of(memoryNode))
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining("->", "(", ")"));
                String message = String.format("Detected an ancestral Memory cycle in path '%s'", path);
                throw new IllegalArgumentException(message);
            }

            checkMemoryNodeNotInCurrentPath(memoryNode, ancestralMemoryEdges, currentPathMemoryNodes,
                    alreadyCheckedMemoryNodes);
            currentPathMemoryNodes.remove(memoryNode);
        }

        private void checkMemoryNodeNotInCurrentPath(Class<? extends Memory<?>> memoryNode,
                MemoryEdges ancestralMemoryEdges, Set<Class<? extends Memory<?>>> currentPathMemoryNodes,
                Set<Class<? extends Memory<?>>> alreadyCheckedMemoryNodes) {
            // Base case 2 (Pass): we've already checked this node (optimization)
            boolean wasAddedToAlreadyChecked = alreadyCheckedMemoryNodes.add(memoryNode);
            boolean alreadyChecked = !wasAddedToAlreadyChecked;
            if (alreadyChecked) {
                return;
            }

            // Recurse
            ancestralMemoryEdges.getEdgesWithConsumer(memoryNode).forEach(edge -> {
                checkMemoryNode(edge.dependency, ancestralMemoryEdges, currentPathMemoryNodes,
                        alreadyCheckedMemoryNodes);
            });
        }

        /**
         * A convenient collection of MemoryEdges. Isn't a full MemoryGraph representation, because it doesn't cover
         * Memory nodes without edges.
         */
        private static class MemoryEdges {
            private final Map<Class<? extends Memory<?>>, Set<MemoryEdge>> consumerMemoryNodeToEdges;
            private final Set<Class<? extends Memory<?>>> participatingMemoryNodes;

            private MemoryEdges(Map<Class<? extends Memory<?>>, Set<MemoryEdge>> consumerMemoryNodeToEdges,
                    Set<Class<? extends Memory<?>>> participatingNodes) {
                this.consumerMemoryNodeToEdges = consumerMemoryNodeToEdges;
                this.participatingMemoryNodes = participatingNodes;
            }

            public static MemoryEdges fromConsumerMemoryNodeToEdges(
                    Map<Class<? extends Memory<?>>, Set<MemoryEdge>> consumerMemoryNodeToEdges) {
                Set<Class<? extends Memory<?>>> participatingMemoryNodes = consumerMemoryNodeToEdges.values()
                        .stream()
                        .flatMap(Collection::stream)
                        .flatMap(memoryEdge -> Stream.of(memoryEdge.consumer, memoryEdge.dependency))
                        .collect(Collectors.toSet());
                return new MemoryEdges(consumerMemoryNodeToEdges, participatingMemoryNodes);
            }

            public Collection<MemoryEdge> getEdgesWithConsumer(Class<? extends Memory<?>> memoryNode) {
                return consumerMemoryNodeToEdges.getOrDefault(memoryNode, Set.of());
            }

            public Set<Class<? extends Memory<?>>> participatingMemoryNodes() {
                return participatingMemoryNodes;
            }
        }

        /**
         * An edge in a graph of Memory relationships. That is, records the classes from a consumer to a dependency node
         * in terms of the Memory classes in each.
         * 
         * @apiNote package private to facilitate testing that
         *          {@link GraphValidators#ancestorMemoryRelationshipsDontCycle()} can't hit. Not intended for other
         *          use.
         */
        static class MemoryEdge {
            private final Class<? extends Memory<?>> consumer;
            private final Class<? extends Memory<?>> dependency;

            MemoryEdge(Class<? extends Memory<?>> consumer, Class<? extends Memory<?>> dependency) {
                this.consumer = consumer;
                this.dependency = dependency;
            }

            public static MemoryEdge fromEdge(Edge edge) {
                return new MemoryEdge(edge.getConsumingNode().getMemoryClass(),
                        edge.getDependency().getNode().getMemoryClass());
            }

            @Override
            public boolean equals(Object object) {
                if (object instanceof MemoryEdge) {
                    MemoryEdge other = (MemoryEdge) object;
                    return Objects.equals(this.consumer, other.consumer)
                            && Objects.equals(this.dependency, other.dependency);
                }
                return false;
            }

            @Override
            public int hashCode() {
                return Objects.hash(consumer, dependency);
            }
        }
    }
}
