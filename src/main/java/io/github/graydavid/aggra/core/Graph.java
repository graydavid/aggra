/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import io.github.graydavid.aggra.core.CallObservers.ObservationFailureObserver;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceInputFactory;

/**
 * The entry point for describing and calling a set of Nodes. A COncurrentGRaph. Like Node, Graph is a "static"
 * description of the structure of the graph of Nodes. To be able to execute a call for a given request, the user must
 * create a {@link Call} from the Graph.
 * 
 * There are a few reasons this class exists:<br>
 * 1. This class has a global view of the Nodes that will be called. In contrast, Nodes themselves only have a local
 * vision that stops at their dependencies. A global view is helpful, for example, in answering questions like what does
 * the graph of possible Nodes look like, who consumers Node X, or how do I know when a Node will not be called for a
 * particular Graph call.<br>
 * 2. It's possible to track down and control where the entry point to graphs are.<br>
 * 3. A single entry point for a graph of Nodes helps with flow control. E.g. there can be a common point to wait for
 * the entire graph to finish execution and perform some common clean up actions.<br>
 * 4. It's a useful structure for integration with frameworks like GraphQL. GraphQL is good at inspecting requests and
 * managing which code fulfills those pieces. This would be unnatural to fit into a Node's intrinsic behavior. Instead,
 * Graph provides the freedom for clients, like GraphQL, to choose which Nodes to execute and when. So, in this way,
 * Graph is like a Node with wide-open behavior.
 */
public class Graph<M extends Memory<?>> implements Caller {
    private static final Collection<GraphValidator> VALIDATORS_FOR_EVERY_GRAPH = List
            .of(GraphValidators.ancestorMemoryRelationshipsDontCycle());
    private final Role role;
    private final GraphCandidate<M> candidate;
    private final boolean allRootNodesWaitForAllDependencies; // Derived, frequently-used value
    private final boolean shadowSupportsActiveCancelHooks; // Derived, frequently-used value

    private Graph(Role role, GraphCandidate<M> candidate) {
        this.role = Objects.requireNonNull(role);
        this.candidate = candidate;
        this.allRootNodesWaitForAllDependencies = candidate.getRootNodes()
                .stream()
                .allMatch(node -> node.getMinimumDependencyLifetime().waitsForAllDependencies());
        this.shadowSupportsActiveCancelHooks = candidate.getRootNodes()
                .stream()
                .anyMatch(Node::shadowSupportsActiveCancelHooks);
    }

    /**
     * Tries to construct a Graph with the given set of roots. As a part of this process, validators will be run to make
     * sure that a valid Graph can be created from the given root nodes. The validators will consist of the following:
     * each Node's {@link Node#getGraphValidators()}, extraValidators, and any other validators that the Graph class
     * itself chooses to run. If any of the validators fails, this method will fail.
     * 
     * @param roots all of the root nodes in the Graph. The Graph will be comprised of all the roots, their
     *        dependencies, the dependencies of those dependencies, etc. Only the root nodes will be callable through
     *        Graph.
     */
    public static <M extends Memory<?>> Graph<M> fromRoots(Role role, Set<Node<M, ?>> roots,
            Collection<GraphValidator> extraValidators) {
        return fromCandidate(role, GraphCandidate.fromRoots(roots), extraValidators);
    }

    /** Same as {@link #fromRoots(Role, Set)}, except with no extra validators to run. */
    public static <M extends Memory<?>> Graph<M> fromRoots(Role role, Set<Node<M, ?>> roots) {
        return fromRoots(role, roots, List.of());
    }

    /**
     * Similar to {@link #fromRoots(Role, Set, Collection)}, except using a GraphCandidate instead of the given set of
     * roots. This is useful if a user already has access to a GraphCandidate.
     */
    public static <M extends Memory<?>> Graph<M> fromCandidate(Role role, GraphCandidate<M> candidate,
            Collection<GraphValidator> extraValidators) {
        Stream<GraphValidator> allValidators = Stream.concat(VALIDATORS_FOR_EVERY_GRAPH.stream(),
                candidate.getAllNodes().stream().map(Node::createGraphValidators).flatMap(v -> v.stream()));
        allValidators = Stream.concat(allValidators, extraValidators.stream());
        allValidators.forEach(validator -> validator.validate(candidate));
        return new Graph<>(role, candidate);
    }

