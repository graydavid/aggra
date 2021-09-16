/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.concurrent.CompletionStage;

/**
 * Houses definitions related to traveling to different Memorys: creating new and accessing existing Memories, either
 * from nothing or from an existing Memory.
 */
public class MemoryBridges {
    private MemoryBridges() {}

    /**
     * Defines how to create a new instance of a Memory for a given source Memory and input.
     * 
     * @param <SM> the type of the Memory associated with the source Memory.
     * @param <NI> the type of the input that the new Memory instance will provide.
     * @param <NM> the type of the new Memory to be created.
     */
    @FunctionalInterface
    public interface MemoryFactory<SM extends Memory<?>, NI, NM extends Memory<NI>> {
        /**
         * Creates a new memory. Each call to this method must create a new memory instance.
         * 
         * @param scope the MemoryScope the created memory will return for its input. If any additional Memorys were
         *        created within this method (e.g. ancestors of the created Memory), then they, too, should return the
         *        same scope. Clients should not save and/ore use this scope directly after this method is finished: it
         *        should only be used to create Memorys within this method call. Aggra assumes this will be true, and
         *        violating this rule could have consequences such as mismanagement of the MemoryScope lifecycle
         *        compared to its actual usage.
         * @param newInput the input the created memory will return for its input.
         * @param source the Memory used to help create the new Memory. The idea is that source may be the new Memory's
         *        ancestor, may help provide the pre-existing ancestors to create the new Memory, or may be completely
         *        unrelated to the new Memory; it all depends on how Memorys should be shared.
         */
        NM createNew(MemoryScope scope, CompletionStage<NI> newInput, SM source);

        /**
         * Converts the factoryWithInput into a {@link MemoryNoInputFactory} by binding this factory to the given
         * newInput.
         * 
         * The returned factory will throw a MisbehaviorException if the Memory it creates does not return newInput as
         * its input.
         * 
         * @param newInput the input that the returned factory should create.
         * 
         * @apiNote this method is static rather than a default in order to prevent clients from bypassing the
         *          requirement that it's supposed to check.
         */
        static <SM extends Memory<?>, NI, NM extends Memory<NI>> MemoryNoInputFactory<SM, NM> toNoInputFactory(
                MemoryFactory<SM, NI, NM> factoryWithInput, CompletionStage<NI> newInput) {
            return (scope, source) -> {
                NM newMemory = factoryWithInput.createNew(scope, newInput, source);
                requireMemoryHasInput(newMemory, newInput);
                return newMemory;
            };
        }
    }

    /**
     * Similar to {@link MemoryFactory}, but requires no input to create a Memory; requires only the source Memory. This
     * class provides for the more flexible way to create a new Memory (users don't have to care as much how the new
     * Memory will be created) but at the cost of verbosity (Memory constructors are more likely to be MemoryFactorys)
     * and abstraction (clients have to pass around factories rather than inputs, and inputs are more natural).
     * 
     * @apiNote this interface is exactly the same as {@link AncestorMemoryAccessor}; the only difference is semantics.
     *          This interface should create new Memories relative to the source, while AncestorMemoryAccessor should
     *          return pre-existing ones of the source.
     * @apiNote although this class is more flexible, MemoryFactory is more usable and so is given the honor of the more
     *          succinct class name.
     */
    @FunctionalInterface
    public interface MemoryNoInputFactory<SM extends Memory<?>, NM extends Memory<?>> {
        /**
         * Creates a new memory. Each call to this method must create a new instance of a Memory.
         * 
         * @param scope the MemoryScope the created memory will return for its input. If any additional Memorys were
         *        created within this method (e.g. ancestors of the created Memory), then they, too, should return the
         *        same scope.
         * @param source the Memory used to help create the new Memory. The idea is that source may be the new Memory's
         *        ancestor, may help provide the pre-existing ancestors to create the new Memory, or may be completely
         *        unrelated to the new Memory.
         */
        NM createNew(MemoryScope scope, SM source);
    }

    /**
     * Similar to {@link MemoryFactory}, but requires no source to create a Memory; requires only the input. This class
     * provides a way to create the first memory used to call a Graph.
     */
    @FunctionalInterface
    public interface MemoryNoSourceFactory<NI, NM extends Memory<NI>> {
        /**
         * Creates a new memory. Each call to this method must create a new instance of a Memory. The created memory
         * will return newInput for its input.
         */
        NM createNew(MemoryScope scope, CompletionStage<NI> newInput);

