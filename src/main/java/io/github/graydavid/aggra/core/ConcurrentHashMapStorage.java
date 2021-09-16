/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


/** A Storage implementation backed by a ConcurrentMap, which is used to store and memoize the results of Calls. */
public class ConcurrentHashMapStorage implements Storage {
    private final ConcurrentHashMap<Node<?, ?>, Reply<?>> calls;

    public ConcurrentHashMapStorage() {
        this.calls = new ConcurrentHashMap<>();
    }

    public ConcurrentHashMapStorage(int initialCapacity) {
        this.calls = new ConcurrentHashMap<>(initialCapacity);
    }

    @Override
    public <T> Reply<T> computeIfAbsent(Node<?, T> node, Supplier<Reply<T>> replySupplier) {
        // Suppress justification: call's keys and values are only inserted if they are type compatible
        @SuppressWarnings("unchecked")
        Reply<T> reply = (Reply<T>) calls.computeIfAbsent(node, nd -> replySupplier.get());
        return Objects.requireNonNull(reply);
    }
}