    @Override
    public Role getRole() {
        return role;
    }

    /** Returns only the root nodes in this graph. */
    public Set<Node<M, ?>> getRootNodes() {
        return candidate.getRootNodes();
    }

    /** Returns all of the nodes in this graph. */
    public Set<Node<?, ?>> getAllNodes() {
        return candidate.getAllNodes();
    }

    /**
     * Returns the set of edges in this graph that consume the given node: i.e. those edges where node is a dependency.
     * 
     * @throws IllegalArgumentException if node is not part of this graph.
     */
    public Set<Edge> getConsumingEdgesOf(Node<?, ?> node) {
        return candidate.getConsumingEdgesOf(node);
    }

    /** Returns all of the edges in this graph. */
    public Set<Edge> getAllEdges() {
        return candidate.getAllEdges();
    }

    /** Returns whether all root nodes have {@link DependencyLifetime#waitsForAllDependencies()}. */
    boolean allRootNodesWaitForAllDependencies() {
        return allRootNodesWaitForAllDependencies;
    }

    /** Answers whether any root node {@link Node#shadowSupportsActiveCancelHooks()}; */
    boolean shadowSupportsActiveCancelHooks() {
        return shadowSupportsActiveCancelHooks;
    }

    /**
     * Answers whether (implicitly or explicitly) ignoring a given Node's Reply (either through a GraphCall or a
     * DependencyCallingDevice) will definitely trigger its Reply cancellation signal... simply based on the Graph's
     * static structure (i.e. nothing related to the properties of the dynamic GraphCall). As stated in
     * {@link DependencyCallingDevice#ignore(Reply)}, this triggering can only happen if we can prove that all potential
     * consumers either won't yield the same Reply from that dependency or have already ignored it themselves. The only
     * proof we currently support is if there is only one consumer of the dependency and that consumer relationship has
     * a {@link Dependency#getConsumerCallToDependencyReplyCardinality()} of either ONE_TO_ONE and ONE_TO_MANY (which is
     * the same as saying that it's either a SameMemoryDependency or a NewMemoryDependency). (Note: root nodes of the
     * Graph have an implicit consumer: the top-level caller of the Graph itself. As far as properties of that
     * relationship go, you can consider it to be equivalent to a SameMemoryDependency relationship.) Under these
     * conditions, Aggra can easily prove that the single consumer call has ignored and no longer needs the Node's
     * Reply, and so it can be cancelled; otherwise, Aggra will not go to the effort to prove so. Other conditions may
     * be added in the future as/when Aggra supports them (although this is unlikely, unless the new conditions are
     * equally as simple.)
     * 
     * @throws IllegalArgumentException if node is not part of this graph.
     * 
     * @apiNote triggering the cancellation signal may or may not have any actual effect on the Node's Replies depending
     *          on whether the Node {@link CancelMode#supportsCompositeSignal()} or
     *          {@link CancelMode#supportsCustomAction()}.
     * @apiNote A couple of notes about possible future expansions of the conditions under which ignores will trigger
     *          signals. I can probably reject any algorithm that requires tracking consumer calls from multiple
     *          consumers. E.g. If both consumer calls A and B ignore Reply R, then R can be cancelled. There's just too
     *          much work involved in tracking the Replys and then proving that all paths are covered. One possible idea
     *          is introducing the concept of a Reply "belonging exclusively" to a single consumer call. E.g.
     *          NewMemoryDependency Replys belong exclusively to a single consumer call: no other consumer call, even
     *          from the same consumer through a different Dependency, will get access to the same NewMemoryDependency
     *          Reply. These types of Replys could be cancelled right away if their consumer call asks to ignore them,
     *          regardless of how many other consumers the dependency Node has. The big problem, though, is ensuring
     *          that whoever is doing that ignoring is the actual consumer that called it. See the notes around
     *          {@link #requireReplyToIgnoreYieldedByCallFromThisDevice(Reply)}, which describe the imperfect checks
     *          already in place and the assumptions made to justify them. These checks would have to become much more
     *          strict and inefficient... or we would have to accept the insecurity of not doing that check (which seems
     *          wrong). Due to the complications/questions around this, I don't think this is worth pursing now. Lastly,
     *          both of these proposals are properties of the GraphCall itself, rather than the Graph, so they couldn't
     *          be added here, anyway.
     */
    public boolean ignoringWillTriggerReplyCancelSignal(Node<?, ?> node) {
        return candidate.ignoringWillTriggerReplyCancelSignal(node);
    }

