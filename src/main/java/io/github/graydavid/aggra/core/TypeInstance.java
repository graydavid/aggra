/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

/**
 * Describes {@link Type} information about a Node instance. Compare this to Type itself, which is constant across Node
 * instances. This concept is useful for two reasons:<br>
 * 1. It gives Type providers a mechanism to detect counterfeits. See
 * {@link Type#isCompatibleWithTypeInstance(TypeInstance)}.<br>
 * 2. It gives Type providers a way to store extra type information about the specific Node instance. E.g. this could
 * provide a consistent, targeted way for Type providers to access specific Node dependencies by function.
 */
public abstract class TypeInstance {
    private static final TypeInstance DEFAULT = new TypeInstance() {};

    /**
     * Returns a TypeInstance representing a default, non-specific value for when users don't want to take advantage of
     * TypeInstance features.
     */
    public static TypeInstance defaultValue() {
        return DEFAULT;
    }
}
