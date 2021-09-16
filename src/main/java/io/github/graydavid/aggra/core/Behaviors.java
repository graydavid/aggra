/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

import io.github.graydavid.aggra.core.DependencyCallingDevices.DependencyCallingDevice;

/**
 * Houses definitions of different types of intrinsic behaviors that a "communal node" can have and other
 * tightly-related classes: i.e. what the node does.
 */
public class Behaviors {
    private Behaviors() {}

    /**
     * Defines the basic intrinsic behavior a "communal node" can have: i.e. what the node does.
     * 
     * @param <M> the type of the behavior's node's Memory.
     */
    @FunctionalInterface
    public interface Behavior<M extends Memory<?>, T> {
        /**
         * Runs the intrinsic behavior for the node.
         * 
         * This method will be run on the calling Thread. The intent is that this method should always be fast. If
         * creating and completing the response is fast, then doing that in this method is fine. Otherwise,
         * implementations should create and return a response in this method and then do the work needed to complete
         * the response in another thread. If this method takes a long time, it will affect how quickly consumer Nodes
         * run and may cascade to the entire GraphCall.
         * 
         * If you do push the work needed to complete the response onto a different Thread, you should minimize the code
         * run after you complete the response. Ideally, there will be no code run, but realistically, you may have at
         * least some cleanup work to do (e.g. restoring Thread state). Either way, any code run after completing the
         * behavior in that thread will not be reflected in the Node response or otherwise tracked by the Aggra
         * framework in general.
         * 
         * @param device used to call dependencies of this node. Behavior should use this device *only* to make calls to
         *        other (dependency) Nodes. Specifically, don't save Memory instances used during the current Graph Call
         *        and then invoke a new Graph Call internally with them. (Note: creating a new Graph Call with a
         *        completely new Memory is perfectly okay; it's the reuse of Memorys that you're not supposed to save
         *        that's the problem.) Failure to do this represents the calling of unmodeled dependencies, which at
         *        best is hiding information from users and at worst may cause a cyclic call. Given the costs, the Aggra
         *        framework chooses not to protect against the latter.
         * 
         *        Behavior implementations are allowed to use the device until the latter of the following two happens:
         *        the call to this run method completes or the response from this method completes. It is considered to
         *        be an invalid behavior to use the device after this time. The Aggra framework will try to detect this
         *        and throw a {@link MisbehaviorException}, but (due to synchronization costs) this detection is not
         *        guaranteed to be perfect. (Note: this is kind of like how the collections API tries to detect
         *        concurrent modification and will throw {@link ConcurrentModificationException} but doesn't guarantee
         *        that.) This is a programming mistake which you should correct. In the worst case, if the Aggra
         *        framework doesn't detect these calls, you'll be left with background tasks still running after a Graph
         *        Call or resource nodes that are never closed.
         * @return the non-null result of running this behavior. This result will be used to complete {@link Reply} and
         *         memoized.
         * 
         *         The result is a CompletionStage, because it's only ever used for composition. However, be aware that
         *         {@link CompletionStage#toCompletableFuture()} will be invoked, which may impose hidden costs (e.g.
         *         {@link Reply#toCompletableFuture()} creates a defensive copy).
         * 
         *         This method is allowed to throw an exception. The Aggra framework will consider a thrown exception to
         *         be equivalent to a returned CompletionStage representing that same exception. E.g. a thrown "new
         *         IllegalArgumentException()" would be the same as a response of "CompletableFuture.failedFuture(new
         *         IllegalArgumentException())". To any Node consumers, the two types of behavior will be
         *         indistinguishable.
         * 
         *         It's up to the implementor to make sure that either this method throws an exception or returns a
         *         non-null CompletionStage that completes in the future. (If the method returns a null value, this will
         *         cause an exceptional completion of the Reply.) Otherwise, if the returned CompletionStage doesn't
         *         complete in the future, the Reply tied to it never will. This will probably bring processing for the
         *         entire Graph Call to a halt.
         */
        CompletionStage<T> run(DependencyCallingDevice<M> device);
    }

