/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

/** An caller of nodes in a Aggra graph. */
@FunctionalInterface
public interface Caller {
    /**
     * A brief description of the caller, suitable for debugging.
     * 
     * @implSpec the default implementation calls {@link #getRole()}'s toString function..
     */
    default String getSynopsis() {
        return getRole().toString();
    }

    Role getRole();
}