        /**
         * Converts the factoryWithInput into a {@link MemoryNoSourceInputFactory} by binding this factory to the given
         * newInput.
         * 
         * The returned factory will throw a Misbehavior if the Memory it creates does not return newInput as its input.
         * 
         * @param scope the MemoryScope the created memory will return for its input. If any additional Memorys were
         *        created within this method (e.g. ancestors of the created Memory), then they, too, should return the
         *        same scope.
         * @param newInput the input that the returned factory should create.
         * 
         * @apiNote this method is static rather than a default in order to prevent clients from bypassing the
         *          requirement that it's supposed to check.
         */
        static <NI, NM extends Memory<NI>> MemoryNoSourceInputFactory<NM> toNoSourceInputFactory(
                MemoryNoSourceFactory<NI, NM> factoryWithInput, CompletionStage<NI> newInput) {
            return scope -> {
                NM newMemory = factoryWithInput.createNew(scope, newInput);
                requireMemoryHasInput(newMemory, newInput);
                return newMemory;
            };
        }
    }

    /**
     * Similar to {@link MemoryNoSourceFactory}, but doesn't require an input, either, to create a new Memory. This
     * class provides for the most flexible way to create a new Memory, but there are no active usecases within Aggra
     * itself: this class is just provided as a logical continuation of existing patterns above and as a convenience to
     * clients just in case.
     */
    @FunctionalInterface
    public interface MemoryNoSourceInputFactory<NM extends Memory<?>> {
        /**
         * Creates a new memory. Each call to this method must create a new instance of a Memory.
         * 
         * @param scope the MemoryScope the created memory will return for its input. If any additional Memorys were
         *        created within this method (e.g. ancestors of the created Memory), then they, too, should return the
         *        same scope.
         */
        NM createNew(MemoryScope scope);
    }

    /**
     * Defines how to access an existing Memory that is an ancestor of a source memory.
     * 
     * @param <SM> the type of the Memory associated with the source Memory.
     * @param <AM> the type of the Memory associated with the ancestor Memory.
     * 
     * @apiNote see the apiNote in {@link MemoryNoInputFactory} about the similarity of that interface with this one and
     *          expected semantic differences.
     */
    @FunctionalInterface
    public interface AncestorMemoryAccessor<SM extends Memory<?>, AM extends Memory<?>> {
        /**
         * Accesses an ancestor memory. Each call to this method with the same source must return the same instance of
         * the ancestor Memory. The returned Memory must be accessible through some part of the hierarchy of the
         * ancestor Memories accessible from source: i.e. through some iterative number of calls to
         * {@link Memory#getParents()}.
         */
        AM getAncestorOf(SM source);
    }

    /**
     * Verifies that a Memory has a given input. This is useful because, although the javadoc specified it to be true,
     * no Memory factory actually verifies that the Memory it creates has the same input as passed to the factory.
     * 
     * @return memory if its input matches the input argument.
     * @throws MisbehaviorException if memory's input doesn't match the input argument.
     * @apiNote Although we could add a default method on each factory interface to do this verification, clients could
     *          override this to do nothing, defeating the whole purpose. Although we could make the Memory factories
     *          abstract and then enforce the requirement directly, for client ease of use, it's important that the
     *          factories remain FunctionalInterfaces.
     */
    public static <I, M extends Memory<I>> M requireMemoryHasInput(M memory, CompletionStage<I> expectedInput) {
        if (memory.getInput() == expectedInput) {
            return memory;
        }

        String message = String.format("Expected Memory with input '%s' but found input '%s' for Memory '%s'",
                expectedInput, memory.getInput(), memory);
        throw new MisbehaviorException(message);

    }

    /**
     * Verifies that a Memory has a given MemoryScope. This is useful because, although the javadoc specified it to be
     * true, no Memory factory actually verifies that the Memory it creates has the same MemoryScope as passed as input
     * to the factory.
     * 
     * @return memory if its scope matches the scope argument.
     * @throws MisbehaviorException if memory's scope doesn't match the scope argument.
     * @apiNote Although we could add a default method on each factory interface to do this verification, clients could
     *          override this to do nothing, defeating the whole purpose. Although we could make the Memory factories
     *          abstract and then enforce the requirement directly, for client ease of use, it's important that the
     *          factories remain FunctionalInterfaces.
     */
    public static <M extends Memory<?>> M requireMemoryHasScope(M memory, MemoryScope scope) {
        if (memory.getScope().equals(scope)) {
            return memory;
        }
        String message = String.format("Expected Memory '%s' to have scope '%s' but has '%s' instead", memory, scope,
                memory.getScope());
        throw new MisbehaviorException(message);
    }
}