    /**
     * Builds upon {@link Behavior} to define a more-complicated intrinsic behavior that also accepts a composite cancel
     * signal. This signal tells the behavior whether it should cancel itself. This class helps users implement one of
     * the most common cancellation mechanisms: managing a flag that the user polls to figure out whether/when to stop
     * what they're doing.
     * 
     * @param <M> the type of the behavior's node's Memory.
     * 
     * @apiNote Creating a new class for this concept is useful, because composite cancel signals come with a cost: it
     *          forces Reply to manage an extra volatile boolean tracking whether its Reply cancellation signal has been
     *          triggered. Most Nodes don't care about this, so creating an entirely new Behavior class forces users to
     *          opt-in.
     */
    @FunctionalInterface
    public interface BehaviorWithCompositeCancelSignal<M extends Memory<?>, T> {
        /**
         * The same as {@link Behavior#run(DependencyCallingDevice)}, except with the addition of a new,
         * composite-cancel-signal parameter.
         */
        CompletionStage<T> run(DependencyCallingDevice<M> device, CompositeCancelSignal signal);
    }

    /**
     * Supplies a Boolean that tells whether any of a Reply's relevant GraphCall, MemoryScope, or Reply cancellation
     * signals have been triggered (see {@link Node#call(Caller, Memory, GraphCall, CallObservers.Observer)} for more
     * info on signals, triggering, and cancellation hooks). If so, then that's a signal to the behavior that it should
     * cancel itself/stop what it's doing.
     */
    @FunctionalInterface
    public interface CompositeCancelSignal {
        boolean read();
    }

    /**
     * Builds upon {@link BehaviorWithCompositeCancelSignal} to define a more-complicated intrinsic behavior that
     * returns a response with a custom cancellation action. This action is run when the associated GraphCall,
     * MemoryScope, or Reply cancellation signals are triggered. This class helps users implement a more-complicated
     * cancellation, where simply pulling the cancellation status is not enough and a push action is needed in addition.
     * 
     * @param <M> the type of the behavior's node's Memory.
     * 
     * @apiNote Creating a new class for this concept is useful, because custom cancel actions come with a cost, even
     *          beyond a composite cancel signal: it forces Reply to keep track of its cancellation action, MemoryScope
     *          to keep track of the Replies it manages that have custom actions, and GraphCall to keep track of the
     *          MemoryScopes it manages that have Replies with custom actions. Most Nodes don't care about this, so
     *          creating an entirely new Behavior class forces users to opt-in.
     */
    public interface BehaviorWithCustomCancelAction<M extends Memory<?>, T> extends InterruptModifier {
        /**
         * The same as {@link BehaviorWithCompositeCancelSignal#run(DependencyCallingDevice, CompositeCancelSignal)},
         * except returns a response with a custom cancel action.
         */
        CustomCancelActionBehaviorResponse<T> run(DependencyCallingDevice<M> device, CompositeCancelSignal signal);

