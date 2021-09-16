/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Objects;

import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;

/** Houses definitions related to dependencies that one node has on another. */
public class Dependencies {
    private Dependencies() {}

    /** Describes whether or not a Dependency is primed during the consuming Node's priming phase. */
    public enum PrimingMode {
        PRIMED,
        UNPRIMED
    }

    /**
     * Describes the cardinality of the relationship between a consumer Node's call and the Replys it can receive from a
     * Dependency. E.g. for SameMemoryDependency, the cardinality will always be one-to-one, because for a given
     * consumer call, it will always retrieve the same Reply from its dependency. Meanwhile, for NewMemoryDependency,
     * the cardinality will be one-to-many, because for a given consumer call, it can retrieve multiple Replys from its
     * dependency, one per new Memory it creates. Lastly, for AncestorMemoryDependency, the cardinality will be
     * many-to-one, because many different consumer calls (ephasis: for the same Node) will retrieve the same Reply from
     * its dependency.
     * 
     * The keep track of this property is important, at the very least, for optimizations made in
     * {@link DependencyCallingDevice#ignore(Reply)} regarding when to escalate a consuming node's ignoring a dependency
     * call into a full-fledged cancellation.
     */
    public enum ConsumerCallToDependencyReplyCardinality {
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_ONE;
    }

    /*** A base class describing properties of a consuming Node's dependency on another node. */
    public abstract static class Dependency<M extends Memory<?>, T> {
        private final Node<M, T> node;
        private final PrimingMode primingMode;
        // The following are derived, inherent properties: their values are determined solely by the subclass type. This
        // is why you won't find these properties in equals or hashCode
        private final ConsumerCallToDependencyReplyCardinality callToReplyCardinality;

        private Dependency(Node<M, T> node, PrimingMode primingMode,
                ConsumerCallToDependencyReplyCardinality callToReplyCardinality,
                boolean dependencyReplyBelongsExclusivelyToConsumerCall) {
            this.node = Objects.requireNonNull(node);
            this.primingMode = Objects.requireNonNull(primingMode);
            this.callToReplyCardinality = callToReplyCardinality;
        }

        /** The dependency node: the node on which the consuming node has a dependency. */
        public final Node<M, T> getNode() {
            return node;
        }

        public final PrimingMode getPrimingMode() {
            return primingMode;
        }

        public ConsumerCallToDependencyReplyCardinality getConsumerCallToDependencyReplyCardinality() {
            return callToReplyCardinality;
        }

        @Override
        public final boolean equals(Object object) {
            if (object == null) {
                return false;
            }
            if (!getClass().equals(object.getClass())) {
                return false;
            }
            Dependency<?, ?> other = (Dependency<?, ?>) object;
            return Objects.equals(this.node, other.node) && Objects.equals(this.primingMode, other.primingMode);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(node, primingMode);
        }

        @Override
        public final String toString() {
            return getClass().getSimpleName() + "[" + getPrimingMode() + " - (" + getNode().getSynopsis() + ")]";
        }

        /**
         * Visitor pattern for Dependencies: allows client to do something per Dependency-subclass without having
         * repeated, class-based switch statements in their code.
         */
        public abstract <R> R accept(Visitor<R> visitor);
    }

    /**
     * Describes a dependency that is called in the same memory instance as its consumer.
     * 
     * @see {@link DependencyCallingDevice#call(SameMemoryDependency)}.
     * @apiNote same memory *instance* may seem arbitrary, but it's very useful. As an alternative, we could have said
     *          same memory *type* and then allowed these dependencies to participate in new memory creation and
     *          ancestor memory accesses. By limiting it to "instance" instead of "type", though, we get two benefits.
     *          First off, conceptually, it's easier to understand if two neighbor nodes of the same memory type always
     *          access the same memory instance: you don't have to worry about any arbitrary changes. Secondly, given a
     *          single consumer node, "same instance" ensures that only one NodeReply could consume the dependency
     *          NodeReply... meaning that if that if a consumer NodeReply ignores a dependency NodeReply, that
     *          dependency NodeReply can be cancelled right away. This would not be true for "same type". See
     *          {@link DependencyCallingDevice#ignore(NodeReply)} for more details. Furthermore, there are workarounds
     *          you can do if you'd like to have same-memory types participate in new memory creation or ancestor memory
     *          accesses: in short, move the called Node and all of its dependencies to another Memory.
     */
    public static class SameMemoryDependency<M extends Memory<?>, T> extends Dependency<M, T> {
        private SameMemoryDependency(Node<M, T> node, PrimingMode primingMode) {
            super(node, primingMode, ConsumerCallToDependencyReplyCardinality.ONE_TO_ONE, false);
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitSame(this);
        }
    }

