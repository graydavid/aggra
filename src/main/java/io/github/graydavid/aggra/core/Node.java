/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CompositeCancelSignal;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionBehaviorResponse;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelActionException;
import io.github.graydavid.aggra.core.Behaviors.InterruptClearingException;
import io.github.graydavid.aggra.core.CallObservers.ObservationFailureObserver;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.CallObservers.ObserverAfterStop;
import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;

/**
 * Nodes in a Aggra. Multiple nodes together form a dependency graph and allow the modeling and optimal execution of
 * certain types of programs. A Node is characterized by the logic/behavior that makes up "what the node does", its
 * memoized nature, and the lack of external input arguments. .
 * 
 * Each Node has an intrinsic Behavior that defines what the Node does. When called, a Node will run its Behavior.
 * 
 * The results of Nodes between calls are memoized per {@link Memory}. Memoization allows nodes to be called in a
 * distributed, decoupled fashion in an optimized way, both in terms of execution time and count. Memoization is
 * achieved through the relationship with {@link Memory}; nodes, themselves, are stateless.
 * 
 * The lack of external input arguments makes memoization safer/less likely to fail. Instead, nodes receive their needed
 * inputs through relationships with other dependency nodes. One way to classify these relationships is by the phase in
 * which a dependency node is first called.
 * 
 * A node's call happens in three phases: a priming phase, a Behavior phase, and a waiting phase. Dependencies that
 * indicate they are to be primed are run during the priming phase, and their result is processed according to
 * {@link PrimingFailureStrategy}. Once those dependencies are finished, the Behavior phase starts. In the Behavior
 * phase, a node's intrinsic behavior is run. This behavior may call the primed dependencies again (safe in the
 * knowledge that the results will be memoized and so the call returning immediately) or the unprimed dependencies
 * (which would be called from the node for the first time). In the waiting phase, the Node waits for its dependencies
 * to complete (as according to its {@link DependencyLifetime}. This three-phase scheme simplifies the implementation of
 * Behaviors while allowing the flexibility of having optional dependencies or deciding at runtime how many times to
 * call a dependency (passing it its own Memory) for iteration.
 * 
 * Clients typically won't create Nodes directly. Instead, they'll use the convenience factories in the
 * io.github.graydavid.aggra.nodes package to create specific types of nodes. Either way, to understand a Node's
 * properties, see {@link Builder}'s methods.
 * 
 * @apiNote Why is there a memoryClass but no outputClass? The way that Memory works, we know that there will be a
 *          user-provided Class for Memory. This information can be used to do sanity checks on the graph (e.g. make
 *          sure that Memory ancestry is consistent). For output, these can be arbitrary types, even generics. That
 *          means a simple Class wouldn't always do. I *could* implement a TypeReference-like class for these cases...
 *          but I don't see much use for an outputClass. It would absolutely be great for describing a Graph's
 *          structure. I just don't see a use for it beyond that. Summary: memoryClass is easy to implement and provides
 *          useful functional checks; while outputClass would be difficult to implement, make Aggra more difficult to
 *          use, and only provide descriptive benefits.
 */
public class Node<M extends Memory<?>, T> implements Caller {
    private final Type type;
    private final TypeInstance typeInstance;
    private final Role role;
    private final Class<M> memoryClass;
    private final Set<SameMemoryDependency<M, ?>> primedDependencies; // Derived, frequently-used value
    private final Set<? extends Dependency<?, ?>> dependencies;
    private volatile Set<Node<?, ?>> dependencyNodes; // Derived, infrequently-used value
    private final Conduct<M, T> conductAfterPrimingDependencies;
    private final PrimingFailureStrategy primingFailureStrategy;
    private final DependencyLifetime declaredDependencyLifetime;
    private final DependencyLifetime minimumDependencyLifetime; // Derived, frequently-used value
    private final CancelMode cancelMode;
    private final boolean shadowSupportsActiveCancelHooks; // Derived, frequently-used value
    private final ExceptionStrategy exceptionStrategy;
    private final Collection<ForNodeGraphValidatorFactory> graphValidatorFactories;

    private Node(Builder<M, ?> builder, Conduct<M, T> conductAfterPrimingDependencies) {
        this.type = Objects.requireNonNull(builder.type);
        this.typeInstance = Objects.requireNonNull(builder.typeInstance);
        this.role = Objects.requireNonNull(builder.role);
        this.memoryClass = Objects.requireNonNull(builder.memoryClass);
        this.primedDependencies = Set.copyOf(builder.primedDependencies);
        boolean allDependenciesArePrimed = builder.unprimedDependencies.isEmpty();
        this.dependencies = allDependenciesArePrimed ? this.primedDependencies
                : calculateAllDirectDependencies(builder.primedDependencies, builder.unprimedDependencies);
        this.conductAfterPrimingDependencies = conductAfterPrimingDependencies;
        this.primingFailureStrategy = builder.primingFailureStrategy;
        this.declaredDependencyLifetime = builder.dependencyLifetime;
        this.minimumDependencyLifetime = calculateMinimumDependencyLifetime(builder.dependencyLifetime,
                this.dependencies);
        this.cancelMode = Objects.requireNonNull(builder.cancelMode);
        this.shadowSupportsActiveCancelHooks = calculateShadowSupportsActiveCancellationHooks(builder.cancelMode,
                this.dependencies);
        this.exceptionStrategy = builder.exceptionStrategy;
        this.graphValidatorFactories = List.copyOf(builder.graphValidatorFactories);
    }

    private static <M extends Memory<?>> Set<Dependency<?, ?>> calculateAllDirectDependencies(
            Set<SameMemoryDependency<M, ?>> primedDependencies, Set<Dependency<?, ?>> unprimedDependencies) {
        HashSet<Dependency<?, ?>> allDependencies = new HashSet<>(primedDependencies);
        allDependencies.addAll(unprimedDependencies);
        return Set.copyOf(allDependencies);
    }

    private static DependencyLifetime calculateMinimumDependencyLifetime(DependencyLifetime declaredLifetime,
            Collection<? extends Dependency<?, ?>> allDirectDependencies) {
        Collection<DependencyLifetime> dependencyMinimumLifetimes = allDirectDependencies.stream()
                .map(Dependency::getNode)
                .map(Node::getMinimumDependencyLifetime)
                .collect(Collectors.toSet());
        return declaredLifetime.minimumLifetimeWithDependencies(dependencyMinimumLifetimes);
    }

    private static boolean calculateShadowSupportsActiveCancellationHooks(CancelMode cancelMode,
            Collection<? extends Dependency<?, ?>> allDirectDependencies) {
        if (cancelMode.supportsActiveHooks()) {
            return true;
        }
        return allDirectDependencies.stream().anyMatch(dep -> dep.getNode().getCancelMode().supportsActiveHooks());
    }

    /**
     * Starts construction of a "communal node": a node that can collaborate with other dependency nodes to produce a
     * result. Users can call build on the resulting builder an unlimited number of times.
     * 
     * The builder returned form this method is good for only one use. That is, as soon as you call the build method,
     * calling any other method on this builder will throw an IllegalStateException.
     * 
     * @apiNote Why only one-use? First, this makes sense for all of the Type providers in
     *          io.github.graydavid.aggra.nodes: not all of the builder options are exposed and it wouldn't make sense
     *          to transfer state from one build over to the next. Second, this helps avoid bugs where a user
     *          accidentally continues using a builder after creation. This is made more likely by the non-chaining way
     *          that dependencies are declared. Lastly, I just can't think of a good use case for supporting multiple
     *          use. Adding this feature at a later time should be possible, if desired, with something like copy
     *          constructors.
     */
    public static <M extends Memory<?>> CommunalBuilder<M> communalBuilder(Class<M> memoryClass) {
        return new CommunalBuilder<>(memoryClass);
    }

    /**
     * Starts construction of an "input node": a node that simply retrieves the input from {@link Memory} to produce a
     * result. The builder returned form this method is good for only one use. That is, as soon as you call the build
     * method, calling any other method on this builder will throw an IllegalStateException.
     */
    public static <M extends Memory<T>, T> InputBuilder<M, T> inputBuilder(Class<M> memoryClass) {
        return new InputBuilder<>(memoryClass);
    }