        /**
         * Answers whether the custom cancel action may use interrupts to achieve its cancellation.
         * 
         * This method must return a consistent answer over time: if it returns true once for a given
         * BehaviorWithCustomCancelAction instance, it must always return true for that instance; and vice-versa if
         * false. In other words, the answer is an immutable property of this instance.
         * 
         * This answer is necessary, because if true, then Aggra takes extra effort to make sure that interrupts are
         * isolated to the targeted behaviors (and so don't spill over into dependencies or any code following the
         * behavior). This effort requires extra synchronization (usage of locks (the only use of lock-based
         * synchronization in Aggra!) and the saving and clearing of interrupt status), so users should be thoughtful
         * when deciding to return true from here. More details below, including consequences and requirements of
         * implementation below.
         * 
         * Best practice is that only code that owns a Thread should modify the Thread's interrupt status. There are
         * already many utilities that help manage this relationship, like ThreadPoolExecutor and FutureTask, that would
         * be impractical/unwise to reimplement in Aggra. At the same time, these utilities aren't flexible enough to
         * handle Aggra's call pattern, where tasks can be intertwined with other tasks on the same Thread (via
         * dependency-calling relationships and general chaining of tasks on the ends of others). In spite of this
         * intertwining, the interrupt status still needs to be isolated to each task. As a compromise, Aggra only takes
         * over certain aspects of interrupt status ownership if this method returns true, and delegates others.
         * 
         * First, as a part of a Behavior calling a dependency, before the call happens, Aggra will save the current
         * Thread's interrupt status and clear it. Once the dependency call is done, Aggra will restore the saved
         * interrupt status before returning control to the dependency-calling behavior.
         * 
         * Second, to account for chaining tasks on the end of other tasks, Aggra will also clear the interrupt status
         * just after the behavior completes (but before the Reply completes -- since tasks are actually chained on the
         * Reply and not the Behavior response -- to make sure any new task chained after the Reply will start with a
         * clean slate).
         * 
         * As for delegation, first consider that a Behavior may spawn any number of threads. Aggra allows the
         * CustomCancelAction to deliver interrupts to any number of those threads. Those threads are of two types.
         * 
         * The first class of Threads is the one that completes the Behavior response. If this Behavior Thread ends up
         * running the Reply completion logic after it (which is determined by timing, Node properties, and the
         * internals of Aggra), then as stated above, Aggra will clear the Thread's interrupt status. Aggra relies on
         * users to deliver any interrupts only when the CustomCancelAction is running (which is guaranteed to finish
         * before Aggra clears the interrupt status) so as not to interfere with this process. On the other hand, if the
         * behavior Thread doesn't run the Reply completion logic, the Behavior Thread will instead finish on its own
         * (i.e. without running any further Aggra logic running on top of it) and joins the second class of Thread in
         * terms of expected properties, which are talked about below. In this case, though, *some* Thread must run the
         * Reply completion logic if not the Behavior Thread. This will end up being some random/unrelated Thread. For
         * simplicity (of Aggra implementation), Aggra is still allowed to clear the interrupt status on this Thread,
         * even though it's unrelated to the Behavior Thread(s). (This is acceptable, because each new task deserves to
         * start the current Thread's interrupt status in a pristine, unset state, regardless of whatever happened on
         * the Thread in the past. Aggra doesn't explicitly do this in general but rather delegates to existing
         * utilities... except in cases like this where those utilities don't support the usecase and Aggra can't be
         * 100% sure about whether it allowed the Thread's interrupt status to be set.)
         * 
         * The second class of Threads is those Threads that don't complete the Behavior response. These Threads will
         * finish on their own without Aggra chaining logic on top of it. Aggra will not modify the interrupt status on
         * any of these Threads. It is totally the responsibility of the user to make sure that happens before that
         * behavior thread can run another task again (outside of Aggra's control). The easiest way to ensure all of
         * this happens is to use ThreadPoolExecutor + FutureTask and to call {@link FutureTask#cancel(boolean)} inside
         * of the CustomCancelAction. This combination makes sure that the interrupt status is clear before starting any
         * new task. In addition, you can override {@link FutureTask#done} to complete the Behavior response, as
         * FutureTask guarantees it will not interrupt the Thread at that point.
         * 
         * Aggra also provides one extra mechanism for interrupts: deciding when this interrupt logic actually takes
         * place. So, normally, Aggra will save, clear, and restore interrupt status on calling dependencies; it will
         * also clear interrupt status after the Behavior response completes. There may be some cases where this is
         * undesirable, such as {@link ThreadPoolExecutor#shutdownNow()} or similar processes that deliver interrupts
         * outside of the CustomCancelAction. Aggra exposes the methods it uses for interrupt modification in
         * {@link #clearCurrentThreadInterrupt()} and {@link #setCurrentThreadInterrupt(boolean)}. Clients can override
         * those to decide when to turn them off. E.g. clients could turn them off just before calling shutdownNow and
         * know that the interrupts will not be overridden.
         * 
         * One last note about locking behavior. If this method returns true, then a lock will be created for the Node
         * call's DependencyCallingDevice. The lock will be obtained on any call to an unprimed dependency (since primed
         * dependencies don't need interrupt overriding, since CustomCancelActions can only be run after the
         * dependencies have been primed). In addition, the lock will also be obtained just before the
         * CustomCancelAction is run. In this way, Aggra makes dependency calls and CustomCancelActions mutually
         * exclusive, thereby making sure that these two processes don't step on each other as far as interrupt status
         * modification is concerned. (Note: the lock doesn't need to be obtained when the Behavior response completes
         * and Aggra needs to clear the Thread status, since as mentioned above, Aggra relies on implementations not to
         * set the interrupt status at this point.) Implementations need to be aware of this so as to prevent deadlock:
         * one Thread obtains the lock in some way and then relies on another Thread obtaining the lock in another way.
         * Normal use makes this situation unlikely (e.g. call {@link FutureTask#cancel(boolean)} in the
         * CustomCancelAction and then just return and making sure that dependencies don't weirdly call back into the
         * same Node, which Aggra strictly forbids and tries to make difficult anyway, but which you can get around by
         * doing weird stuff), but it's worth mentioning.
         */
        boolean cancelActionMayInterruptIfRunning();
    }

