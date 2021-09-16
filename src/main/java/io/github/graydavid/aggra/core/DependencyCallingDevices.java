/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.graydavid.aggra.core.Behaviors.Behavior;
import io.github.graydavid.aggra.core.Behaviors.BehaviorWithCustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.CustomCancelAction;
import io.github.graydavid.aggra.core.Behaviors.InterruptModifier;
import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.MemoryBridges.AncestorMemoryAccessor;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.onemoretry.Try;

/**
 * Houses definitions of different types of DependencyCallingDevices and other tightly-related classes.
 */
public class DependencyCallingDevices {
    private DependencyCallingDevices() {}

    /**
     * Creates a non-interrupt-supporting (i.e. not supporting {@link CancelMode#INTERRUPT}) DependencyCallingDevice by
     * priming all of the primed dependencies in callingNode and storing the Replys for tracking. The response from
     * priming the dependencies can be accessed via {@link #getPrimingResponse()}. As indicated by that method's return
     * type, this method will swallow any priming exception for exposure there. If there is such an exception, it's
     * undefined which priming has already been done, but it's guaranteed that the returned Device will be keeping track
     * of them. This ensures that the {@link #weaklyClose()} can be used just as any other normal Device for deciding
     * how to complete a Reply.
     * 
     * Although adding the priming phase to Device construction increases coupling from this class to Node, there are a
     * couple of optimizations that it allows: <br>
     * 1. First, adding all primed Replys to the constructed Device at once avoids any synchronization overhead (that
     * would happen if {@link #call(SameMemoryDependency)} were done individually on them in a scheme where the priming
     * phase was separate from this class).<br>
     * 2. By storing all primed dependency Replys initially, there's no need to store them during calls to
     * {@link #call(SameMemoryDependency)}.<br>
     * 3. Additionally because of #2, this method can optimize the tracking infrastructure for Replys. For Nodes that
     * have only primed dependencies, since dependencyReplies will never be modified (outside of {@link #weaklyClose()},
     * which clears the data structure, and for which synchronization doesn't matter), its data structure doesn't have
     * to be thread safe.
     * 
     * @apiNote package private, because I only see need for this concept in the Aggra framework.
     */
    static <M extends Memory<?>> DependencyCallingDevice<M> createNonInterruptSupportingByPrimingDependencies(
            GraphCall<?> graphCall, Node<M, ?> callingNode, M memory, Observer observer) {
        Collection<Reply<?>> primedReplies = new ArrayList<>();
        // Catching Throwable is justifiable in the realm of where this call applies. See the note inside of
        // Node#FirstCall. In addition FirstCall itself accesses and rethrows any exception caught here.
        Try<CompletableFuture<Void>> primingResponse = Try.callCatchThrowable(() -> {
            callingNode.getPrimedDependencies().stream().forEach(dependency -> {
                Reply<?> reply = dependency.getNode().call(callingNode, memory, graphCall, observer);
                primedReplies.add(reply);
            });
            return Reply.allOfBacking(primedReplies);
        });
        primingResponse.getSuccess()
                .ifPresent(
                        allOf -> callingNode.getPrimingFailureStrategy().modifyPrimingCompletion(allOf, primedReplies));
        // If there are only primed dependencies, we'll never need to add another Reply to dependencyReplies, since
        // we've already captured them all initially. So, just reuse the current primedReplies we've already built
        // (as an unmodifiable copy). Otherwise, we need to be able to add to dependencyReplies in a concurrent way.
        Collection<Reply<?>> dependencyReplies = callingNode.getHasOnlyPrimedDependencies() ? List.copyOf(primedReplies)
                : new ConcurrentLinkedQueue<>(primedReplies);
        return new NonInterruptSupportingDependencyCallingDevice<>(callingNode, memory, graphCall, observer,
                primingResponse, dependencyReplies);
    }

    /**
     * Creates a DependencyCallingDevice for a call that will be cancelled before priming any dependencies. Similarly,
     * there will be no other dependency calls, no execution of behavior, etc. The only way the device will be used is
     * {@link #weaklyClose()}, which will return an empty FinalState.
     * 
     * @apiNote package private, because I only see need for this concept in the Aggra framework.
     */
    static <M extends Memory<?>> DependencyCallingDevice<M> createForCancelled(GraphCall<?> graphCall,
            Node<M, ?> callingNode, M memory, Observer observer) {
        return new NonInterruptSupportingDependencyCallingDevice<>(callingNode, memory, graphCall, observer,
                NO_PRIMING_RESPONSE, List.of());
    }

    /**
     * Creates an interrupt-supporting (i.e. supporting {@link CancelMode#INTERRUPT}) DependencyCallingDevice by
     * decorating another device. This involves modifying dependency calls to obtain a device-specific lock and then
     * provide a clear, interrupt-free environment for running the dependency call. This also involves modifying the
     * decorated device's closing additionally to clear the interrupt status (suppressing any exceptions encountered in
     * doing so). Finally, this device will also modify/decorate custom cancel actions to obtain the same
     * device-specific lock mentioned previously before running the action. In this way, this device isolates interrupts
     * to Nodes that support them.
     * 
     * @apiNote package private, because I only see need for this concept in the Aggra framework.
     */
    static <M extends Memory<?>> DependencyCallingDevice<M> createInterruptSupporting(
            DependencyCallingDevice<M> decorated, InterruptModifier interruptModifier,
            Consumer<Throwable> interruptClearingExceptionSuppressor) {
        return new InterruptSupportingDependencyCallingDevice<>(decorated, interruptModifier,
                interruptClearingExceptionSuppressor);
    }