    /**
     * A builder for creating "communal nodes". This builder is good for only one use. That is, as soon as you call a
     * build method, calling any other method on this builder will throw an IllegalStateException.
     * 
     * @param <M> the type of the Memory for the resulting node.
     * 
     * @apiNote modeling dependencies at all is useful in itself for a couple of different reasons. First, forcing them
     *          to be specified at Node creation time helps prevent circular references (e.g. node A calls node B calls
     *          node A again). This doesn't make sense, because node A would be depending on itself to finish before it
     *          could finish. Second, specifying dependencies helps with debugging/understanding, as full graphs can be
     *          printed out without having to execute a call.
     * @apiNote for all of the dependency-adding methods: it's definitely inconvenient that this Builder method doesn't
     *          return a Builder and so breaks any type of chaining that the user wants to do. This is unfortunate;
     *          however, the tradeoffs were just too good. First, returning a Dependency object that can be used to make
     *          dependency calls helps ensure that users register their dependencies before trying to call them. Second,
     *          forcing users to describe their dependency usage patterns helps detect errors more quickly (e.g. trying
     *          to create a new or access an ancestor Memory of the same type as the current Node's memory is
     *          forbidden). Similarly, describing usage patterns allows for optimizations (e.g. being able to tell when
     *          a call to {@link DependencyCallingDevice#ignore(Reply)} can be escalated to a cancellation right away).
     *          Plus, overall, part of this cost is offset by having common Node implementations in the
     *          io.github.graydavid.aggra.nodes package, such that commons users won't even know that this pattern
     *          exists.
     */
    public static final class CommunalBuilder<M extends Memory<?>> extends Builder<M, CommunalBuilder<M>> {
        private CommunalBuilder(Class<M> memoryClass) {
            super(memoryClass);
        }

        @Override
        public CommunalBuilder<M> type(Type type) {
            return super.type(type);
        }

        @Override
        public CommunalBuilder<M> typeTypeInstance(Type type, TypeInstance typeInstance) {
            return super.typeTypeInstance(type, typeInstance);
        }

        @Override
        public CommunalBuilder<M> primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy) {
            return super.primingFailureStrategy(primingFailureStrategy);
        }