    /** A modifier of the current Thread's interrupt status. */
    interface InterruptModifier {
        /**
         * Clears the current Thread's interrupt status, returning what the interrupt status was before. The Aggra
         * framework uses this method to clear the interrupt status before a Behavior calls dependencies and after the
         * Behavior status completes, as mentioned in {@link #cancelActionMayInterruptIfRunning()}. Implementations can
         * override this method not to do any modification of the interrupt status at all, which could allow external
         * entities to take control of the interrupt status instead (e.g. {@link ThreadPoolExecutor#shutdownNow()}.
         * 
         * One possible idea for implementations would be to add a volatile boolean to check whether to allow Aggra to
         * modify the interrupt status. A problem with just a boolean is that it's a two-part process: check the boolean
         * and then modify the interrupt status if allowed. The signal not to modify the interrupt status can come in
         * the middle, and Aggra will still modify it. Maybe that's okay for some implementations, but other
         * implementations can take stricter control and make sure the two-part process happens atomically. Just beware
         * with any synchronizations that this method is a foreign method called while Aggra has the lock mentioned at
         * the end of {@link #cancelActionMayInterruptIfRunning()}. Be careful not to create situations that might end
         * up in deadlock.
         * 
         * As for exceptional behavior, it depends on where calls to this method are made. Once call is made before
         * every dependency call made by a Node's behavior. Aggra will not suppress any exception thrown in this case.
         * Another call is made just before completing the Node's reply. In this case, any exception will be suppressed
         * and made available through {@link GraphCall.FinalState#getUnhandledExceptions()}, wrapped in a
         * {@link InterruptClearingException}. This suppression is desirable, because this case appears in a critical
         * section of code where the Reply's result has already been determined.
         */
        default boolean clearCurrentThreadInterrupt() {
            return Thread.interrupted();
        }

        /**
         * Sets the current Thread's interrupt status to the desired status. The Aggra framework uses this method to
         * restore the previously recorded status (from before a Behavior calls a dependency, as recorded by
         * {@link #clearCurrentThreadInterrupt()}) after a Behavior calls a dependency. As with
         * {@link #clearCurrentThreadInterrupt()}, implementations can override this method to do nothing under similar
         * situations for similar reasons. Similar caveats and warnings apply here, too, since Aggra will still have the
         * lock mentioned in {@link #cancelActionMayInterruptIfRunning()}.
         * 
         * Aggra will not suppress any exception thrown by this method.
         */
        default void setCurrentThreadInterrupt(boolean status) {
            if (status) {
                Thread.currentThread().interrupt();
            } else {
                Thread.interrupted();
            }
        }
    }

    /**
     * Holds the two main parts of the response from running BehaviorWithCustomCancelAction: the behavior response and
     * the custom cancel action that can be run to cancel it.
     */
    public static final class CustomCancelActionBehaviorResponse<T> {
        private final CompletionStage<T> behaviorResponse;
        private final CustomCancelAction cancelAction;

        public CustomCancelActionBehaviorResponse(CompletionStage<T> behaviorResponse,
                CustomCancelAction cancelAction) {
            this.behaviorResponse = Objects.requireNonNull(behaviorResponse);
            this.cancelAction = Objects.requireNonNull(cancelAction);
        }

        public CompletionStage<T> getBehaviorResponse() {
            return behaviorResponse;
        }

        public CustomCancelAction getCancelAction() {
            return cancelAction;
        }
    }