    /**
     * A constant representation for when no priming is necessary.
     * 
     * Justification of using a CompletableFuture as a constant: I get the impression from looking at
     * {@link CompletableFuture#completedStage(Object)} that its result can be used as a constant. Looking at the source
     * code gives more confirmation. I would *not* be comfortable doing this with a CompletableFuture directly, since
     * it's mutable. My only fear is a memory leak: that maybe chaining things off of this variable will add compounding
     * memory. In the worst case, I can replace this with a true, non-growing constant CompletionStage (if this one is
     * not).
     */
    private static final Try<CompletionStage<Void>> NO_PRIMING_RESPONSE = Try
            .ofSuccess(CompletableFuture.completedStage(null));

    /**
     * Used by nodes to call their dependencies. This class encapsulates the details of how to do that in order to help
     * prevent Nodes from making mistakes, like using the wrong callers or memories, calling an unmodeled dependency, or
     * making a cyclical call.
     * 
     * @param <M> the type of the Memory for the node call this device is associated with.
     * 
     * @apiNote DependencyCaller is another name I considered for this class, but it would have created confusion with
     *          the existing {@link Caller} class, implying an inheritance relationship when none exists.
     */
    public abstract static class DependencyCallingDevice<M extends Memory<?>> {
        private DependencyCallingDevice() {}

        /**
         * Returns the result of the priming phase. If priming was successful, the response completes when all primed
         * dependencies complete. If priming was a failure, then the response represents that failure. The response
         * should not be modified.
         * 
         * @apiNote package private, because I only see need for this concept in the Aggra framework and since the
         *          response is mutable for efficiency's sake but shouldn't be modified.
         */
        abstract Try<? extends CompletionStage<Void>> getPrimingResponse();

        /**
         * Calls the given dependency node.
         * 
         * @throws MisbehaviorException if this device detects that it's being used after the conditions laid out in
         *         {@link Behavior#run(DependencyCallingDevice)}: later than the latter of the call to run completing or
         *         the response from run completing. This detection is best effort and is not guaranteed. If the user
         *         breaks this promise and detection fails, the behavior of this method is undefined.
         * @throws MisbehaviorException if the dependency is not modeled from the calling Node: i.e. to avoid this
         *         exception, dependency must have been returned from the calling Node's builder method on construction.
         * @throws all the same exceptions as
         *         {@link Node#call(Caller, Memory, io.github.graydavid.aggra.core.GraphCall, Observer)}
         */
        public abstract <T> Reply<T> call(SameMemoryDependency<M, T> dependency);

        /**
         * The same as {@link #call(SameMemoryDependency)}, except uses the executor-accepting
         * {@link Node#call(Caller, Memory, GraphCall, Observer, Executor)} to make the dependency call. As mentioned
         * there, this variant provides better protection against rogue nodes that take too long to return a response
         * from a node call... at the cost of needing to switch threads to run any first-call-related logic.
         * 
         * Note: this functionality/protection is exposed through
         * {@link io.github.graydavid.aggra.nodes.TimeLimitNodes}, so users should check out that node builder to see if
         * it meets their usecase before resorting to calling this method directly.
         * 
         * @apiNote only the same-memory-calling method supports the executor-specifying variant of the node call. This
         *          is due to simplicity, as the main target use case is to protect against rogue nodes, for which
         *          same-memory calling is the most common type of call. The new-memory and ancestor-accessing variants
         *          can introduce an intermediate node to get the same effect, if they want.
         */
        public abstract <T> Reply<T> call(SameMemoryDependency<M, T> dependency, Executor firstCallExecutor);