        @Override
        public CommunalBuilder<M> primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy,
                DependencyLifetime dependencyLifetime) {
            return super.primingFailureStrategy(primingFailureStrategy, dependencyLifetime);
        }

        @Override
        public CommunalBuilder<M> dependencyLifetime(DependencyLifetime dependencyLifetime) {
            return super.dependencyLifetime(dependencyLifetime);
        }

        @Override
        public CommunalBuilder<M> exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            return super.exceptionStrategy(exceptionStrategy);
        }

        /**
         * Adds a dependency that this node will prime before executing its intrinsic behavior. Together with unprimed
         * dependencies, these properties define all of this node's dependencies. These types of nodes are implicitly
         * SameMemoryDependencys.
         * 
         * @return a SameMemoryDependency that can be used to make {@link DependencyCallingDevice} calls to "node" in
         *         this Node's intrinsic behavior. If this node was added previously by calling this same method, then
         *         an equal Dependency is returned. Both the previous and current response from this method can be used
         *         just the same in the Node's intrinsic behavior, but there is no guarantee that they're the same
         *         instance. Regardless of the number of calls, "node" will only be a single dependency: multiple calls
         *         to this method with the same "node" will *not* add multiple dependencies.
         * @throws IllegalArgumentException if this node has already been added as an
         *         {@link #sameMemoryUnprimedDependency(Node)}. Although there's no technical harm in doing so, having
         *         the same SameMemoryDependency be both primed and unprimed on the same consumer accomplishes nothing
         *         and could be indicative of a misunderstanding.
         * 
         * @apiNote this concept of primed dependencies is useful for two reasons. First, it allows easier
         *          implementation of the node's intrinsic behavior: that behavior can just access the results of these
         *          dependencies without worrying whether or not they're completed, yet (since they will be guaranteed
         *          to be completed when that intrinsic behavior is run). (That said, behaviors can also execute a
         *          similar priming approach themselves; modeling one way or the other is perfectly fine either way; but
         *          providing first-class support is convenient.) Second, since primed dependencies are always called,
         *          this allows for easier tracking and checks of those calls (e.g. to handle exceptions and make sure
         *          that illegal calls aren't being made).
         * @apiNote all primed dependencies must be associated with the same memory as the node itself: they can't be
         *          associated with ancestor- or arbitrary new-Memorys. The reason is that memoization only covers the
         *          calling of another node: it doesn't cover accessing to an ancestor or creating a new Memory. So,
         *          there's no guarantee that priming calls and calls made inside of a Node's intrinsic behavior would
         *          be the same for such dependencies.
         */
        public <T> SameMemoryDependency<M, T> primedDependency(Node<M, T> node) {
            return internalPrimedDependency(node);
        }

        /**
         * Adds a same-memory-instance-calling dependency that this node will not prime before executing its intrinsic
         * behavior.
         * 
         * @return a SameMemoryDependency that can be used to make {@link DependencyCallingDevice} calls to "node" in
         *         this Node's intrinsic behavior. If this node was added previously by calling this same method, then
         *         an equal Dependency is returned. Both the previous and current response from this method can be used
         *         just the same in the Node's intrinsic behavior, but there is no guarantee that they're the same
         *         instance. Regardless of the number of calls, "node" will only be a single dependency: multiple calls
         *         to this method with the same "node" will *not* add multiple dependencies.
         * @throws IllegalArgumentException if this node has already been added as an {@link #primedDependency(Node)}.
         *         Although there's no technical harm in doing so, having the same SameMemoryDependency be both primed
         *         and unprimed on the same consumer accomplishes nothing and could be indicative of a misunderstanding.
         */
        public <T> SameMemoryDependency<M, T> sameMemoryUnprimedDependency(Node<M, T> node) {
            return internalSameMemoryUnprimedDependency(node);
        }

        /**
         * Adds a dependency such that this Node's intrinsic behavior can create new Memorys from its Memory and call
         * the dependency node within that new Memory. These types of nodes are implicitly unprimed.
         * 
         * Note: although doing so would be highly unusual, the same dependency can also be added as a
         * {@link #ancestorMemoryDependency(Node)} as well. In such a case, the built node would be consuming the
         * dependency node in two different ways, represented by two different Dependencys.
         * 
         * @return a NewMemoryDependency that can be used to make {@link DependencyCallingDevice} calls to "node" in
         *         this Node's intrinsic behavior. If this node was added previously by calling this same method, then
         *         an equal Dependency is returned. Both the previous and current response from this method can be used
         *         just the same in the Node's intrinsic behavior, but there is no guarantee that they're the same
         *         instance. Regardless of the number of calls, "node" will only be a single dependency: multiple calls
         *         to this method with the same "node" will *not* add multiple dependencies.
         * @throws IllegalArgumentException if node's memory class is the same as the memory class of the Node that's
         *         current being built. Instead, for Nodes like these, you should use the
         *         {@link SameMemoryDependency}-related methods to add the dependency instead.
         * 
         * @param <NM> the type of the new Memory that will be created.
         */
        public <NM extends Memory<?>, T> NewMemoryDependency<NM, T> newMemoryDependency(Node<NM, T> node) {
            return internalNewMemoryDependency(node);
        }

        /**
         * Adds a dependency such that this Node's intrinsic behavior can access its Memory's ancestor Memorys and call
         * the dependency node within that ancestor Memory. These types of nodes are implicitly unprimed.
         * 
         * Note: although doing so would be highly unusual, the same dependency can also be added as a
         * {@link #newMemoryDependency(Node)} as well. In such a case, the built node would be consuming the dependency
         * node in two different ways, represented by two different Dependencys.
         * 
         * @return an AncestorMemoryDependency that can be used to make {@link DependencyCallingDevice} calls to "node"
         *         in this Node's intrinsic behavior. If this node was added previously by calling this same method,
         *         then an equal Dependency is returned. Both the previous and current response from this method can be
         *         used just the same in the Node's intrinsic behavior, but there is no guarantee that they're the same
         *         instance. Regardless of the number of calls, "node" will only be a single dependency: multiple calls
         *         to this method with the same "node" will *not* add multiple dependencies.
         * @throws IllegalArgumentException if this node has already been added via a different builder method on this
         *         Builder.
         * @throws IllegalArgumentException if node's memory class is the same as the memory class of the Node that's
         *         current being built. Instead, for Nodes like these, you should use the
         *         {@link SameMemoryDependency}-related methods to add the dependency instead.
         * 
         * @param <AM> the type of the ancestor Memory that will be accessed.
         */
        public <AM extends Memory<?>, T> AncestorMemoryDependency<AM, T> ancestorMemoryDependency(Node<AM, T> node) {
            return internalAncestorMemoryDependency(node);
        }

        /**
         * Clears all dependencies currently associated with this Builder: primed, unprimed; same memory, ancestor, new;
         * etc... it doesn't matter... all dependencies are cleared.
         */
        public CommunalBuilder<M> clearDependencies() {
            return internalClearDependencies();
        }

        /**
         * Builds the node, which will run a plain-old, standard Behavior. The returned node has
         * {@link CancelMode#DEFAULT}, which does *not* support the Reply signal's passive hook. See the javadoc there
         * for why and what alternatives you can do if you want that support.
         * 
         * @param behaviorAfterPrimingDependencies the behavior to be called after priming all primedDependencies.
         */
        public <T> Node<M, T> build(Behavior<M, T> behaviorAfterPrimingDependencies) {
            requireHasNotBuilt();
            setHasBuilt();

            cancelMode(CancelMode.DEFAULT);
            return new Node<>(this, new BehaviorCommunalConduct<>(behaviorAfterPrimingDependencies));
        }

        /**
         * Builds the node, which will run a Behavior that's provided a composite cancel signal.
         * 
         * @param behaviorAfterPrimingDependencies the behavior to be called after priming all primedDependencies.
         */
        public <T> Node<M, T> buildWithCompositeCancelSignal(
                BehaviorWithCompositeCancelSignal<M, T> behaviorAfterPrimingDependencies) {
            requireHasNotBuilt();
            setHasBuilt();

            cancelMode(CancelMode.COMPOSITE_SIGNAL);
            return new Node<>(this,
                    new BehaviorWithCompositeCancelSignalCommunalConduct<>(behaviorAfterPrimingDependencies));
        }

        /**
         * Builds the node, which will run a Behavior that's provided a composite cancel signal... and has a custom
         * cancel action.
         * 
         * @param behaviorAfterPrimingDependencies the behavior to be called after priming all primedDependencies.
         */
        public <T> Node<M, T> buildWithCustomCancelAction(
                BehaviorWithCustomCancelAction<M, T> behaviorAfterPrimingDependencies) {
            requireHasNotBuilt();
            setHasBuilt();

            CancelMode cancelMode = behaviorAfterPrimingDependencies.cancelActionMayInterruptIfRunning()
                    ? CancelMode.INTERRUPT
                    : CancelMode.CUSTOM_ACTION;
            cancelMode(cancelMode);
            return new Node<>(this,
                    new BehaviorWithCustomCancelActionCommunalConduct<>(behaviorAfterPrimingDependencies));
        }

        protected CommunalBuilder<M> getThis() {
            return this;
        }
    }

    /**
     * An abstract Builder for creating a Node. Child builders can extend this Builder to reuse common functionality.
     */
    private abstract static class Builder<M extends Memory<?>, B extends Builder<M, B>> {
        private boolean hasBuilt;
        private final Class<M> memoryClass;
        private Type type;
        private TypeInstance typeInstance;
        private Role role;
        private final Set<SameMemoryDependency<M, ?>> primedDependencies = new HashSet<>();
        private final Set<Dependency<?, ?>> unprimedDependencies = new HashSet<>();
        private final Map<Node<M, ?>, SameMemoryDependency<M, ?>> seenSameNodeToDependency = new HashMap<>();
        private PrimingFailureStrategy primingFailureStrategy = PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE;
        private DependencyLifetime dependencyLifetime = DependencyLifetime.NODE_FOR_DIRECT;
        private CancelMode cancelMode;
        private ExceptionStrategy exceptionStrategy = ExceptionStrategy.SUPPRESS_DEPENDENCY_FAILURES;
        private final Collection<ForNodeGraphValidatorFactory> graphValidatorFactories = new ArrayList<>();

        Builder(Class<M> memoryClass) {
            this.hasBuilt = false;
            this.memoryClass = Objects.requireNonNull(memoryClass);
        }

        protected abstract B getThis();

        /** Shorthand for {@link #typeTypeInstance(Type, TypeInstance)} with {@link TypeInstance#defaultValue()}. */
        protected B type(Type type) {
            return typeTypeInstance(type, TypeInstance.defaultValue());
        }

        /**
         * Sets both the Type and TypeInstance.
         * 
         * @throws IllegalArgumentException if type and typeInstance are not compatible, as per
         *         {@link Type#isCompatibleWithTypeInstance(TypeInstance)}.
         */
        protected B typeTypeInstance(Type type, TypeInstance typeInstance) {
            requireHasNotBuilt();
            requireTypeTypeInstanceCompatible(type, typeInstance);
            this.type = Objects.requireNonNull(type);
            this.typeInstance = Objects.requireNonNull(typeInstance);
            return getThis();
        }

        /** Checks that this builder has not yet been built and throws an IllegalStateException if it has. */
        public void requireHasNotBuilt() {
            if (hasBuilt) {
                throw new IllegalStateException("Builders can't be used further after they've built.");
            }
        }

        protected void requireTypeTypeInstanceCompatible(Type type, TypeInstance typeInstance) {
            if (!type.isCompatibleWithTypeInstance(typeInstance)) {
                String message = String.format("Type '%s' is not compatible with TypeInstance '%s'", type,
                        typeInstance);
                throw new IllegalArgumentException(message);
            }
        }

        protected void setHasBuilt() {
            hasBuilt = true;
        }

        /**
         * Describes the role this Node is fulfilling in modeling and executing a Aggra (e.g.
         * "calls-service-X-and-then-transforms-to-response-Y"). This is *what* the Node does.
         */
        public B role(Role role) {
            requireHasNotBuilt();
            this.role = Objects.requireNonNull(role);
            return getThis();
        }

        /**
         * Defines how to treat failures in the priming phase. The default value is
         * {@link PrimingFailureStrategy#WAIT_FOR_ALL_CONTINUE}; see that javadoc for reasons why.
         * 
         * @throws IllegalArgumentException if primingFailureStrategy is not compatible with the pre-existing
         *         dependencyLifetime.
         */
        protected B primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy) {
            requireHasNotBuilt();
            primingFailureStrategy.requireCompatibleWithDependencyLifetime(dependencyLifetime);
            this.primingFailureStrategy = Objects.requireNonNull(primingFailureStrategy);
            return dependencyLifetime(dependencyLifetime);
        }

        /**
         * Same as {@link #primingFailureStrategy(PrimingFailureStrategy)}, except this method also sets dependency
         * lifetime at the same time. The reason is that some PrimingFailureStrategys are only compatible with select
         * DependencyLifetimes. So, sometimes, it's convenient to set both at the same time.
         * 
         * @throws IllegalArgumentException if primingFailureStrategy is not compatible with the provided
         *         dependencyLifetime.
         */
        protected B primingFailureStrategy(PrimingFailureStrategy primingFailureStrategy,
                DependencyLifetime dependencyLifetime) {
            requireHasNotBuilt();
            primingFailureStrategy.requireCompatibleWithDependencyLifetime(dependencyLifetime);
            this.primingFailureStrategy = Objects.requireNonNull(primingFailureStrategy);
            return dependencyLifetime(dependencyLifetime);
        }

        /**
         * Defines how long this Node's dependencies will live relative to this Node and the Graph Call that executes
         * them. The default value is {@link DependencyLifetime#NODE_FOR_DIRECT}; see that javadoc for reasons why.
         * 
         * @throws IllegalArgumentException if this Builder's current value of primingFailureStrategy is not compatible
         *         with dependencyLifetime.
         */
        protected B dependencyLifetime(DependencyLifetime dependencyLifetime) {
            requireHasNotBuilt();
            this.primingFailureStrategy.requireCompatibleWithDependencyLifetime(dependencyLifetime);
            this.dependencyLifetime = Objects.requireNonNull(dependencyLifetime);
            return getThis();
        }

        protected B cancelMode(CancelMode cancelMode) {
            this.cancelMode = cancelMode;
            return getThis();
        }

        /**
         * Defines how to build Container Exceptions, which are the exceptions returned from
         * {@link Node#call(Caller, Memory, GraphCall, Observer)}. The default value for this property is
         * SUPPRESS_DEPENDENCY_FAILURES so as not to lose information, but if this Node purposefully wants to return
         * only a single exception, feel free to use DISCARD_DEPENDENCY_FAILURES... just make sure that you're
         * purposefully losing this extra dependency failure information.
         */
        protected B exceptionStrategy(ExceptionStrategy exceptionStrategy) {
            requireHasNotBuilt();
            this.exceptionStrategy = Objects.requireNonNull(exceptionStrategy);
            return getThis();
        }

        protected <T> SameMemoryDependency<M, T> internalPrimedDependency(Node<M, T> node) {
            requireHasNotBuilt();
            SameMemoryDependency<M, T> dependency = Dependencies.newSameMemoryDependency(node, PrimingMode.PRIMED);
            requireSameNotBothPrimedAndUnprimed(dependency);
            primedDependencies.add(dependency);
            return dependency;
        }

        private void requireSameNotBothPrimedAndUnprimed(SameMemoryDependency<M, ?> dependency) {
            SameMemoryDependency<M, ?> existingDependency = seenSameNodeToDependency.putIfAbsent(dependency.getNode(),
                    dependency);
            if ((existingDependency == null) || existingDependency.equals(dependency)) {
                return;
            }
            String message = "SameMemoryDependencys cannot be both primed and unprimed for the same consumer: "
                    + dependency;
            throw new IllegalArgumentException(message);
        }

        protected <T> SameMemoryDependency<M, T> internalSameMemoryUnprimedDependency(Node<M, T> node) {
            requireHasNotBuilt();
            SameMemoryDependency<M, T> dependency = Dependencies.newSameMemoryDependency(node, PrimingMode.UNPRIMED);
            requireSameNotBothPrimedAndUnprimed(dependency);
            unprimedDependencies.add(dependency);
            return dependency;
        }

        protected <NM extends Memory<?>, T> NewMemoryDependency<NM, T> internalNewMemoryDependency(Node<NM, T> node) {
            requireHasNotBuilt();
            requireDifferentMemory(node);
            NewMemoryDependency<NM, T> dependency = Dependencies.newNewMemoryDependency(node);
            unprimedDependencies.add(dependency);
            return dependency;
        }

        private void requireDifferentMemory(Node<?, ?> node) {
            if (memoryClass.equals(node.getMemoryClass())) {
                String message = String.format(
                        "Can only use SameMemoryDependency-returning methods to add a dependency targeting the same memoryClass '%s'",
                        memoryClass);
                throw new IllegalArgumentException(message);
            }
        }

        protected <AM extends Memory<?>, T> AncestorMemoryDependency<AM, T> internalAncestorMemoryDependency(
                Node<AM, T> node) {
            requireHasNotBuilt();
            requireDifferentMemory(node);
            AncestorMemoryDependency<AM, T> dependency = Dependencies.newAncestorMemoryDependency(node);
            unprimedDependencies.add(dependency);
            return dependency;
        }

        protected B internalClearDependencies() {
            requireHasNotBuilt();
            primedDependencies.clear();
            unprimedDependencies.clear();
            seenSameNodeToDependency.clear();
            return getThis();
        }

        /** Adds a ForNodeGraphValidatorFactory to this builder. */
        public B graphValidatorFactory(ForNodeGraphValidatorFactory factory) {
            requireHasNotBuilt();
            graphValidatorFactories.add(Objects.requireNonNull(factory));
            return getThis();
        }

        /** Clears all ForNodeGraphValidatorFactory associated with this builder. */
        public B clearGraphValidatorFactories() {
            requireHasNotBuilt();
            graphValidatorFactories.clear();
            return getThis();
        }
    }


    /**
     * A builder for creating "input nodes". This builder is good for only one use. That is, as soon as you call the
     * build method, calling any other method on this builder will throw an IllegalStateException.
     */
    public static final class InputBuilder<M extends Memory<T>, T> extends Builder<M, InputBuilder<M, T>> {
        /** TypeInstance for all nodes of type INPUT_TYPE. */
        private static final TypeInstance INPUT_TYPE_INSTANCE = new TypeInstance() {};
        /** All nodes created through InputBuilder will have this as their type. */
        public static final Type INPUT_TYPE = new Type("Input") {
            @Override
            public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
                return instance == INPUT_TYPE_INSTANCE;
            }
        };

        private InputBuilder(Class<M> memoryClass) {
            super(memoryClass);
            typeTypeInstance(INPUT_TYPE, INPUT_TYPE_INSTANCE);
            // There are no dependencies, so pick the most restrictive value
            dependencyLifetime(DependencyLifetime.NODE_FOR_ALL);
            // Input nodes have no dependencies, so ignoring them only simplifies/optimizes code paths
            exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES);
        }

        @Override
        protected InputBuilder<M, T> getThis() {
            return this;
        }

        public Node<M, T> build() {
            requireHasNotBuilt();
            setHasBuilt();
            cancelMode(CancelMode.DEFAULT);
            return new Node<>(this, (caller, reply, memory, graphCall, observer, device) -> memory.getInput());
        }

    }

    public Type getType() {
        return type;
    }

    public TypeInstance getTypeInstance() {
        return typeInstance;
    }

    @Override
    public Role getRole() {
        return role;
    }

    public Class<M> getMemoryClass() {
        return memoryClass;
    }

    public PrimingFailureStrategy getPrimingFailureStrategy() {
        return primingFailureStrategy;
    }

    /** The DependencyLifetime this Node was created with/it was declared to have. */
    public DependencyLifetime getDeclaredDependencyLifetime() {
        return declaredDependencyLifetime;
    }

    /**
     * The minimum, most-restrictive DependencyLifetime that this Node effectively has. Whereas
     * {@link #getDeclaredDependencyLifetime()} returns the value that it was set to during construction, the value
     * returned here is the most-restrictive version of how this Node's {@link DependencyLifetime} behaves. For example,
     * a value of {@link DependencyLifetime#GRAPH} for a Node without dependencies also behaves like a
     * {@link DependencyLifetime#NODE_FOR_DIRECT} or a {@link DependencyLifetime#NODE_FOR_ALL}, but the minimum value is
     * NODE_FOR_ALL, so that's what would be returned here. Meanwhile, a value of GRAPH for a Node with any dependencies
     * could only be described as having a GRAPH value, so that's what would be returned here. This concept is useful
     * for optimizations (e.g. sharing data structures for Nodes that effectively behave like NODE_FOR_ALL) that may
     * change over time as a Graph evolves.
     */
    public DependencyLifetime getMinimumDependencyLifetime() {
        return minimumDependencyLifetime;
    }

    /** Returns a description of how this Node's Replys can be cancelled. */
    public CancelMode getCancelMode() {
        return cancelMode;
    }

    /**
     * Answers whether any Node in this Node's "shadow" supports active cancellation hooks. "Shadow" means this Node
     * plus all of its dependencies, both direct and transitive. I'm sure that's not an official term, but I can't find
     * an official term for this concept, and this method is package protected anyway.
     */
    boolean shadowSupportsActiveCancelHooks() {
        return shadowSupportsActiveCancelHooks;
    }

    public ExceptionStrategy getExceptionStrategy() {
        return exceptionStrategy;
    }

    /**
     * Returns all of this Node's (direct) primed dependencies.
     * 
     * @apiNote package private, because I don't see a need to expose this concept to others, yet. It's strictly useful
     *          right now for DependencyCallingDevice.
     */
    Set<SameMemoryDependency<M, ?>> getPrimedDependencies() {
        return primedDependencies;
    }

    /**
     * Returns all of this Node's (direct) dependencies. It's guaranteed that no Node is repeated in the returned set:
     * i.e. a dependency Node can only be one type of Dependency for a given consumer.
     */
    public Set<? extends Dependency<?, ?>> getDependencies() {
        return dependencies;
    }

    /**
     * Returns all of this Node's (direct) primed dependencies.
     * 
     * @apiNote package private, because I don't see a need to expose this concept to others, yet. It's strictly useful
     *          right now for DependencyCallingDevice.
     */
    Set<Node<?, ?>> getDependencyNodes() {
        // Single-lock idiom is good enough
        if (dependencyNodes == null) {
            dependencyNodes = dependencies.stream().map(Dependency::getNode).collect(Collectors.toUnmodifiableSet());
        }
        return dependencyNodes;
    }

    /**
     * Returns whether or not all of this Node's dependencies are primed. Convenience method that provides an answer
     * already achievable through other methods.
     * 
     * @apiNote package private, because I don't see a need to expose this concept to others, yet. It's strictly useful
     *          right now for DependencyCallingDevice.
     */
    boolean getHasOnlyPrimedDependencies() {
        return dependencies.size() == primedDependencies.size();
    }

    /** Returns all ForNodeGraphValidatorFactory associated with this Node. */
    public Collection<ForNodeGraphValidatorFactory> getGraphValidatorFactories() {
        return graphValidatorFactories;
    }

    /**
     * Creates all of this Node's GraphValidators by running the {@link #getGraphValidatorFactories()} against itself.
     */
    public Collection<GraphValidator> createGraphValidators() {
        // Most of the time, the list of factories will be empty, so I feel this optimization is worth it, although I
        // have no proof of that.
        if (graphValidatorFactories.isEmpty()) {
            return List.of();
        }

        return graphValidatorFactories.stream()
                .map(factory -> factory.create(this))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns a brief description of this node alone, without describing any of the dependencies.
     */
    @Override
    public String getSynopsis() {
        return "[" + memoryClass.getSimpleName() + "] [" + type + "] " + role;
    }

    @Override
    public String toString() {
        String validates = graphValidatorFactories.stream()
                .map(ForNodeGraphValidatorFactory::getDescription)
                .collect(Collectors.joining(",", "(", ")"));
        return "[" + memoryClass.getSimpleName() + "] [" + type + "] [" + primingFailureStrategy + "] ["
                + minimumDependencyLifetime + "] [" + exceptionStrategy + "] " + "[validates=" + validates + "] "
                + role;
    }

    /**
     * The "thing" that a node does when it's called. This differs from {@link Behavior} in that Conduct is given access
     * to the Memory associated with the call. Behavior is not given access in order to prevent clients from using it to
     * invoke non-modeled dependencies. Meanwhile, there are some legitimate cases where the Memory is required (e.g.
     * for retrieving the Memory's input, as "input nodes" do). As a compromise, Conduct is a private class and only
     * Node itself can create implementations... and so can guarantee that that only modeled dependencies are called.
     * 
     * @apiNote This class is so similar to Behavior with just a minor difference that the two classes use synonyms for
     *          their names. This is slightly confusing internally to Node, but external clients are never exposed to
     *          this concept. Any external javadoc referring only to Behavior can also be considered to refer to Conduct
     *          as well.
     */
    private interface Conduct<M extends Memory<?>, T> {
        CompletionStage<T> perform(Caller caller, Reply<T> reply, M memory, GraphCall<?> graphCall, Observer observer,
                DependencyCallingDevice<M> device);

        /**
         * Called by {@link FirstCall} to allow Conducts to modify the DependencyCallingDevice that will be used against
         * them. The default behavior is to perform no modifications, as is the norm.
         */
        default DependencyCallingDevice<M> modifyDevice(Reply<T> reply, M memory, GraphCall<?> graphCall,
                DependencyCallingDevice<M> device) {
            return device;
        }
    }

    /** The conduct associated with communal nodes executing just plain Behaviors. */
    private static class BehaviorCommunalConduct<M extends Memory<?>, T> implements Conduct<M, T> {
        private final Behavior<M, T> behaviorAfterPrimingDependencies;

        private BehaviorCommunalConduct(Behavior<M, T> behaviorAfterPrimingDependencies) {
            this.behaviorAfterPrimingDependencies = Objects.requireNonNull(behaviorAfterPrimingDependencies);
        }

        @Override
        public CompletionStage<T> perform(Caller caller, Reply<T> reply, M memory, GraphCall<?> graphCall,
                Observer observer, DependencyCallingDevice<M> device) {
            return behaviorAfterPrimingDependencies.run(device);
        }
    }

    /** The conduct associated with communal nodes executing BehaviorWithCompositeCancelSignals. */
    private static class BehaviorWithCompositeCancelSignalCommunalConduct<M extends Memory<?>, T>
            implements Conduct<M, T> {
        private final BehaviorWithCompositeCancelSignal<M, T> behaviorAfterPrimingDependencies;

        private BehaviorWithCompositeCancelSignalCommunalConduct(
                BehaviorWithCompositeCancelSignal<M, T> behaviorAfterPrimingDependencies) {
            this.behaviorAfterPrimingDependencies = Objects.requireNonNull(behaviorAfterPrimingDependencies);
        }

        @Override
        public CompletionStage<T> perform(Caller caller, Reply<T> reply, M memory, GraphCall<?> graphCall,
                Observer observer, DependencyCallingDevice<M> device) {
            CompositeCancelSignal signal = () -> measureCompositeCancelSignal(reply, memory.getScope());
            return behaviorAfterPrimingDependencies.run(device, signal);
        }
    }

    private static boolean measureCompositeCancelSignal(Reply<?> reply, MemoryScope memoryScope) {
        return reply.isCancelSignalTriggered() || memoryScope.isCancelSignalTriggered();
        // No need to check the GraphCall cancellation signal due to the note in MemoryScope#isCancelSignalTriggered
        // (i.e. GraphCall#isCancelSignalTriggered implies MemoryScope#isCancelSignalTriggered)
    }

    /** The conduct associated with communal nodes executing BehaviorWithCustomCancelActions. */
    private static class BehaviorWithCustomCancelActionCommunalConduct<M extends Memory<?>, T>
            implements Conduct<M, T> {
        private final BehaviorWithCustomCancelAction<M, T> behaviorAfterPrimingDependencies;

        private BehaviorWithCustomCancelActionCommunalConduct(
                BehaviorWithCustomCancelAction<M, T> behaviorAfterPrimingDependencies) {
            this.behaviorAfterPrimingDependencies = Objects.requireNonNull(behaviorAfterPrimingDependencies);
        }

        @Override
        public CompletionStage<T> perform(Caller caller, Reply<T> reply, M memory, GraphCall<?> graphCall,
                Observer observer, DependencyCallingDevice<M> device) {
            CompositeCancelSignal signal = () -> measureCompositeCancelSignal(reply, memory.getScope());
            CustomCancelActionBehaviorResponse<T> runResponse = behaviorAfterPrimingDependencies.run(device, signal);
            if (runResponse == null) {
                return null;
            }

            CustomCancelAction faultTolerantAction = faultTolerantCustomCancelAction(runResponse.getCancelAction(),
                    caller, reply, memory, graphCall, observer);
            CustomCancelAction finalAction = device.modifyCustomCancelAction(faultTolerantAction);
            reply.setCustomCancelAction(finalAction);
            memory.getScope().addReplyToTrack(reply);
            return runResponse.getBehaviorResponse();
        }

        private CustomCancelAction faultTolerantCustomCancelAction(CustomCancelAction decorated, Caller caller,
                Reply<T> reply, M memory, GraphCall<?> graphCall, Observer observer) {
            return mayInter -> {
                ObserverAfterStop<Object> observerAfterStop = observer.observeBeforeCustomCancelAction(caller,
                        reply.getNode(), memory);
                try {
                    decorated.run(mayInter);
                    observerAfterStop.observe(null, null);
                } catch (Throwable t) {
                    observerAfterStop.observe(null, t);
                    CustomCancelActionException exception = new CustomCancelActionException(t, reply, memory);
                    graphCall.addUnhandledException(exception);
                }
            };
        }

        /**
         * If Reply's Node's CancelMode supports interrupts, then the device is decorated to be able to support
         * interrupts. Otherwise, the device is returned untouched.
         */
        public DependencyCallingDevice<M> modifyDevice(Reply<T> reply, M memory, GraphCall<?> graphCall,
                DependencyCallingDevice<M> device) {
            if (reply.getNode().getCancelMode().supportsCustomActionInterrupt()) {
                Consumer<Throwable> interruptClearingExceptionSuppressor = throwable -> suppressInterruptClearingException(
                        throwable, reply, memory, graphCall);
                return DependencyCallingDevices.createInterruptSupporting(device, behaviorAfterPrimingDependencies,
                        interruptClearingExceptionSuppressor);
            }
            return device;
        }

        private static void suppressInterruptClearingException(Throwable cause, Reply<?> reply, Memory<?> memory,
                GraphCall<?> graphCall) {
            InterruptClearingException exception = new InterruptClearingException(cause, reply, memory);
            graphCall.addUnhandledException(exception);
        }
    }

    /**
     * Calls this node: returns a NodeReply that represents this set of actions: calls all dependenciesToPrime, waits
     * for them to finish, and then executes the behavior intrinsic with this node (which may call
     * dependenciesNotToPrime). All of this is done optimally using CompletableFuture's callback framework: the Aggra
     * framework doesn't block the thread to wait for any result (although client-provided behaviors technically can,
     * but are highly discouraged from doing so). This method will only ever execute this set of steps at most once per
     * Memory instance.
     * 
     * Exceptional behavior:
     * 
     * What follows is a discussion of exceptional behavior. There are many nuances to exceptional behavior.
     * 
     * This method may throw exceptions itself, but this will only ever happen before the "main logic" of this Node is
     * run. By "main logic", I mean the combination of the priming phase, the Behavior phase, and the waiting phase. If
     * an exception is thrown, then this indicates a major problem either with the input, the Aggra framework itself,
     * and/or the pieces provided to execute the Aggra framework (e.g. the Storage/Memory). In this case, consumer Nodes
     * should not proceed further with their logic. In addition, the response from this method will *not* be memoized
     * across calls for the same Memory.
     * 
     * If the "main logic" of this Node has already started, this call will not throw an exception. Instead, any
     * exceptions will be indicated in the Reply returned from this method. In addition, the response from this call
     * will be memoized across calls for the same Memory. (Note: this is "within reason". E.g. after the "main logic" is
     * run, there's a catch all that needs to run, and it creates an Object from Memory. If this happens to throw an
     * OutOfMemoryError, then the NodeReply will be memoized, but it will never complete, and this method call will
     * throw that same OutOfMemoryError. So, "within reason" means error cases that should result in the shutdown of the
     * JVM itself. That said, the "main logic" will, if it can, catch any of these fatal errors from the to-prime
     * dependencies or intrinsic behavior and return a NodeReply indicating them.) See the javadoc for Reply to
     * understand how it represents the exceptional structure and how it provides access to it.
     * 
     * Reply has the concept of an "encountered exception". This call method along with the Node's ExceptionStrategy are
     * responsible for determining what the exception is exactly. From this call method's perspective (i.e. ignoring
     * ExceptionStrategy):<br>
     * * If any dependency-to-prime throws an exception (again, as mentioned above, indicating a serious error) while
     * being called during the priming phase, then it's undefined whether any of the other dependencies-to-prime have
     * been or will be called during the priming phase. Furthermore, the intrinsic behavior of this Node will *not* be
     * run. The NodeReply from this method will indicate the priming failure as the encountered exception. In contrast,
     * if any node *returns* a NodeReply indicating a failure (instead of throwing a failure), this will not affect the
     * running of any other to-prime dependency, and the intrinsic behavior will run as is.<br>
     * * If this Node's intrinsic behavior throws an exception, then that will be counted as the encountered exception.
     * Otherwise, if the behavior returns an exceptional NodeReply, that will be counted as the encountered
     * exception.<br>
     * * If this Node's intrinsic behavior returns a null CompletionStage, then the Reply will be completed with a
     * MisbehaviorException, which will be counted as the encountered exception.<br>
     * * Cancellation behavior can also affect the encountered exception when it's detected that a Reply should be
     * cancelled. See the discussion about "hooks" in the section below.<br>
     * 
     * Cancellation behavior:
     * 
     * The Aggra framework supports multiple cancel signals, which are signals that indicate groups of Replys should be
     * cancelled. Aggra triggers these signals at specific points. Aggra provides multiple hooks for detecting those
     * signals and taking the appropriate action.
     * 
     * Aggra has 3 possible cancel signals:<br>
     * 1. {@link GraphCall} signal -- means all Replies for a given GraphCall should be cancelled.<br>
     * 2. {@link MemoryScope} signal -- means all Replies in a given MemoryScope should be cancelled.<br>
     * 3. {@link Reply} signal -- means that a given Reply should be cancelled. <br>
     * 
     * Aggra has triggers for each of the 3 cancel signals:<br>
     * 1. GraphCall -- there are two different triggers for this signal. First, the GraphCall can be cancelled by the
     * user explicitly. See {@link GraphCall#triggerCancelSignal()}. Second, the GraphCall can be cancelled implicitly
     * once the graph has been {@link GraphCall#weaklyClose()}-ed and all rootReplies are complete.<br>
     * 2. MemoryScope -- there are two different triggers for this signal. First, a given MemoryScope can be cancelled
     * when all externally-accessible replies in that MemoryScope are complete. E.g. when a consumer Node calls
     * {@link DependencyCallingDevice#createMemoryAndCall}), a MemoryScope is created... and that MemoryScope will be
     * cancelled once the NewMemoryDependency call (the only Reply from the MemoryScope that's externally accessible) is
     * complete. Second, a given MemoryScope can be cancelled when any one of its ancestor MemoryScopes is cancelled.
     * <br>
     * 3. Reply -- this signal is triggered for a given Reply whenever a consuming Node calls
     * {@link DependencyCallingDevice#ignore(Reply)} *and* Aggra can prove that no other consumer call is interested in
     * the response from the Reply. See the javadoc for ignore for more details.<br>
     * 
     * Additionally, besides the triggers mentioned above, GraphCall signals may also trigger MemoryScope and/or Reply
     * signals. MemoryScope signals may also trigger Reply signals. That's really an implementation detail, though, one
     * which I mention for the sake of conceptual completeness. After all, the GraphCall signal would implicitly seem to
     * imply both the MemoryScope and Reply signals. Whether Aggra actually does that or cancellation is accomplished in
     * another way is left unspecified.
     * 
     * Aggra provides both passive and active hooks for detecting the cancellation signals and taking action on them.
     * With "passive" hooks, the cancel signal is triggered, and a status is set. Consumers must poll that status and
     * take action themselves. With "Active" hooks, consumers register the action to take with Aggra, and Aggra executes
     * that action after triggering the cancel signal. (Note: in either case, Aggra is free to ignore signals if they
     * would have no practical effect (e.g. A Reply cancellation signal sent to an already-complete Reply.) <br>
     * 1. Before priming phase (Passive) -- this is a passive hook, where Aggra is the consumer itself of the GraphCall
     * and MemoryScope cancel signals. At the start of every Node call, before priming dependencies, Aggra polls those
     * cancel signals. If they're triggered, the Reply will be completed immediately with an encountered exception of
     * CancellationException. No priming will take place. No behavior will be run.<br>
     * 2. Between the priming and behavior phases (Passive) -- this is similar to the previous hook, except that it
     * takes place *after* dependencies have finished priming but before the Node's behavior has been run. Again, Aggra
     * polls the GraphCall and MemoryScope signals; however, this time it also polls the Reply signal as well. There's
     * only a very specific set of circumstances where the Reply signal will matter, so users should take a look at the
     * notes on {@link CommunalBuilder#build(Behavior)} about which builds support that and why. If any of those signals
     * are triggered (and the Node supports those triggers), the Reply will be completed immediately with an encountered
     * exception of CancellationException. No behavior will be run.<br>
     * 3. Composite cancel signal (Passive) -- this is a passive hook, where Aggra passes a composite cancel signal to
     * the Behavior (see {@link CommunalBuilder#buildWithCompositeCancelSignal(BehaviorWithCompositeCancelSignal)}. This
     * composite signal answers whether any of the GraphCall, MemoryScope, or Reply cancellation signals have been
     * triggered. The idea is that the Behavior can poll this signal at its leisure and decide what to do with the
     * signal response: i.e. it's completely up to the user what to do with this information/what cancellation means.
     * <br>
     * 4. Custom cancel action (Active) -- this is an active hook. As with the composite cancel signal hook, this hook
     * also includes the same composite cancel signal in the call to the Node's Behavior. Building on that, this hook
     * also expects back a more complex answer, including both the Behavior's CompletionStage response as well as a
     * {@link CustomCancelAction} that can be called when that CompletionStage should be cancelled. Lastly, this hook
     * also allows this action to support cancellation through interrupts or not. See
     * {@link CommunalBuilder#buildWithCustomCancelAction(BehaviorWithCustomCancelAction)}. Note: any exception thrown
     * by the custom cancel action is suppressed and added to the GraphCall to be exposed through
     * {@link GraphCall.FinalState#getUnhandledExceptions()}. This suppression is done so as to prioritize the
     * more-essential, primary result-producing path of a Node call over the nice-to-have, secondary cancellation
     * path... as well as to unify Node logic when an exception is thrown, given that the cancel action can be run
     * either when the cancel signal is detected or (if the action wasn't set when the signal was detected) when the
     * cancel signal is set.
     * 
     * @param observer an observer for this call. This observer *must* be fault-tolerant. See
     *        {@link Observer#faultTolerant(Observer, ObservationFailureObserver)} for more information on what that
     *        means. In short, the observer and any observers it creates *must not* throw any exceptions. Doing so would
     *        invalidate promises that this method makes.
     * 
     *        There are two kinds of observations that happen: one for every call to this method (useful for
     *        constructing a graph of all the runtime calls made between nodes, for example, possibly including timing
     *        information) and one for the first, unmemoized call to this method (useful for recording timing
     *        information about the Node's "main logic", for example). This observer will be passed through all
     *        dependency calls that this node makes, all dependencies of dependencies, etc.
     *        <p>
     *        {@link Observer#observeBeforeEveryCall(Caller, Node, Memory)} is always called before anything else
     *        happens in this method (besides basic sanity checks that the input parameters are legal). If this throws
     *        an exception, then that exception is propagated from this method as is. Otherwise, the resulting
     *        {@link ObserverAfterStop} is always called at the end of this method. The first parameter passed to
     *        ObserverAfterStop is the result of this method call if successful (or null if not); while the second
     *        parameter is the exception that was thrown if this method was not successful (or null if the method was
     *        successful). Note: the first parameter is not guaranteed to be complete on exit, so accessing it
     *        prematurely may block.
     *        <p>
     *        {@link Observer#observeBeforeFirstCall(Caller, Node, Memory)} is called only for the first, as-yet
     *        unmemoized call to this method. It's called before the "main logic" of this method. The resulting
     *        {@link ObserverAfterStop} is called just before the "main logic"'s reply is completed (this is done mostly
     *        so as to establish a happens-before relationship between the observation and the completion of the
     *        NodeReply, so that readers of the NodeReply can be sure that the observation is already complete). The two
     *        parameters passed to ObserverAfterStop represent the value of the NodeReply, the first (possibly null,
     *        even on success) parameter being the value on success, and the second parameter being the value on
     *        exception (or null on success). Note: this exception represents the encountered exception mentioned above:
     *        i.e. the exception before any transformation is applied to standardize the causal structure.
     * 
     * @throws IllegalArgumentException if caller is self.
     * @throws all the same exceptions as {@link Memory#computeIfAbsent(Node, java.util.function.Supplier)}.
     *
     * @apiNote declared package private in order to force Node calls to be made through the {@link Graph} interface
     *          rather than directly through Nodes.
     */
    Reply<T> call(Caller caller, M memory, GraphCall<?> graphCall, Observer observer) {
        return call(caller, memory, graphCall, observer, Runnable::run);
    }

    /**
     * The same as {@link #call(Caller, Memory, GraphCall, Observer)}, except that an explicit executor is provided for
     * running the node call's first-call logic. The "first-call logic" is the priming, behavior, and waiting phases
     * that only happen during the first call to the node. All of the other logic for a call, like checking if a Reply
     * already exists in the memory and potentially creating and storing one if not, is run on the caller thread
     * directly.
     * 
     * This type of call is useful for isolating alien, node-specific, potentially-rogue, user-provided behavior logic
     * on a separate thread. This logic might, for example, take too long to return, taking time away from the caller
     * thread to execute other Aggra logic. There's definitely a cost to this safety: the cost of switching threads,
     * which needs to be balanced against the benefits.
     * 
     * Calling this method is much safer than executing the non-Executor-specifying method completely on an Executor. In
     * the worst case, if the node call doesn't return at all (because the user-provided behavior doesn't), Aggra will
     * be left unaware during a dependency call that the reply even exists and needs to be tracked. That means
     * {@link GraphCall#weaklyClose()}'s response won't account for it, and the user will be left unaware that a
     * dependency call is still in progress. Meanwhile, with this method, since only the behavior is run on a separate
     * executor, the reply-supplying logic will still run on the caller thread, and Aggra will know about it.
     */
    Reply<T> call(Caller caller, M memory, GraphCall<?> graphCall, Observer observer, Executor firstCallExecutor) {
        Objects.requireNonNull(caller);
        Objects.requireNonNull(graphCall);
        if (caller == this) {
            throw new IllegalArgumentException("Nodes cannot be recorded as calling themselves: " + this);
        }

        ObserverAfterStop<? super Reply<T>> observerAfterStop = observer.observeBeforeEveryCall(caller, this, memory);
        try {
            Reply<T> reply = storeAndCallIfAbsent(caller, memory, graphCall, observer, firstCallExecutor);
            observerAfterStop.observe(reply, null);
            return reply;
        } catch (Throwable callThrowable) {
            observerAfterStop.observe(null, callThrowable);
            throw callThrowable;
        }
    }

    private Reply<T> storeAndCallIfAbsent(Caller caller, M memory, GraphCall<?> graphCall, Observer observer,
            Executor firstCallExecutor) {
        StorageAttempt<T> storageAttempt = StorageAttempt.storeIfAbsent(caller, this, memory, observer);
        if (storageAttempt.isFirstStorage) {
            firstCallExecutor.execute(() -> FirstCall.callTowardsReply(this, caller, memory, graphCall, observer,
                    storageAttempt.firstCallObserverAfterStop, storageAttempt.reply));
        }
        return storageAttempt.reply;
    }

    /**
     * A simple holder class to capture objects associated with an attempt to create and store a reply in Memory, before
     * actually trying to run the logic to populate the reply (if necessary). Convenient for passing arguments around
     * and needed to get around lambdas inability to modify local variables.
     * 
     * Note: only {@link #storeIfAbsent(Node, Caller, Memory, Observer)} should modify variables. They can't be final
     * because of how the class is used (inability to modify local variables), but I don't want to extract this class
     * outside of Node.
     */
    private static class StorageAttempt<T> {
        /**
         * Whether this attempt is the first attempt to compute and store a reply or whether it's been previously
         * stored. {@link #firstCallObserverAfterStop} will be populated with a non-null value if this is true;
         * otherwise, they'll be null. That makes sense, because only the first storage computes/has access to that
         * value.
         */
        private boolean isFirstStorage = false;
        /**
         * Either the newly computed and stored reply or the previously-existing one, depending on the value of
         * {@link #isFirstStorage}.
         */
        private Reply<T> reply;
        /**
         * The ObserverAfterStop corresponding to the already-started observation for the first call to the Reply's
         * Node. Only populated if {@link #isFirstStorage}.
         */
        private ObserverAfterStop<? super T> firstCallObserverAfterStop;

        public static <M extends Memory<?>, T> StorageAttempt<T> storeIfAbsent(Caller caller, Node<M, T> node,
                Memory<?> memory, Observer observer) {
            StorageAttempt<T> storageAttempt = new StorageAttempt<>();
            Reply<T> reply = memory.computeIfAbsent(node, () -> {
                storageAttempt.isFirstStorage = true;
                storageAttempt.firstCallObserverAfterStop = observer.observeBeforeFirstCall(caller, node, memory);
                return Reply.forCall(caller, node);
            });
            storageAttempt.reply = reply;
            return storageAttempt;
        }
    }

    /**
     * The logic necessary to make the first, raw, unmemoized node call.
     */
    private static class FirstCall<M extends Memory<?>, T> {
        private final Caller caller;
        private final GraphCall<?> graphCall;
        private final Node<M, T> node;
        private final M memory;
        private final DependencyCallingDevice<M> device;
        private final Observer observer;
        private final ObserverAfterStop<? super T> firstCallObserverAfterStop;
        private final Reply<T> reply;

        private FirstCall(Caller caller, GraphCall<?> graphCall, Node<M, T> node, M memory,
                DependencyCallingDevice<M> device, Observer observer,
                ObserverAfterStop<? super T> firstCallObserverAfterStop, Reply<T> reply) {
            this.caller = caller;
            this.graphCall = graphCall;
            this.node = node;
            this.memory = memory;
            this.device = device;
            this.observer = observer;
            this.firstCallObserverAfterStop = firstCallObserverAfterStop;
            this.reply = reply;
        }

        public static <M extends Memory<?>, T> void callTowardsReply(Node<M, T> node, Caller caller, M memory,
                GraphCall<?> graphCall, Observer observer, ObserverAfterStop<? super T> observerAfterStop,
                Reply<T> reply) {
            // Note: no point checking the Reply cancellation signal now, since it couldn't have been triggered yet
            CancellationException cancellationException = checkCancellationSignals(node, null, memory);
            DependencyCallingDevice<M> device = createDependencyCallingDevice(cancellationException, node, memory,
                    graphCall, observer, reply);
            new FirstCall<>(caller, graphCall, node, memory, device, observer, observerAfterStop, reply)
                    .callWithPotentialCancellationBeforePriming(cancellationException);
        }

        private static CancellationException checkCancellationSignals(Node<?, ?> node, Reply<?> reply,
                Memory<?> memory) {
            // Note: minimize reads of potential volatile by saving status to local variable. Reply is optional
            boolean replyCancelTriggered = reply == null ? false : reply.isCancelSignalTriggered();
            boolean memoryScopeCancelTriggered = memory.getScope().isCancelSignalTriggered();
            // No need to check the GraphCall cancellation signal due to the note in MemoryScope#isCancelSignalTriggered
            // (i.e. GraphCall#isCancelSignalTriggered implies MemoryScope#isCancelSignalTriggered)

            boolean shouldCancel = replyCancelTriggered || memoryScopeCancelTriggered;
            if (shouldCancel) {
                List<String> cancellationSignals = new ArrayList<>();
                if (replyCancelTriggered) {
                    cancellationSignals.add(reply.toString());
                }
                if (memoryScopeCancelTriggered) {
                    cancellationSignals.add(memory.toString());
                }
                String message = String.format("Cancelling Node '%s' after detecting cancellation signals: '%s'",
                        node.getRole(), cancellationSignals);
                return new CancellationException(message);
            }
            return null;
        }

        private static <M extends Memory<?>, T> DependencyCallingDevice<M> createDependencyCallingDevice(
                CancellationException cancellationException, Node<M, T> node, M memory, GraphCall<?> graphCall,
                Observer observer, Reply<T> reply) {
            boolean shouldCancel = cancellationException != null;
            if (shouldCancel) {
                return DependencyCallingDevices.createForCancelled(graphCall, node, memory, observer);
            }
            DependencyCallingDevice<M> device = DependencyCallingDevices
                    .createNonInterruptSupportingByPrimingDependencies(graphCall, node, memory, observer);
            return node.conductAfterPrimingDependencies.modifyDevice(reply, memory, graphCall, device);
        }

        private void callWithPotentialCancellationBeforePriming(CancellationException cancellationException) {
            if (cancellationException == null) {
                callWithNormalPriming();
            } else {
                startCompleteReply(null, cancellationException);
            }
        }

        private void callWithNormalPriming() {
            try {
                device.getPrimingResponse().getOrThrowThrowable().whenComplete(this::handleSuccessfulPrimingCompletion);
            } catch (Throwable t) {
                // This branch is run when even executing the priming phase throws vs. handlePrimingCompletion
                // is called when the priming phase complete, but one of the primed dependencies returns a failure
                // response. In the former case, we *have to* stop right now; otherwise, we *may* be able to continue
                startCompleteReply(null, t);
            }
        }

        /**
         * @apiNote we only check the MemoryScope and GraphCall cancellation signals here. We could theoretically check
         *          the Reply cancellation signal. Unlike before priming, the Reply might be cancelled now, since it's
         *          been published to consumers at this point in the Node call. However, this would only be of benefit
         *          in a very, very limited set of circumstances. Meanwhile, all users would pay the cost of reading the
         *          (volatile) Reply cancellation signal. Instead, we'll provide users that care about this feature an
         *          easy way to detect it as part of the run behavior and cancel that behavior themselves early.
         */
        private void handleSuccessfulPrimingCompletion(Object primingResult, Throwable primingThrowable) {
            boolean shouldContinueGivenPrimingResult = (primingThrowable == null
                    || node.getPrimingFailureStrategy().shouldContinueOnPrimingFailures());
            Throwable throwableToCompleteWith = shouldContinueGivenPrimingResult
                    ? checkCancellationSignals(node, reply, memory)
                    : primingThrowable;
            if (throwableToCompleteWith == null) {
                performConductAndChainCompletion();
            } else {
                startCompleteReply(null, throwableToCompleteWith);
            }
        }

        private void performConductAndChainCompletion() {
            ObserverAfterStop<Object> behaviorObserverAfterStop = observer.observeBeforeBehavior(caller, node, memory);
            CompletionStage<T> behaviorCompletionStage = performConductAsNonThrowing();
            if (behaviorCompletionStage == null) {
                MisbehaviorException exception = new MisbehaviorException(
                        "Behaviors are not allowed to return null Responses.");
                startCompleteReplyAfterConduct(null, exception, behaviorObserverAfterStop);
            } else {
                behaviorCompletionStage.whenComplete((result, throwable) -> startCompleteReplyAfterConduct(result,
                        throwable, behaviorObserverAfterStop));
            }
        }

        private CompletionStage<T> performConductAsNonThrowing() {
            try {
                return node.conductAfterPrimingDependencies.perform(caller, reply, memory, graphCall, observer, device);
            } catch (Throwable t) {
                // Note: it's justified to catch Throwable above, rather than RuntimeException; because that's how
                // CompletableFuture and FutureTask behave internally as well: catch everything. There's no need to
                // apply side effects of this catching (e.g. setting the Thread interrupt flag), since nothing in
                // the try block can throw the InterruptedException that would warrant that.
                return CompletableFuture.failedFuture(t);
            }
        }

        private void startCompleteReplyAfterConduct(T result, Throwable throwable,
                ObserverAfterStop<Object> behaviorObserverAfterStop) {
            behaviorObserverAfterStop.observe(result, throwable);
            startCompleteReply(result, throwable);
        }

        private void startCompleteReply(T result, Throwable throwable) {
            reply.startComplete(result, throwable, device, firstCallObserverAfterStop);
        }
    }
}