    /**
     * A custom cancel action that can be run to cancel a particular behavior.
     */
    @FunctionalInterface
    public interface CustomCancelAction {
        /**
         * Runs the cancel action. This method will be run on the calling Thread.
         * 
         * There are a couple of important rules that every implementation should follow:<br>
         * * This method should always be fast: don't ever wait around. If this method takes a long time, it will delay
         * other Node calls and Aggra logic.<br>
         * * This method should not simply call {@link CompletableFuture#cancel(boolean)} on the behavior response
         * (assuming the behavior response is a CompletableFuture). CompletableFuture#cancel, according to its javadoc,
         * does nothing beyond simply completing the future with an exceptional response. This will leave any task
         * associated with the behavior running. This is an inefficient and unnecessary waste of resources. This is why
         * there's a CustomerCancelAction concept at all rather than just reusing CompletableFuture#cancel.<br>
         * * A generalization of the previous point is that this method and its corresponding behavior should cooperate
         * in such a way as to minimize the amount of work still running after the behavior's response is completed.<br>
         * * This method should not cause the cancellation of any logic that would complete the behavior's response.
         * Said another way, this method and the node's behavior should cooperate such that the behavior's response is
         * guaranteed to complete. One easy mistake to make here is to use an ExecutorService to run the task that
         * completes the behavior's response, save the future from that submission, and implement the custom action by
         * calling {@link Future#cancel(boolean)}. The problem is that cancelling a future may cause the associated task
         * never to run at all, which means that the behavior's response would never complete, causing GraphCall
         * processing to halt. What's worse, this is timing dependent: it's only if the call to cancel happens before
         * the task is run that the task will never be run; otherwise, if the task has already started (which will be
         * true in probably the majority of cases), the running task will receive the cancellation signal and complete
         * the behavior. This is an antipattern you should watch out for.
         * 
         * Aggra may run a cancel action at any time after their Behavior has created them until the Behavior response
         * completes and triggers the start of the associated Reply's completion. If the cancel action is still running
         * when the Behavior completes, the associated Reply's completion will wait until the cancel action is finished.
         * Although this waiting is lockless/doesn't consume any threads, it still delays the Node and its consumers.
         * So, implementors should be aware of this potential delay and design their cancel actions accordingly: make
         * sure the cancel actions send their signals and then quickly finish.
         * 
         * Implementations are allowed to throw exceptions from this method. However, as mentioned in
         * {@link Node#call(Caller, Memory, GraphCall, Observer)}, Aggra will suppress any exception and make it
         * available through {@link GraphCall.FinalState#getUnhandledExceptions()}, wrapped in a
         * {@link CustomCancelActionException}.
         * 
         * @param mayInterruptIfRunning indicates whether this cancel action is allowed to interrupt as a part of
         *        cancellation. Implementations must only use interrupts when this parameter is true; otherwise,
         *        interrupts will spill over between different behaviors. For the same reason, this interrupt must be
         *        set only during the the duration of the CustomCancelAction (i.e. the start of this run method
         *        happens-before any interrupt is set which happens-before the end of this run method).
         * 
         *        The value of the parameter is equal to
         *        {@link BehaviorWithCustomCancelAction#cancelActionMayInterruptIfRunning()} from the associated
         *        behavior that produced this cancel action, as polled by Aggra. Aggra plumbs the value from that method
         *        here in order to help clients better keep this value in-sync with the actual cancellation code. To
         *        facilitate that, implementations should base their code solely on the value of the passed-in
         *        parameter.
         */
        void run(boolean mayInterruptIfRunning);
    }

    /** A wrapper that describes exceptions experienced during CustomCancelActions. */
    public static final class CustomCancelActionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reply<?> reply;
        private final Memory<?> memory;

        /**
         * Creates a new CustomCancelActionException, where cause is the exception encountered during the running of the
         * custom cancel action and the other parameters describe Reply and Memory associated with the action.
         */
        public CustomCancelActionException(Throwable cause, Reply<?> reply, Memory<?> memory) {
            super(calculateMessage(reply, memory), cause);
            this.reply = Objects.requireNonNull(reply);
            this.memory = Objects.requireNonNull(memory);
        }

        private static String calculateMessage(Reply<?> reply, Memory<?> memory) {
            return String.format("Failed executing custom cancel action: <%s, %s>", reply, memory);
        }

        public Reply<?> getReply() {
            return reply;
        }

        public Memory<?> getMemory() {
            return memory;
        }
    }

    /**
     * A wrapper that describes exceptions experienced during one type of call to
     * {@link BehaviorWithCustomCancelAction#clearCurrentThreadInterrupt()}.
     */
    public static final class InterruptClearingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reply<?> reply;
        private final Memory<?> memory;

        /**
         * Creates a new InterruptClearingException, where cause is the exception encountered during the clearing of the
         * interrupt status and the other parameters describe Reply and Memory associated with the clear.
         */
        public InterruptClearingException(Throwable cause, Reply<?> reply, Memory<?> memory) {
            super(calculateMessage(reply, memory), cause);
            this.reply = Objects.requireNonNull(reply);
            this.memory = Objects.requireNonNull(memory);
        }

        private static String calculateMessage(Reply<?> reply, Memory<?> memory) {
            return String.format("Failed clearing interrupt status: <%s, %s>", reply, memory);
        }

        public Reply<?> getReply() {
            return reply;
        }

        public Memory<?> getMemory() {
            return memory;
        }
    }
}