        /**
         * Creates a new Memory (that will return the provided input) using the current call's Memory and then calls a
         * dependency within that new Memory.
         * 
         * @param memoryFactory creates a new Memory whose input is the same instance as newInput.
         *        <p>
         *        The implementation of the factory should just be a simple creation of a Memory along with, if
         *        necessary, creating any needed ancestor Memorys. There should not be any complicated logic, like
         *        calling other Nodes or otherwise using the Memory during this call. Violating this advice could cause
         *        the wrong caller to be passed between calls (causing confusion in debug information) or could open up
         *        the program to cyclical calls (which could halt the program, since a Reply may be waiting on itself to
         *        complete before completing).
         *        <p>
         *        In addition, clients should not intercept this memory, save it, expose it, and use it after this
         *        method is complete. Aggra assumes that the memory will be used only internal to this method call.
         *        Violating this advice could cause the management of the Memory's MemoryScope to be inconsistent with
         *        its usage, with consequences like never delivering cancellation messages to running Replys.
         *        <p>
         *        As for MemoryScope, although the MemoryFactory interface says the following, I'm including this for
         *        emphasis: similarly, clients should also not intercept the MemoryScope used to create the Memory,
         *        other than to use it to create new Memorys inside of the MemoryFactory method call.
         * @param newInput the input passed to the memoryFactory to create the new Memory.
         * 
         * @throws MisbehaviorException if this device detects that it's being used after the conditions laid out in
         *         {@link Behavior#run(DependencyCallingDevice)}: later than the latter of the call to run completing or
         *         the response from run completing. This detection is best effort and is not guaranteed. If the user
         *         breaks this promise and detection fails, the behavior of this method is undefined.
         * @throws MisbehaviorException if dependencyInNew is not modeled from the calling Node: i.e. to avoid this
         *         exception, dependencyInNew must have been returned from the calling Node's builder method on
         *         construction.
         * @throws MisbehaviorException if the created Memory's scope does not match the scope provided to the
         *         memoryFactory.
         * @throws MisbehaviorException if the created Memory's input does not match newInput.
         * @throws all the same exceptions as
         *         {@link Node#call(Caller, Memory, io.github.graydavid.aggra.core.GraphCall, Observer)}
         * 
         * @param <NI> the type of the input for the new Memory.
         * @param <NM> the type of the new Memory.
         * @param <T> the type of the reply returned by the called dependencyInNew.
         */
        public abstract <NI, NM extends Memory<NI>, T> Reply<T> createMemoryAndCall(
                MemoryFactory<M, NI, NM> memoryFactory, CompletionStage<NI> newInput,
                NewMemoryDependency<NM, T> dependencyInNew);

        /**
         * Same as {@link #createMemoryAndCall(MemoryFactory, CompletionStage, Node)} except without using input to
         * create the new Memory.
         * 
         * @apiNote see the note for {@link #accessAncestorMemoryAndCall(AncestorMemoryAccessor, Node)} about similar
         *          signatures and notes about semantic enforcement.
         */
        public abstract <NM extends Memory<?>, T> Reply<T> createMemoryNoInputAndCall(
                MemoryNoInputFactory<M, NM> memoryFactory, NewMemoryDependency<NM, T> dependencyInNew);

        /**
         * Accesses an ancestor Memory of the current call's Memory and then calls a dependency within that ancestor
         * Memory. "Ancestor" Memory means the Memory is accessible from the current Memory's
         * {@link Memory#getParents()}, or parents of those parents, etc..
         * 
         * @param <AM> the type of the ancestor's Memory.
         * @param <T> the type of the reply returned by the accessed ancestor node.
         * 
         * @throws MisbehaviorException if this device detects that it's being used after the conditions laid out in
         *         {@link Behavior#run(DependencyCallingDevice)}: later than the latter of the call to run completing or
         *         the response from run completing. This detection is best effort and is not guaranteed. If the user
         *         breaks this promise and detection fails, the behavior of this method is undefined.
         * @throws MisbehaviorException if dependencyInAncestor is not modeled from the calling Node: i.e. to avoid this
         *         exception, dependencyInAncestor must have been returned from the calling Node's builder method on
         *         construction.
         * @throws all the same exceptions as
         *         {@link Node#call(Caller, Memory, io.github.graydavid.aggra.core.GraphCall, Observer)}
         * 
         * @apiNote This method has the same signature as
         *          {@link #createMemoryNoInputAndCall(MemoryNoInputFactory, Node)}. However, the semantics are
         *          different. That said, there's currently no enforcement that this method is called with a true,
         *          pre-existing ancestor (as opposed to returning a completely arbitrary one). This enforcement of the
         *          semantics is left to the user, while tooling in the form of Observers to verify/metric at runtime.
         *          Although {@link Memory#getParents()} could be called to verify, it's too likely that the user
         *          forgets to update the return of that method to be a true reflection of its ancestors. The runtime
         *          exception would not be worth the cost of preserving semantics considering that accessing an existing
         *          and creating an arbitrary Memory are both valid actions.
         * 
         */
        public abstract <AM extends Memory<?>, T> Reply<T> accessAncestorMemoryAndCall(
                AncestorMemoryAccessor<M, AM> ancestorMemoryAccessor,
                AncestorMemoryDependency<AM, T> dependencyInAncestor);