    @Override
    public String toString() {
        return "role=" + role + ";" + candidate;
    }

    /**
     * Opens/starts a cancellable call to a Graph. Whereas Graph is the "static" representation of the graph, describing
     * its uncallable, unchanging structure; the Call object provides a request-specific means of calling Nodes in that
     * graph.
     * 
     * @param factory the factory used to create the Memory for this call.
     *        <p>
     *        The implementation of the factory should just be a simple creation of a Memory along with, if necessary,
     *        creating any needed ancestor Memorys. There should not be any complicated logic, like calling other Nodes
     *        or otherwise using the Memory during this call. Violating this advice could cause the wrong caller to be
     *        passed between calls (causing confusion in debug information) or could open up the program to cyclical
     *        calls (which could halt the program, since a Reply may be waiting on itself to complete before
     *        completing).
     *        <p>
     *        In addition, clients should not intercept this memory, save it, expose it, and use it after this method is
     *        complete. Aggra assumes that the memory will be used only internal to this method call. Violating this
     *        advice could cause the management of the Memory's MemoryScope to be inconsistent with its usage, with
     *        consequences like never delivering cancellation messages to running Replys.
     *        <p>
     *        As for MemoryScope, although the MemoryFactory interface says the following, I'm including this for
     *        emphasis: similarly, clients should also not intercept the MemoryScope used to create the Memory, other
     *        than to use it to create new Memorys inside of the MemoryFactory method call.
     * @param observer will observe all Node calls made during this GraphCall. On entry, this observer will be converted
     *        into a fault-tolerant observer by calling
     *        {@link Observer#faultTolerant(Observer, ObservationFailureObserver)}. The ObservationFailureObserver used
     *        there will add the unhandled exception to the GraphCall's list of unhandled exceptions, which will be
     *        available on GraphCall completion in {@link GraphCall.FinalState#getUnhandledExceptions()}. The resulting
     *        fault-tolerant Observer is what's actually used to observe Node calls. Exceptions are suppressed in this
     *        way so that they don't interfere with the result-producing path of a GraphCall (e.g. making sure all
     *        Behavior CompletionStages complete before their Node's Replys complete).
     * 
     * @throws MisbehaviorException if factory creates a Memory with a different scope than what's provided to it as an
     *         argument.
     * 
     * @apiNote The "cancellable" part comes from the fact that a non-cancellable variant of this method used to exist
     *          (and may in the future if it justifies itself). Supporting cancellation means 2 extra reads from a
     *          volatile variable per Node call (as part of the passive cancellation hooks that Aggra runs before and
     *          after priming dependencies) and 1 extra read for evaluations of Replys' custom cancellation signals.
     *          Requesting a non-cancellable call would allow for the opportunity to optimize away those reads. There
     *          are downsides, though, which for now outweigh the addition of this feature.
     * 
     *          First, that opportunity can only be realized if in addition to requesting this feature, all of the
     *          graph's root nodes {@link DependencyLifetime#waitsForAllDependencies()}. If that's not true, then
     *          although you still won't have the opportunity to cancel the GraphCall directly, the GraphCall itself
     *          will still be cancellable internally in order to be able to support cancellation when its root Node
     *          Replies are complete (and other Graph Nodes Replies may still be running). A potential
     *          "GraphCall#isResponsiveToCancelSignal" method could exist to figure out which variant is actually
     *          created.
     * 
     *          Second, GraphCall's should be cancellable in order to support {@link GraphCall#abandon()} and its
     *          variants. Having users remember to do that is brittle.
     */
    public GraphCall<M> openCancellableCall(MemoryNoSourceInputFactory<M> factory, Observer observer) {
        return GraphCall.createCancellableCall(this, factory, observer);
    }
}
