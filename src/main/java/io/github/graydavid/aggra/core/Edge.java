/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;

import io.github.graydavid.aggra.core.Dependencies.Dependency;

/**
 * An edge in a graph: a connection between a consumingNode at one of the dependencies it consumes.
 * 
 * @apiNote This class is not generic both because the number of parameters would be unwieldy but also because Edge is
 *          meant to *describe* graphs, not to support *behavior*.
 */
public class Edge {
    private final Node<?, ?> consumingNode;
    private final Dependency<?, ?> dependency;

    /** @throws IllegalArgumentException if dependency is not a dependency of consumingNode. */
    public Edge(Node<?, ?> consumingNode, Dependency<?, ?> dependency) {
        requireIsDependency(consumingNode, dependency);
        this.consumingNode = consumingNode;
        this.dependency = dependency;
    }

    private static void requireIsDependency(Node<?, ?> consumingNode, Dependency<?, ?> dependency) {
        if (!consumingNode.getDependencies().contains(dependency)) {
            String message = String.format(
                    "Expected consuming node '%s' to have '%s' as a dependency, but only found the following: '%s'",
                    consumingNode, dependency, consumingNode.getDependencies());
            throw new IllegalArgumentException(message);
        }
    }

    public Node<?, ?> getConsumingNode() {
        return consumingNode;
    }

    public Dependency<?, ?> getDependency() {
        return dependency;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Edge)) {
            return false;
        }

        Edge other = (Edge) object;
        return Objects.equals(this.consumingNode, other.consumingNode)
                && Objects.equals(this.dependency, other.dependency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumingNode, dependency);
    }

    @Override
    public String toString() {
        return "{" + consumingNode + "}->{" + dependency + "}";
    }
}