        /**
         * Tells this device that it should "ignore" a Reply that it previously yielded through a dependency call (e.g.
         * {@link #call(SameMemoryDependency)}. This both means the Reply *might* be cancelled and that it will always
         * be returned by {@link GraphCall.FinalState#getIgnoredReplies()}. (Note, however, that ignoring a Reply does
         * *not* have any effect on whether the device will wait around for the Reply to complete; that's completely
         * determined by the Nodes DependencyLifetime.)
         * 
         * Ignoring is similar to cancellation, but purposefully different. There is no direct mechanism for consumers
         * to cancel a Reply from one of their dependencies. For a given dependency Node, it would be unfair and
         * unexpected for one consumer Node to be able to cancel the dependency when another consumer Node may still
         * expect the Reply to finish; they're completely independent of one another, after all. Instead, each consumer
         * simply indicates that they don't care about the Reply through ignoring it. That *can* lead to the triggering
         * of the Reply's Reply cancellation signal, but only if the Aggra framework can prove that all potential
         * consumers either won't yield the same Reply from that dependency or have already ignored it themselves.
         * 
         * In practice, this proof can be complex. See {@link Graph#ignoringWillTriggerReplyCancelSignal(Node)} for a
         * list of the conditions. If you rely on triggering, then it would be a good idea either to create a unit test
         * or make use of {@link GraphValidators#ignoringWillTriggerReplyCancelSignal()} to verify that it remains true
         * as your graph evolves over time.
         * 
         * What does cancellation mean, exactly. Well, this method only controls the Reply cancellation signal. See
         * {@link Node#call(Caller, Memory, GraphCall, Observer)} for more info about cancellation signals, triggers,
         * and hooks. So, "it can be cancelled" with regards to this method means that its Reply cancellation signal can
         * be triggered. What that actually means depends on what the cancellation hooks that the dependency has in
         * place actually do. One note to call out: if the hook {@link CancelMode#supportsCustomAction()} and if this
         * call to ignore triggers the Reply cancellation signal, that custom action will be executed during this call
         * to ignore.
         * 
         * Aggra may/is free to expand, in the future, the conditions under which it triggers based on calls to ignore.
         * That depends on whether easy, useful algorithms can be found for making similar kinds of proofs. That's
         * unlikely to happen, though, since the extra cost for tracking every consumer call, just to construct these
         * proofs, is likely not worth the effort for the vast majority of clients. So, if clients want to make sure
         * that ignores lead to cancellations, they should check (in a unit test, maybe) that the conditions above hold
         * and that the dependency responds to cancellation in the way that they want (i.e. it's
         * {@link Node#getCancelMode()} has the desired properties, so that they don't "pull the rug out from
         * themselves" by accidentally changing the dependency they're targeting to one that doesn't respond to the
         * desired type of cancellation at all). There are also other options to ensure quick cancellation, which come
         * from the GraphCall and MemoryScope cancellation signals (again, see the Node#call javadoc for more info).
         * E.g. for the MemoryScope cancellation signal, you could make sure the dependency Node is part of a new,
         * shallow Memory and that consumers don't wait around for it to finish (via {@link DependencyLifetime#GRAPH};
         * that's just one example, though.
         * 
         * One final note: this method is a means of explicitly ignoring a Reply. Aggra will automatically implicitly
         * ignore any still-incomplete Reply after the Node's Behavior completes (or more exactly, just before the
         * Node's Reply is asked to complete itself, which always happens... unlike the Behavior completing, which is
         * the usual case, but sometimes doesn't happen due to error cases). Now, this is a best-effort check: there is
         * an obvious race condition where Behavior may complete with one of its Replies still running, and that Reply
         * may then complete before the implicit ignoring happens. In that case, the Reply would not be
         * implicitlyIgnored. If users want a guarantee, they should always call this explicit ignore method for any
         * Reply that they want to be available in {@link GraphCall.FinalState#getIgnoredReplies()}. As far as
         * similarities and differences, implicit ignoring leads to cancellation under the same conditions as explicit
         * ignoring, it's just that they happen for different reasons at different points in a Node's lifecycle.
         * 
         * @throws MisbehaviorException if this device detects that it's being used after the conditions laid out in
         *         {@link Behavior#run(DependencyCallingDevice)}: later than the latter of the call to run completing or
         *         the response from run completing. This detection is best effort and is not guaranteed. If the user
         *         breaks this promise and detection fails, the behavior of this method is undefined.
         * @throws MisbehaviorException if this DependencyCallingDevice can prove that it didn't call a dependency to
         *         yield this Reply. For efficiency's sake, this device simply checks that the Reply's Node is a modeled
         *         dependency, which is 90% of the way there. However, in the future, DependencyCallingDevice is free to
         *         change the implementation to compare the provided reply with all of those dependency Replys that this
         *         device is tracking.
         */
        public abstract void ignore(Reply<?> reply);

