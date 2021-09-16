/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Memoizes Node calls. Nodes are meant to be static, immutable representations of algorithms. Memory provides the
 * ability to execute those algorithms on a per-request basis.
 * 
 * Every memory instance will be associated with a MemoryScope. MemoryScope instances are provided by the Aggra
 * framework at Memory-creation points. The main Memory created at those points as well as any created ancestor Memorys
 * will end up with the same MemoryScope. MemoryScope is a grouping mechanism, and Aggra uses it to manage the lifecycle
 * of associated Replys in Memories with that scope.
 * 
 * All memory instances must be initialized with and provide access to its overall input. Individual Nodes do not accept
 * the specific input necessary to execute their algorithms. Instead, they depend on other Nodes, retrieving results
 * from those Node using the Memory instance they're passed. However, at some point, there must be a Node who depends on
 * no other Nodes and simply provides the overall input for the request. It's this node that makes use of Memory's
 * ability to provide access to the overall input.
 * 
 * Memorys can depend on other Memorys. Those dependencies are called the Memory's "ancestors"; a Memory's direct
 * ancestors are called its "parents". A Memory's ancestors don't affect its behavior, though. Rather, modeling Memory
 * ancestors only serves the following purposes:<br>
 * 1. Access to context needed to create new Memorys during a graph call -- this ability is needed, for example, to
 * support iteration (i.e. for each time that it's called, one node may have to call another node multiple times). In an
 * ideal world, all nodes in a graph would execute in the same, sole instance of a given Memory; it's just simpler that
 * way; multiple Memorys introduce complexity. However, a given Memory instance stores calls for each Node only once.
 * This implies that multiple Memory instances are needed per call. <br>
 * 2. Access to shared context in shared ancestor Memorys during a graph call -- multiple Memorys may exist during a
 * single graph call. Nodes executed in separate Memorys may want to share some results between them for certain Node
 * calls. They can do this by referring to shared Memory instances.<br>
 * 3. Support reuse -- graph implementors may want to organize their graphs in groups of nodes so that the groups can be
 * shared across multiple graphs. Memorys provide a natural grouping mechanism to facilitate this sharing. In order not
 * to couple each graph to its ancestors, Memorys are allowed to depend on arbitrary numbers of parents (instead of, for
 * example, having Memorys depend on only one parent Memory at most, which would be simpler but would make Node reuse
 * more difficult).
 * 
 * To summarize, Memorys may be organized in a DAG (directed acyclic graph) to facilitate multiple calls to some Nodes,
 * share calls between other Nodes, and allow groups of Nodes to be reused across Aggra graphs.
 * 
 * To achieve these goals and ensure as much compile-time safety as possible (to protect against the dangers of
 * refactoring), graph implementors are expected to do the following:<br>
 * 1. Conceptually organize a static DAG of all the different Memorys they want to use during a Aggra call. <br>
 * 2. Create a new Memory class for each node in that DAG. The Memory should take all of its parent Memorys as arguments
 * in its constructor. <br>
 * 3. For each parent of a Memory, expose that parent through a type-specific POJO-style accessor.
 *
 * For example, let's say there's a simple Aggra graph where one "iterator" Node calls another "child" Node several
 * times during the course of a single graph call, and that each of these "child" Node calls shares the results of a
 * third "shared" node. This scheme would require at least two Memorys. Let's call them "main", which will contain the
 * "iterator" and "shared" Nodes; and "secondary", which will contain the "child" Node. Each Memory needs its own class.
 * The Main class will extend Memory and have no parents. The Secondary class will extend Memory and have a single
 * dependence on a Main Memory. The Secondary class will expose this parent through a "getMain" accessor method. When
 * the "iterator" Node is called, it will create new instances of the Secondary memory and call the "child" Node there.
 * During the "child" Node's call, it will invoke Secondary::getMain to be able to access the results of the "shared"
 * Node there.
 * 
 * Now, this may sound like a lot of boilerplate for such a simple situation... and honestly it is, but it also serves a
 * purpose: to protect against the dangers of refactoring. Let's imagine that we needed to insert another "Intermediate"
 * Memory instance between Main and Secondary in the example above. Main would remain the same. Secondary would change
 * to accept an Intermediate instance in its constructor. This single act would cause a sequence of compilation errors
 * that would guide the programmer towards most all of the other changes that need to be made: change Secondary's
 * "getMain" to "getIntermediate"; change the "iterator" Node's iteration (e.g. to create its own Intermediate and
 * Secondary Memory instances together, if that's appropriate, or potentially to move itself to the new Intermediate
 * Memory); and change accesses of the "shared" Node from the "child" Node (e.g. to access Intermediate first and then
 * Main through that or maybe instead to have the "shared" Node move itself to the Intermediate Memory).
 * 
 * In comparison, imagine that instead of separate Memory classes per DAG node, there was a single Memory class. After
 * inserting the Intermediate Memory, there would be no compilation mistakes. Clients would then have to rely on runtime
 * checks/failures to narrow down the set of changes that need to be made.
 * 
 * Unfortunately, the Memory-class-per-DAG-node approach doesn't guarantee 100% safety on its own at compile time. Some
 * runtime checks remain. For example, because ancestor Memory accesses can go arbitrarily far (e.g. parent of a parent
 * of a parent), it's impossible to say at compile time that a given Memory is actually a valid, accessible ancestor of
 * a given Memory. In addition, there would be no compilation error in exposing the full set of parents if an
 * implementor forgot to add them to the set. In spite of all this, I believe the cost of the Memory-class-per-DAG-node
 * approach to be worth the reward.
 * 
 * @param <I> the type of the overall input necessary for this Memory.
 * 
 * @apiNote I was tempted to name this class "Scope" at one point. While there are similarities between the two
 *          concepts, I feel like "Memory" more naturally evokes the semantics of how this class is used. "Scope" seems
 *          more static and removed from the concept of storage. In addition, there may be confusion for people familiar
 *          with "Scope" from dependency injection containers, where the Scope instance does matter, but people aren't
 *          used to dealing with that.
 */
