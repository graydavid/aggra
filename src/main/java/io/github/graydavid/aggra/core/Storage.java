/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.function.Supplier;


/**
 * Storage for the transient, per-call results of a node graph execution. Nodes and graphs are meant to be immutable
 * representations of algorithms. Memory is passed around between nodes during call to record and de-dupe the results.
 * 
 * Memories are hierarchical in a tree-like structure: memories can have a single super memory and several sub memories.
 * This hierarchy allows for stream-like calls: a root memory can spawn several sub memories for the same graph and
 * execute them for different inputs.
 * 
 * Node clients typically don't need to interact with Memory beyond initially creating it and passing it to the Node
 * they wish to call..
 */
public interface Storage {

    /**
     * Similar to {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}.
     * To summarize, if the node is not already associated with a reply in this storage, this method invokes the
     * replySupplier to generate the reply and then stores the reply. In addition, the method invocation is atomic so
     * that the supplier is only ever called once per node through the lifetime of the storage.n. The supplier must not
     * attempt, during its execution, to store additional values in this storage. Any deviation from these rules may
     * result in a RuntimeException.
     * 
     * Implementations are allowed to throw any RuntimeException, but if they do, the call cannot establish the mapping
     * for the Node key. In other words, exceptional calls cannot store anything in the storage for the given key.
     * 
     * @return the reply associated with the given node, regardless of whether or not it already existed before this
     *         method was called.
     */
    <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier);
}