        /**
         * (Weakly) closes this DependencyCallingDevice so that no further node calls should be made through it. Node
         * will call this method after the calling node is complete. This is usually after the Node's Behavior has
         * completed, but could also happen earlier (e.g. {@link PrimingFailureStrategy#FAIL_FAST_STOP} and the priming
         * phase throws an exception). Anyway, this method is always called just before the Node's Reply is asked to
         * start completing itself.
         * 
         * As a part of this closure, this method will figure out which Replies are still incomplete and
         * {@link GraphCall#implicitlyIgnore(Collection)} them. This always happens, even though the Node's minimum
         * DependencyLifetime may make the Node Reply wait around until those dependencies are finished. The idea is
         * that, either way, Behavior has implicitly ignored those incomplete Replys (if the Behavior hasn't run at all,
         * perhaps due to an error in the priming phase, the same idea still applies). Now, this is a best-effort check:
         * there is an obvious race condition where Behavior may complete with one of its Replies still running, and
         * that Reply may then complete before this weaklyClose method runs. In that case, the Reply would not be
         * implicitlyIgnored. If users want a guarantee, they should always call {@link #ignore(Reply)} for any Reply
         * that they want to be available in {@link GraphCall.FinalState#getIgnoredReplies()}.
         * 
         * In addition, like {@link #ignore(Reply)}, any implicitly ignored Replys may also be cancelled as well; the
         * conditions for that happening are the same, so you should read the "ignore" javadoc for more info. Unlike
         * {@link #ignore(Reply)}, though, if ignore leads to cancellation which leads to a custom cancellation action
         * being executed, and that action throws an exception; that exception will *not* be propagated. Instead, it
         * will be suppressed and be available in {@link GraphCall.FinalState#getUnhandledExceptions()}. This is
         * intentional, since implicit ignoring/cancellation may be disconnected from any user code that could handle
         * it.
         * 
         * The "weak" part of the method name comes from the fact that this method puts the onus on the user of not
         * using a DependencyCallingDevice after its Behavior's response has completed. This is clearly specified in the
         * {@link Behavior#run(DependencyCallingDevice)} API as a user responsibility. While this and other
         * externally-facing DependencyCallingDevice methods *try* their best to verify that these conditions are met,
         * neither method *guarantees* detection, and behavior is undefined if the user breaks them.
         * 
         * Implementations of this method are allowed to perform side effects consistent with their type. E.g.
         * interrupt-supporting devices are allowed to clear interrupt status.
         * 
         * @throws MisbehaviorException if it's been detected (which is not a guarantee) that this
         *         DependencyCallingDevice has already been closed through this method before. If the user breaks this
         *         promise and detection fails, the behavior of this method is undefined.
         * 
         * @apiNote Although it would be possible to detect perfectly/"strongly" the conditions for closing a
         *          DependencyCallingDevice, it would require thread synchronization. Given the simplicity of the API
         *          and the (admittedly probably minor, although non-trivial) cost of thread synchronization, I've
         *          chosen to user a "best effort" approach instead. The guarantees in the documentation are lenient
         *          enough that I can switch approaches, if desired.
         */
        abstract FinalState weaklyClose();

        /** Potentially modifies/decorates action to provide features relevant to the current device. */
        abstract CustomCancelAction modifyCustomCancelAction(CustomCancelAction action);
    }

    /**
     * The final state of a DependencyCallingDevice after it's been {@link DependencyCallingDevice#weaklyClose()}-ed.
     * Contains the data necessary to implement a Node's {@link DependencyLifetime} and the consequences of it (i.e.
     * ignoring Replys if necessary). Part of that job is communicating all of the dependency Replies that were made
     * during the course of the Node call. This includes partitioning those Replys based on whether they were complete
     * or not at the time of FinalState's construction. This partitioning is important to do once in a central location
     * in order to maintain consistency (e.g. incomplete Replies will need to be implicitly ignored, while in some
     * cases, the complete Replies will need to be communicated... and, because Replies complete over time, if those two
     * partitions are computed at different times rather than once together, those computations may get different,
     * inconsistent pictures of what is and what isn't complete).
     * 
     * @apiNote Package private, because only the Aggra framework should know about and deal with this concept.
     */
    static class FinalState {
        private final Set<Reply<?>> allDependencyReplies;
        private final Set<Reply<?>> incompleteDependencyReplies;
        private final Set<Reply<?>> completeDependencyReplies;

        private FinalState(Set<Reply<?>> allDependencyReplies, Set<Reply<?>> incompleteDependencyReplies,
                Set<Reply<?>> completeDependencyReplies) {
            this.allDependencyReplies = allDependencyReplies;
            this.incompleteDependencyReplies = incompleteDependencyReplies;
            this.completeDependencyReplies = completeDependencyReplies;
        }

        /** All of Replys from all dependency calls the DependencyCallingDevice has made. */
        Set<Reply<?>> getAllDependencyReplies() {
            return allDependencyReplies;
        }

        /**
         * The portion of {@link #getAllDependencyReplies()} that were incomplete when this FinalState was created.
         * Because Replies complete over time, this partition may not reflect the current reality, but it will give a
         * consistent picture over multiple invocations.
         */
        Set<Reply<?>> getIncompleteDependencyReplies() {
            return incompleteDependencyReplies;
        }

        /**
         * The portion of {@link #getAllDependencyReplies()} that were complete when this FinalState was created.
         * Because Replies complete over time, this partition may not reflect the current reality, but it will give a
         * consistent picture over multiple invocations.
         */
        Set<Reply<?>> getCompleteDependencyReplies() {
            return completeDependencyReplies;
        }

        /**
         * Creates a Final state from all of the Replies from all dependency calls made by a DependencyCallingDevice.
         */
        static FinalState of(Set<Reply<?>> dependencyReplies) {
            Map<Boolean, Set<Reply<?>>> isCompleteToReplies = dependencyReplies.stream()
                    .collect(Collectors.partitioningBy(Reply::isDone, Collectors.toUnmodifiableSet()));
            return new FinalState(dependencyReplies, isCompleteToReplies.get(false), isCompleteToReplies.get(true));
        }

        private static FinalState EMPTY = FinalState.of(Set.of());

        /** A FinalState that contains no state. A convenient method for unit tests... and others? */
        static FinalState empty() {
            return EMPTY;
        }
    }