    /**
     * Returns a new SameMemoryDependency targeting the given node with the given primingMode.
     */
    public static <M extends Memory<?>, T> SameMemoryDependency<M, T> newSameMemoryDependency(Node<M, T> node,
            PrimingMode primingMode) {
        return new SameMemoryDependency<>(node, primingMode);
    }

    /**
     * Describes a dependency that is called in the new memory created by the consuming node. These types of
     * dependencies are always unprimed.
     * 
     * @see {@link DependencyCallingDevice#createMemoryAndCall(io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory, java.util.concurrent.CompletionStage, NewMemoryDependency)}
     *      and
     *      {@link DependencyCallingDevice#createMemoryNoInputAndCall(io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory, NewMemoryDependency)}.
     */
    public static class NewMemoryDependency<M extends Memory<?>, T> extends Dependency<M, T> {
        private NewMemoryDependency(Node<M, T> node) {
            super(node, PrimingMode.UNPRIMED, ConsumerCallToDependencyReplyCardinality.ONE_TO_MANY, true);
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitNew(this);
        }
    }

    /**
     * Returns a new NewMemoryDependency targeting the given node. The returned value will have a PrimingMode of
     * UNPRIMED, since only {@link SameMemoryDependency}s are allowed to be primed.
     */
    public static <M extends Memory<?>, T> NewMemoryDependency<M, T> newNewMemoryDependency(Node<M, T> node) {
        return new NewMemoryDependency<>(node);
    }

    /**
     * Describes a dependency that is called in the consumer's memory's ancestor. These types of dependencies are always
     * unprimed.
     * 
     * @see {@link DependencyCallingDevice#accessAncestorMemoryAndCall(io.github.graydavid.aggra.core.MemoryBridges.AncestorMemoryAccessor, AncestorMemoryDependency)}.
     */
    public static class AncestorMemoryDependency<M extends Memory<?>, T> extends Dependency<M, T> {
        private AncestorMemoryDependency(Node<M, T> node) {
            super(node, PrimingMode.UNPRIMED, ConsumerCallToDependencyReplyCardinality.MANY_TO_ONE, false);
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitAncestor(this);
        }
    }

    /**
     * Returns a new AncestorMemoryDependency targeting the given node. The returned value will have a PrimingMode of
     * UNPRIMED, since only {@link SameMemoryDependency}s are allowed to be primed.
     */
    public static <M extends Memory<?>, T> AncestorMemoryDependency<M, T> newAncestorMemoryDependency(Node<M, T> node) {
        return new AncestorMemoryDependency<>(node);
    }

    /**
     * Visitor pattern for Dependencies: allows client to do something per Dependency-subclass without having repeated,
     * class-based switch statements in their code.
     * 
     * @implSpec by default, each visit method calls {@link Visitor#defaultVisit(Dependency)}, which does nothing and
     *           returns null. This has two benefits:<br>
     *           1. Users who don't care about the specific Dependency implementation can override only a single
     *           method.<br>
     *           2. If implementations want to be informed of a new type of Dependency added in the future, they can
     *           override defaultVisit to throw an UnsupportedOperationException. The default implementation doesn't do
     *           this, because it can't be sure that throwing an exception to discover new Dependency types is the
     *           appropriate thing to do for every usecase.
     */
    public interface Visitor<T> {
        default T defaultVisit(Dependency<?, ?> dependency) {
            return null;
        }

        default T visitSame(SameMemoryDependency<?, ?> dependency) {
            return defaultVisit(dependency);
        }

        default T visitNew(NewMemoryDependency<?, ?> dependency) {
            return defaultVisit(dependency);
        }

        default T visitAncestor(AncestorMemoryDependency<?, ?> dependency) {
            return defaultVisit(dependency);
        }
    }
}
