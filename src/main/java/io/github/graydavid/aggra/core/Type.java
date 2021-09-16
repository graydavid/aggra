/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * Provides an illuminating description of "how a Node works". Type is a way of grouping Nodes into broad categories
 * based on *how* they do what they do. Whereas the Role is the *what* a Node does, the Type is the *how* it does it.
 * Type should *not* be used to drive a Node's Behavior or the Behavior of any consumers: it's purpose is merely
 * descriptive.
 * 
 * Type is a glorified wrapper around a String name. One usage for type is in the toString representation of a Node.
 * Simply calling toString will then give the user an idea of how the Node does what it's doing (whereas Role would more
 * answer the question of *what* the node is doing). If this were the only use, then Type could just be a String inside
 * of Node itself (which it was at one point in the Aggra design process). Another usage, however, involves creating
 * more elaborate representations of how the Node works (e.g. a visual diagram). In this usecase, the user would maybe
 * want to present a different visualization for each Type of Node. To help support this, Type is class-based. It's also
 * abstract. It provides a Generic implementation where equality is effectively based on the name alone. To avoid
 * clashes with different providers of Nodes (who don't know about each other and so could accidentally choose the same
 * name), those providers can also create their own Type implementations. By exposing those implementations, providers
 * can enable users to identify and perhaps better describe those Nodes with custom representations. The general advice
 * to providers for choosing when to share or use a different Type implementation for different ways of creating a node
 * is to consider the example usecase of showing a diagram: would you want a different visual representation for the two
 * Nodes or would that be too busy? If yes, use different implementations; if not, then share.
 */
public abstract class Type {
    private final String name;

    protected Type(String name) {
        this.name = requireValidName(name);
    }

    private static String requireValidName(String name) {
        if (name.isBlank()) {
            StringJoiner codePoints = name.codePoints()
                    .collect(() -> new StringJoiner(", ", "[", "]"),
                            (joiner, point) -> joiner.add(String.valueOf(point)), StringJoiner::merge);
            throw new IllegalArgumentException(
                    "Type names must not be blank but found whitespace character in code points: " + codePoints);
        }
        return name;
    }

    @Override
    public final String toString() {
        return name;
    }

    @Override
    public final boolean equals(Object object) {
        if (object == null) {
            return false;
        }
        if (!getClass().equals(object.getClass())) {
            return false;
        }

        Type other = (Type) object;
        return Objects.equals(name, other.name);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(name);
    }

    /**
     * Creates a generic Type. Generic Types will compare equal to other generic Types with the same name, and unequal
     * to all other Types. As such, while this is a simple way to specify a Node's type, it's also brittle in that two
     * users could happen to choose the same name for a generic Type, even though they work completely differently. If
     * this usecase matters, it's recommended that users derive new Types by inheriting from Type. (Types only compare
     * equally to other Types with the same class and name.)
     * 
     * @throws NullPointerException if name is null
     * @throws IllegalArgumentException if name is blank, as per {@link String#isBlank()}.
     */
    public static Type generic(String name) {
        return new GenericType(name);
    }

    private static class GenericType extends Type {
        private GenericType(String name) {
            super(name);
        }
    }

    /**
     * Checks that this Type is compatible with the given TypeInstance. The main use of this concept is to allow Type
     * providers a mechanism to detect counterfeits. Type providers will often (always?) provide a static Type object to
     * allow users a way to switch behavior based on the actual Type (see {@link ByTypeVisitor}. Doing this also allows
     * users to set a Node's Type to that static instance directly, implying that the Node came from the Type provider,
     * even though it didn't. To avoid that, Type providers can define their own private TypeInstance and override this
     * method. Node's builders will call this method whenever the Type and TypeInstance are set. Returning false if the
     * TypeInstance doesn't match the Type provider's private one will make the Node builder reject the type, making it
     * impossible for users to use the static Type themselves. The default version of this method returns true, which
     * allows Type providers who don't care about this mechanism a way to opt-out.
     */
    public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
        return true;
    }
}