    /**
     * A DependencyCallingDevices that does not support interrupt status: not supporting {@link CancelMode#INTERRUPT}.
     */
    public static class NonInterruptSupportingDependencyCallingDevice<M extends Memory<?>>
            extends DependencyCallingDevice<M> {
        private final Node<M, ?> callingNode;
        private final M memory;
        private final GraphCall<?> graphCall;
        private final Observer observer;
        private final Try<? extends CompletionStage<Void>> primingResponse;
        private final Collection<Reply<?>> dependencyReplies;
        // Only provides weak closure: not declared volatile to avoid costs of synchronization
        private boolean closed;

        private NonInterruptSupportingDependencyCallingDevice(Node<M, ?> callingNode, M memory, GraphCall<?> graphCall,
                Observer observer, Try<? extends CompletionStage<Void>> primingResponse,
                Collection<Reply<?>> dependencyReplies) {
            this.callingNode = callingNode;
            this.memory = memory;
            this.graphCall = graphCall;
            this.observer = observer;
            this.primingResponse = primingResponse;
            this.dependencyReplies = dependencyReplies;
        }

        @Override
        Try<? extends CompletionStage<Void>> getPrimingResponse() {
            return primingResponse;
        }

        @Override
        public <T> Reply<T> call(SameMemoryDependency<M, T> dependency) {
            requireValidDependencyCall(dependency);
            return createAndRememberDependencyReply(dependency,
                    () -> dependency.getNode().call(callingNode, memory, graphCall, observer));
        }

        @Override
        public <T> Reply<T> call(SameMemoryDependency<M, T> dependency, Executor firstCallExecutor) {
            requireValidDependencyCall(dependency);
            return createAndRememberDependencyReply(dependency,
                    () -> dependency.getNode().call(callingNode, memory, graphCall, observer, firstCallExecutor));
        }

        private void requireValidDependencyCall(Dependency<?, ?> dependencyToCall) {
            Objects.requireNonNull(dependencyToCall);
            requireNotClosedCalling(dependencyToCall);
            requireModeledDependency(dependencyToCall);
        }

        private void requireNotClosedCalling(Dependency<?, ?> dependencyToCall) {
            if (closed) {
                String message = String.format(
                        "Cannot call dependency once a DependencyCallingDevice has been closed but found a call from '%s''s behavior to '%s'",
                        callingNode.getSynopsis(), dependencyToCall);
                throw new MisbehaviorException(message);
            }
        }

        private void requireModeledDependency(Dependency<?, ?> dependency) {
            if (!isModeledDependency(dependency)) {
                String message = String.format("'%s' tried to call '%s' without modeling it as a dependency",
                        callingNode.getSynopsis(), dependency);
                throw new MisbehaviorException(message);
            }
        }

        private boolean isModeledDependency(Dependency<?, ?> dependency) {
            return callingNode.getDependencies().contains(dependency);
        }

        private <T> Reply<T> createAndRememberDependencyReply(Dependency<?, ?> dependency,
                Supplier<Reply<T>> replySupplier) {
            Reply<T> reply = replySupplier.get();
            // Optimization: we already called all primed dependencies in this class's static factory method and put
            // them in dependencyReplies. There's no reason to add a duplicate.
            if (dependency.getPrimingMode() != PrimingMode.PRIMED) {
                dependencyReplies.add(reply);
            }
            return reply;
        }

        @Override
        public <NI, NM extends Memory<NI>, T> Reply<T> createMemoryAndCall(MemoryFactory<M, NI, NM> memoryFactory,
                CompletionStage<NI> newInput, NewMemoryDependency<NM, T> dependencyInNew) {
            return createMemoryNoInputAndCall(MemoryFactory.toNoInputFactory(memoryFactory, newInput), dependencyInNew);
        }

        @Override
        public <NM extends Memory<?>, T> Reply<T> createMemoryNoInputAndCall(MemoryNoInputFactory<M, NM> memoryFactory,
                NewMemoryDependency<NM, T> dependencyInNew) {
            requireValidDependencyCall(dependencyInNew);
            MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(dependencyInNew.getNode(),
                    memory.getScope());
            NM newMemory = memoryFactory.createNew(scope, memory);
            MemoryBridges.requireMemoryHasScope(newMemory, scope);
            Reply<T> reply = createAndRememberDependencyReply(dependencyInNew,
                    () -> dependencyInNew.getNode().call(callingNode, newMemory, graphCall, observer));
            // Optimization: only set up scope cancellation if scope is responsive to it
            if (scope.isResponsiveToCancelSignal()) {
                /*
                 * Note: it's possible that the whenComplete action will be called after the response from
                 * GraphCall#weaklyClose completes. This is one of the only places in the Aggra framework where this is
                 * allowed to happen (search for "AFTER_WEAKLY_CLOSE" for the others). This is both unlikely to happen
                 * and would be a no-op if it did (since all fully-responsive-to-cancel memory scopes are guaranteed to
                 * already have had triggerCancelSignal called and not-fully-responsive scopes either do nothing or just
                 * set a boolean in response to having triggerCancelSignal called), so it's considered acceptable.
                 */
                reply.whenComplete((ignore1, ignore2) -> scope.triggerCancelSignal());
            }
            return reply;
        }

        @Override
        public <AM extends Memory<?>, T> Reply<T> accessAncestorMemoryAndCall(
                AncestorMemoryAccessor<M, AM> ancestorMemoryAccessor,
                AncestorMemoryDependency<AM, T> dependencyInAncestor) {
            requireValidDependencyCall(dependencyInAncestor);
            AM ancestorMemory = ancestorMemoryAccessor.getAncestorOf(memory);
            return createAndRememberDependencyReply(dependencyInAncestor,
                    () -> dependencyInAncestor.getNode().call(callingNode, ancestorMemory, graphCall, observer));
        }

        @Override
        public void ignore(Reply<?> reply) {
            Objects.requireNonNull(reply);
            requireNotClosedIgnoring(reply);
            requireReplyToIgnoreYieldedByCallFromThisDevice(reply);

            graphCall.explicitlyIgnore(reply);
        }

        private void requireNotClosedIgnoring(Reply<?> replyToIgnore) {
            if (closed) {
                String message = String.format(
                        "Cannot ignore reply once a DependencyCallingDevice has been closed but found an ignore from '%s''s behavior to '%s'",
                        callingNode.getSynopsis(), replyToIgnore);
                throw new MisbehaviorException(message);
            }
        }

        /**
         * Does its best to verify that the replyToIgnore was yielded by a call from this device. In practice, all this
         * method does is verify that the Reply's node is modeled as a dependency. Ideally, this method would check
         * dependencyReplies; however, that would be inefficient: O(n) lookup or changing dependencyReplies to a Set
         * (which would make the addition of new Replys more inefficient), plus dependencyReplies (in cases where
         * ignores would matter) is a concurrent collection (for which there is an extra cost to accessing).
         * 
         * This is acceptable, since even if replyToIgnore was not yielded by a call from this device, there will not be
         * any heavy consequences. First, replyToIgnore would be added to GraphCall as a tracked ignored Reply, which is
         * low cost. Second, the only way this could yield an unintended cancellation is if there was a single consumer
         * node (i.e. if replyToIgnore were generated by another consumer node, that would imply multiple consumer
         * nodes, which according to {@link #ignore(Reply)} would not lead to a cancellation). For that to be true
         * another device for the same callingNode would have had to yield the replyToIgnore. I find that highly
         * unlikely, and even if true, somewhat acceptable, since devices are tightly bound to their callingNodes: two
         * devices for the same calling Node conspiring to cancel a Reply from one device in another device... is
         * acceptable given the costs of protecting against it.
         */
        private void requireReplyToIgnoreYieldedByCallFromThisDevice(Reply<?> replyToIgnore) {
            if (!callingNode.getDependencyNodes().contains(replyToIgnore.getNode())) {
                String message = String.format("'%s' tried to ignore '%s' without first calling it as a dependency",
                        callingNode.getSynopsis(), replyToIgnore);
                throw new MisbehaviorException(message);
            }
        }

        @Override
        FinalState weaklyClose() {
            requireNotReclosure();
            closed = true;

            Set<Reply<?>> finalDependencyReplies = dependencyReplies.stream().collect(Collectors.toUnmodifiableSet());
            FinalState finalState = FinalState.of(finalDependencyReplies);
            graphCall.implicitlyIgnore(finalState.getIncompleteDependencyReplies());
            return finalState;
        }

        private void requireNotReclosure() {
            if (closed) {
                throw new MisbehaviorException("A DependencyCallingDevice can only be closed once");
            }
        }

        /** Returns the action as is: no need to modify it for this device. */
        @Override
        CustomCancelAction modifyCustomCancelAction(CustomCancelAction action) {
            return action;
        }
    }