public abstract class Memory<I> {
    private final MemoryScope scope;
    private final CompletionStage<I> input;
    private final Storage storage;
    private final Set<Memory<?>> parents;

    /**
     * @param scope the MemoryScope associated with this Memory. The Aggra framework will provide this argument at
     *        appropriate Memory-creation points.
     * @param input what to return from {@link #getInput()}
     * @param parents what to return from {@link #getParents()}
     * @param storageFactory capable of creating the Storage instance that will be used for implementing
     *        {@link #computeIfAbsent(Node, Supplier)} through delegation. This parameter is a Supplier/factory rather
     *        than a direct Storage instance to hint that there should be one Storage instance per Memory instance. The
     *        created Storage instance should not have anything in it initially, and all external references to it
     *        should be discarded after this Memory is created: only this memory is allowed to store things in and
     *        retrieve things from it.
     */
    protected Memory(MemoryScope scope, CompletionStage<I> input, Set<Memory<?>> parents,
            Supplier<? extends Storage> storageFactory) {
        this.scope = Objects.requireNonNull(scope);
        this.input = Objects.requireNonNull(input);
        this.storage = Objects.requireNonNull(storageFactory.get());
        this.parents = Set.copyOf(parents);
    }

    final MemoryScope getScope() {
        return scope;
    }

    /**
     * Returns a stringified version of the MemoryScope passed to the constructor. This allows users to perform
     * operations based on the MemoryScope identify without allowing access to the actual underlying MemoryScope object
     * (which could enable unintended MemoryScope usage during memory construction).
     */
    public final String getScopeToString() {
        return scope.toString();
    }

    /**
     * Returns the overall input for this Memory.
     * 
     * @apiNote Forcing a Memory to have a single input can definitely be restrictive. It's like forcing every function
     *          to have a single argument. That may not always be applicable. There are workarounds, like creating a
     *          single request class to hold all of the individual arguments that would otherwise be passed to a
     *          multi-argument function. While that's not fun to do, the upside is that Memory can guarantee that it's
     *          been initialized with the necessary input to work as expected, which is extremely powerful.
     * 
     */
    public final CompletionStage<I> getInput() {
        return input;
    }

    /**
     * Returns the set of parent/direct ancestors for this Memory. See the class's javadoc for more details about why a
     * Memory would want to have ancestors and how to model them.
     * 
     * This method guarantees that it only returns parents that were already constructed before the current Memory
     * instance was constructed. I.e. if you were to consider the current Memory instance's parents, the parents of
     * those parents, etc. as a single set, then the current Memory instance would not appear. Said another way, the
     * graph constructed by that single set would not form any cycles.
     * 
     * Note: this method is currently only used in the Aggra framework for debug information. There are no checks or
     * logic in place to make sure that a Memory's ancestors actually match what's returned by this collection. So,
     * clients should be wary.
     */
    public final Set<Memory<?>> getParents() {
        return parents;
    }

    /**
     * Should delegate to {@link Storage#computeIfAbsent(Node, Supplier)}; so, go see there for javadoc.
     * 
     * @throws IllegalArgumentException if node is not associated with the current memory. Similar to Storage, this
     *         exception means that no mapping was established for Node in this Memory for this call.
     */
    final <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
        if (!node.getMemoryClass().equals(getClass())) {
            throw new IllegalArgumentException(
                    "This memory cannot process nodes associated with other memories: " + node.getMemoryClass());
        }
        return storage.computeIfAbsent(node, replySupplier);
    }

    @Override
    public String toString() {
        String mySimpleString = objectToStringSimpleName(this) + "(" + objectToStringSimpleName(scope) + ")";
        String parentString = getParents().isEmpty() ? ""
                : getParents().stream().map(Object::toString).collect(Collectors.joining(", ", "->[", "]"));
        return mySimpleString + parentString;
    }

    private static String objectToStringSimpleName(Object object) {
        return object.getClass().getSimpleName() + "@" + Integer.toHexString(object.hashCode());
    }
}
