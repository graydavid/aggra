/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A simple, kind-of visitor that allows users to execute functionality by a Node's Type. As stated in the Type javadoc,
 * this class should only be used to drive meta-data functionality that describes how a Node works (e.g. to draw a
 * diagram for a Graph, with a different shape for each Type of Node); it should *not* be used to change a Node's
 * Behavior or the Behavior of any consumer Nodes.
 * 
 * This is only a "kind-of" visitor, because Type isn't really a standard "visitable" class. Consumers don't really know
 * about the classes underling Node types, users may want different behavior for the same Type class based on name, and
 * anybody can create a new Type and so it would be impossible to collect all of the types under one visitor interface.
 * Instead, the way this class works is that users define a mapping between Type instances and the functionality they
 * want to execute against a Node for that specific instance. Since it would be impossible for any user to know about
 * all possible Type instances, users must also specify a default visitor that would be executed for any Type not in
 * that mapping. In addition, you won't find an "accept" method on either the Node or Type classes, since because
 * dispatching is done by value instead of class, those methods would be worthless.
 */
public class ByTypeVisitor<T> {
    private final Map<Type, Function<Node<?, ?>, T>> typeToVisitor;
    private final Function<Node<?, ?>, T> defaultVisitor;

    private ByTypeVisitor(Map<Type, Function<Node<?, ?>, T>> typeToVisitor, Function<Node<?, ?>, T> defaultVisitor) {
        this.typeToVisitor = Objects.requireNonNull(typeToVisitor);
        this.defaultVisitor = Objects.requireNonNull(defaultVisitor);
    }

    /**
     * @param typeToVisitor a mapping of Type instances to functionality that the user wants to execute for Node's with
     *        Types equal to that.
     * @param defaultVisitor the functionality to execute for any Type instance not in typeToVisitor
     */
    public static <T> ByTypeVisitor<T> ofVisitors(Map<Type, Function<Node<?, ?>, T>> typeToVisitor,
            Function<Node<?, ?>, T> defaultVisitor) {
        return new ByTypeVisitor<>(Map.copyOf(typeToVisitor), defaultVisitor);
    }

    /** Executes the functionality in {@link #ofVisitors(Map, Function)} that matches the node's given Type. */
    public T visit(Node<?, ?> node) {
        Function<Node<?, ?>, T> visitor = typeToVisitor.getOrDefault(node.getType(), defaultVisitor);
        return visitor.apply(node);
    }
}