    /**
     * A DependencyCallingDevice that supports {@link CancelMode#INTERRUPT}. This means making sure that interrupts are
     * isolated to the targeted Node and don't leak out during dependency calls or chained onto the completion of a
     * Reply, as outlined in {@link BehaviorWithCustomCancelAction#cancelActionMayInterruptIfRunning()}.
     * 
     * For isolating interrupts during dependency calls, this device maintains an internal lock. At the beginning of any
     * dependency call (i.e. to {@link #call(SameMemoryDependency)} or any other method involving a {@link Dependency})
     * this device waits to obtain the lock and then holds it until the method call is over. Furthermore, after the lock
     * is obtained, it uses its {@link InterruptModifier} to save the current interrupt status and clear it. Only then
     * is the dependency call made. Once the dependency call is finished, the device uses the InterruptModifier to
     * restore the interrupt status to its previous value.
     * 
     * Meanwhile, {@link #modifyCustomCancelAction(CustomCancelAction)} modifies the provided action to obtain and hold
     * the same lock while the action runs. Since only the CustomCancelAction can deliver interrupts, this scheme helps
     * ensure that dependency calls are isolated from any interrupt modifications that the CustomCancelAction might
     * make.
     * 
     * One additional note about dependency calls: the interrupt isolation logic is only run for unprimed dependencies.
     * Since primed dependencies are guaranteed to finish before any Behavior is run (and so before any
     * CustomCancelAction can be created), and since dependency calls are inherently cached, there's no need to protect
     * the interrupt status during explicit primed calls made by any Behavior.
     * 
     * For isolating interrupts after completion of a Reply, this device uses InterruptModifier to clear the interrupt
     * status upon calling {@link #weaklyClose()}. There's no need to obtain the device's lock for this clearing, since
     * Behaviors are forbidden from making dependency calls once weaklyClose is called. Any exception in this clearing
     * will be suppressed with the supplied interruptClearingExceptionSuppressor (this does not happen for the clearing
     * mentioned in a previous paragraph: all exceptions for InterruptModifier there are propagated back to the client).
     */
    public static class InterruptSupportingDependencyCallingDevice<M extends Memory<?>>
            extends DependencyCallingDevice<M> {
        private final DependencyCallingDevice<M> decorated;
        private final InterruptModifier interruptModifier;
        private final Consumer<Throwable> interruptClearingExceptionSuppressor;
        private final Lock modifyInterrupt;

        private InterruptSupportingDependencyCallingDevice(DependencyCallingDevice<M> decorated,
                InterruptModifier interruptModifier, Consumer<Throwable> interruptClearingExceptionSuppressor) {
            this.decorated = Objects.requireNonNull(decorated);
            this.interruptModifier = Objects.requireNonNull(interruptModifier);
            this.interruptClearingExceptionSuppressor = Objects.requireNonNull(interruptClearingExceptionSuppressor);
            this.modifyInterrupt = new ReentrantLock();
        }

        @Override
        Try<? extends CompletionStage<Void>> getPrimingResponse() {
            return decorated.getPrimingResponse();
        }

        @Override
        public <T> Reply<T> call(SameMemoryDependency<M, T> dependency) {
            return runLockedIsolatingInterrupt(dependency, dep -> decorated.call(dep));
        }

        @Override
        public <T> Reply<T> call(SameMemoryDependency<M, T> dependency, Executor firstCallExecutor) {
            return runLockedIsolatingInterrupt(dependency, dep -> decorated.call(dep, firstCallExecutor));
        }

        private <D extends Dependency<?, ?>, T> T runLockedIsolatingInterrupt(D dependency, Function<D, T> call) {
            // As mentioned in the class javadoc, no need to isolate interrupts for primed dependencies...
            if (dependency.getPrimingMode() == PrimingMode.PRIMED) {
                return call.apply(dependency);
            }

            // ... But will need to isolate interrupts for unprimed dependencies
            modifyInterrupt.lock();
            try {
                return runIsolatingInterrupt(() -> call.apply(dependency));
            } finally {
                modifyInterrupt.unlock();
            }
        }

        private <T> T runIsolatingInterrupt(Supplier<T> call) {
            boolean oldInterruptStatus = interruptModifier.clearCurrentThreadInterrupt();
            try {
                return call.get();
            } finally {
                interruptModifier.setCurrentThreadInterrupt(oldInterruptStatus);
            }
        }

        @Override
        public <NI, NM extends Memory<NI>, T> Reply<T> createMemoryAndCall(MemoryFactory<M, NI, NM> memoryFactory,
                CompletionStage<NI> newInput, NewMemoryDependency<NM, T> dependencyInNew) {
            return runLockedIsolatingInterrupt(dependencyInNew,
                    dep -> decorated.createMemoryAndCall(memoryFactory, newInput, dep));
        }

        @Override
        public <NM extends Memory<?>, T> Reply<T> createMemoryNoInputAndCall(MemoryNoInputFactory<M, NM> memoryFactory,
                NewMemoryDependency<NM, T> dependencyInNew) {
            return runLockedIsolatingInterrupt(dependencyInNew,
                    dep -> decorated.createMemoryNoInputAndCall(memoryFactory, dep));
        }

        @Override
        public <AM extends Memory<?>, T> Reply<T> accessAncestorMemoryAndCall(
                AncestorMemoryAccessor<M, AM> ancestorMemoryAccessor,
                AncestorMemoryDependency<AM, T> dependencyInAncestor) {
            return runLockedIsolatingInterrupt(dependencyInAncestor,
                    dep -> decorated.accessAncestorMemoryAndCall(ancestorMemoryAccessor, dep));
        }

        @Override
        public void ignore(Reply<?> reply) {
            decorated.ignore(reply);
        }

        @Override
        FinalState weaklyClose() {
            // Call decorated first to activate DCD's best-effort attempt to prevent further dependency calls...
            FinalState finalState = decorated.weaklyClose();
            // ... before trying to clear interrupts, which could interfere with those calls
            tryClearCurrentThreadInterrupt();
            return finalState;
        }

        private void tryClearCurrentThreadInterrupt() {
            try {
                interruptModifier.clearCurrentThreadInterrupt();
            } catch (Throwable t) {
                interruptClearingExceptionSuppressor.accept(t);
            }
        }

        /**
         * Decorates the action to make sure that it obtains this device's lock during the duration of the running of
         * the action.
         */
        @Override
        CustomCancelAction modifyCustomCancelAction(CustomCancelAction action) {
            return mayInterrupt -> {
                modifyInterrupt.lock();
                try {
                    action.run(mayInterrupt);
                } finally {
                    modifyInterrupt.unlock();
                }
            };
        }
    }
}
