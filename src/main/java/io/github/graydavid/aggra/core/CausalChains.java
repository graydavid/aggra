/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Utility to help accessing throwables in causal chains. Example:
 * https://github.com/google/guava/commit/826bda60d3c84c8142da6f0e8576c68540d51891#diff-0c476aea3a671287e98f0e708b9ad9d7
 */
class CausalChains {
    private CausalChains() {}

    /** Returns the first throwable in the causal chain matching the given predicate. */
    public static Optional<Throwable> findFirst(Throwable base, Predicate<Throwable> matches) {
        Objects.requireNonNull(base);

        // Guard against malicious overrides of Throwable.equals by using a Set with identity equality semantics.
        Set<Throwable> alreadySeen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = base; current != null && alreadySeen.add(current); current = current.getCause()) {
            if (matches.test(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }
}
