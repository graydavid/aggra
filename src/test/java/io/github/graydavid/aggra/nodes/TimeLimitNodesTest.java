package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.TestData.nodeBackedBy;
import static io.github.graydavid.aggra.core.TestData.nodeReturningValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Dependencies;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.Graph;
import io.github.graydavid.aggra.core.GraphCall;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.PrimingFailureStrategy;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.TimeLimitNodes.CallTimeExecutorStarter;
import io.github.graydavid.aggra.nodes.TimeLimitNodes.CreationTimeExecutorStarter;
import io.github.graydavid.aggra.nodes.TimeLimitNodes.FiniteTimeout;
import io.github.graydavid.aggra.nodes.TimeLimitNodes.Timeout;
import io.github.graydavid.onemoretry.Try;

public class TimeLimitNodesTest {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Test
    public void timeLimitTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(TimeLimitNodes.TIME_LIMIT_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void startNodeThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> TimeLimitNodes.startNode(null, TestMemory.class));
        assertThrows(NullPointerException.class, () -> TimeLimitNodes.startNode(Role.of("role"), null));
    }

    @Test
    public void containsSetSettingsWithoutExecutorNodeOrTimeout() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(12);
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout()
                .graphValidatorFactory(validatorFactory)
                .timeLimitedCall(dependency);

        assertThat(timeLimited.getGraphValidatorFactories(), contains(validatorFactory));
        assertThat(timeLimited.getType(), is(TimeLimitNodes.TIME_LIMIT_TYPE));
        assertThat(timeLimited.getRole(), is(Role.of("time-limited")));
        assertThat(timeLimited.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(dependency, PrimingMode.UNPRIMED)));
        assertThat(timeLimited.getPrimingFailureStrategy(), is(PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE));
        assertThat(timeLimited.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_DIRECT));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(12);
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout()
                .graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .timeLimitedCall(dependency);

        assertThat(timeLimited.getGraphValidatorFactories(), empty());
    }

    @Test
    public void includesExecutorNodeAndTimeoutNodeInDependenciesWhenSet() {
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .get(() -> Executors.newSingleThreadExecutor());
        Node<TestMemory, Timeout> timeoutNode = FunctionNodes.synchronous(Role.of("timeout"), TestMemory.class)
                .getValue(FiniteTimeout.of(1, TimeUnit.MINUTES));
        Node<TestMemory, Integer> dependency = nodeReturningValue(12);

        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executorNode(executorNode)
                .timeoutNode(timeoutNode)
                .timeLimitedCall(dependency);

        assertThat(timeLimited.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(dependency, PrimingMode.UNPRIMED),
                        Dependencies.newSameMemoryDependency(executorNode, PrimingMode.PRIMED),
                        Dependencies.newSameMemoryDependency(timeoutNode, PrimingMode.PRIMED)));
    }

    @Test
    public void callerThreadExecutorCallsDependencyNodeOnCallerThread() {
        Node<TestMemory, Thread> captureRunningThread = runningThreadCaptor();
        Node<TestMemory, Thread> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout()
                .timeLimitedCall(captureRunningThread);
        Thread callerThread = Thread.currentThread();

        Reply<Thread> capturedRunningThread = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(capturedRunningThread.join(), is(callerThread));
    }

    private static Node<TestMemory, Thread> runningThreadCaptor() {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("capturor"))
                .role(Role.of("capture-thread"))
                .build(device -> CompletableFuture.completedFuture(Thread.currentThread()));
    }

    @Test
    public void callerThreadExecutorDoesntBlockCallerThreadOnImmediateButIncompleteResponse() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> initiallyIncomplete = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout()
                .timeLimitedCall(initiallyIncomplete);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(reply.isDone());
        dependencyResponse.complete(56);

        assertThat(reply.join(), is(56));
    }

    @Test
    public void executorThrowsExceptionGivenNullExecutor() {
        assertThrows(NullPointerException.class,
                () -> TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class).executor(null));
    }

    @Test
    public void executorCallsDependencyNodeOnCallerThread() throws InterruptedException, ExecutionException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        Node<TestMemory, Thread> captureRunningThread = runningThreadCaptor();
        Node<TestMemory, Thread> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executor(singleThreadExecutor)
                .neverTimeout()
                .timeLimitedCall(captureRunningThread);
        Thread executorThread = singleThreadExecutor.submit(() -> Thread.currentThread()).get();

        Reply<Thread> capturedRunningThread = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(capturedRunningThread.join(), is(executorThread));
    }

    @Test
    public void executorDoesntBlockExecutorThreadOnImmediateButIncompleteResponse()
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> initiallyIncomplete = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executor(singleThreadExecutor)
                .neverTimeout()
                .timeLimitedCall(initiallyIncomplete);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(singleThreadExecutor.submit(() -> 32).get(5, TimeUnit.SECONDS), is(32));
        assertFalse(reply.isDone());
        dependencyResponse.complete(56);

        assertThat(reply.join(), is(56));
    }

    @Test
    public void executorDoesntBlockCallerThreadOnDelayedResponse() {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch returnResponseFromDependency = new CountDownLatch(1);
        Node<TestMemory, Boolean> delayedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("delay"))
                .role(Role.of("delay"))
                .build(device -> {
                    return Try.callUnchecked(() -> CompletableFuture
                            .completedFuture(returnResponseFromDependency.await(500, TimeUnit.SECONDS)));
                });
        Node<TestMemory, Boolean> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executor(singleThreadExecutor)
                .neverTimeout()
                .timeLimitedCall(delayedDependency);

        Reply<Boolean> receivedSignal = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(receivedSignal.isDone());
        returnResponseFromDependency.countDown();

        assertTrue(receivedSignal.join());
    }

    @Test
    public void executorImmediatelyRecognizesDependencyReplyEvenIfBehaviorIsBlocked() {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch checkDependencyReplyIsKnown = new CountDownLatch(1);
        CountDownLatch returnResponseFromDependency = new CountDownLatch(1);
        Node<TestMemory, Boolean> delayedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("delay"))
                .role(Role.of("delay"))
                .build(device -> {
                    checkDependencyReplyIsKnown.countDown();
                    return Try.callUnchecked(() -> CompletableFuture
                            .completedFuture(returnResponseFromDependency.await(500, TimeUnit.SECONDS)));
                });
        Node<TestMemory, Boolean> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executor(singleThreadExecutor)
                .timeout(1, TimeUnit.NANOSECONDS)
                .timeLimitedCall(delayedDependency);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(timeLimited));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Boolean> receivedSignal = graphCall.call(timeLimited);
        Try.callCatchRuntime(() -> receivedSignal.join());
        CompletableFuture<GraphCall.FinalState> finalState = graphCall.weaklyClose();

        assertFalse(finalState.isDone());
        returnResponseFromDependency.countDown();
        finalState.join();
    }

    @Test
    public void executorNodeCallsDependencyNodeOnCallerThread() throws InterruptedException, ExecutionException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .getValue(singleThreadExecutor);
        Node<TestMemory, Thread> captureRunningThread = runningThreadCaptor();
        Node<TestMemory, Thread> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executorNode(executorNode)
                .neverTimeout()
                .timeLimitedCall(captureRunningThread);
        Thread executorThread = singleThreadExecutor.submit(() -> Thread.currentThread()).get();

        Reply<Thread> capturedRunningThread = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(capturedRunningThread.join(), is(executorThread));
    }

    @Test
    public void executorNodeDoesntBlockExecutorThreadOnImmediateButIncompleteResponse()
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .getValue(singleThreadExecutor);
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> initiallyIncomplete = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executorNode(executorNode)
                .neverTimeout()
                .timeLimitedCall(initiallyIncomplete);

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(singleThreadExecutor.submit(() -> 32).get(5, TimeUnit.SECONDS), is(32));
        assertFalse(reply.isDone());
        dependencyResponse.complete(56);

        assertThat(reply.join(), is(56));
    }

    @Test
    public void executorNodeDoesntBlockCallerThreadOnDelayedResponse() {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .getValue(singleThreadExecutor);
        CountDownLatch returnResponseFromDependency = new CountDownLatch(1);
        Node<TestMemory, Boolean> delayedDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("delay"))
                .role(Role.of("delay"))
                .build(device -> {
                    return Try.callUnchecked(() -> CompletableFuture
                            .completedFuture(returnResponseFromDependency.await(500, TimeUnit.SECONDS)));
                });
        Node<TestMemory, Boolean> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .executorNode(executorNode)
                .neverTimeout()
                .timeLimitedCall(delayedDependency);

        Reply<Boolean> receivedSignal = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(receivedSignal.isDone());
        returnResponseFromDependency.countDown();

        assertTrue(receivedSignal.join());
    }

    @Test
    public void creationTimeExecutorStarterFromThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> CreationTimeExecutorStarter.from(null));
    }

    @Test
    public void creationTimeExecutorStarterUsesPassedExecutorToCallThreads()
            throws InterruptedException, ExecutionException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        CreationTimeExecutorStarter starter = CreationTimeExecutorStarter.from(singleThreadExecutor);
        Node<TestMemory, Thread> captureRunningThread = runningThreadCaptor();
        Node<TestMemory, Thread> timeLimited = starter.startNode(Role.of("time-limited"), TestMemory.class)
                .neverTimeout()
                .timeLimitedCall(captureRunningThread);
        Thread executorThread = singleThreadExecutor.submit(() -> Thread.currentThread()).get();

        Reply<Thread> capturedRunningThread = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(capturedRunningThread.join(), is(executorThread));
    }

    @Test
    public void callTimeExecutorStarterFromThrowsExceptionGivenNullArguments() {
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .get(() -> Executors.newSingleThreadExecutor());

        assertThrows(NullPointerException.class, () -> CallTimeExecutorStarter.from(null, executorNode));
        assertThrows(NullPointerException.class, () -> CallTimeExecutorStarter.from(TestMemory.class, null));
    }

    @Test
    public void callTimeExecutorStarterUsesExecutorFromPassedExecutorNodeToCallThreads()
            throws InterruptedException, ExecutionException {
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        Node<TestMemory, Executor> executorNode = FunctionNodes.synchronous(Role.of("executor"), TestMemory.class)
                .getValue(singleThreadExecutor);
        CallTimeExecutorStarter<TestMemory> starter = CallTimeExecutorStarter.from(TestMemory.class, executorNode);
        Node<TestMemory, Thread> captureRunningThread = runningThreadCaptor();
        Node<TestMemory, Thread> timeLimited = starter.startNode(Role.of("time-limited"))
                .neverTimeout()
                .timeLimitedCall(captureRunningThread);
        Thread executorThread = singleThreadExecutor.submit(() -> Thread.currentThread()).get();

        Reply<Thread> capturedRunningThread = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(capturedRunningThread.join(), is(executorThread));
    }

    @Test
    public void neverTimeoutAllowsForWaitingForIncompleteDependency() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout()
                .timeLimitedCall(dependency);

        Reply<Integer> timeboundReply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(timeboundReply.isDone());
        dependencyResponse.complete(16);

        assertThat(timeboundReply.join(), is(16));
    }

    @Test
    public void neverTimeoutWithDependencyLifetimeAndPrimingFailureStrategyAllowsForWiatingForIncompleteDependency() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .neverTimeout(DependencyLifetime.GRAPH)
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP)
                .timeLimitedCall(dependency);

        Reply<Integer> timeboundReply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertThat(timeLimited.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
        assertThat(timeLimited.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertFalse(timeboundReply.isDone());
        dependencyResponse.complete(16);

        assertThat(timeboundReply.join(), is(16));
    }

    @Test
    public void timeoutThrowsExceptionGivenNullTimeUnit() {
        assertThrows(NullPointerException.class,
                () -> TimeLimitNodes.startNode(Role.of("timebound"), TestMemory.class)
                        .callerThreadExecutor()
                        .timeout(1, null));
    }

    @Test
    public void timeoutSetsDependencyLifetimeToGraphAndAllowsForPrimingFailureStrategyToBeSet() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(45);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .timeout(1, TimeUnit.MINUTES)
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP)
                .timeLimitedCall(dependency);

        assertThat(timeLimited.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
        assertThat(timeLimited.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
    }

    @Test
    public void timeoutCallMirrorsDependencyBeforeTimeout() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .timeout(1, TimeUnit.MINUTES)
                .timeLimitedCall(dependency);

        Reply<Integer> timeboundReply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(timeboundReply.isDone());
        dependencyResponse.complete(16);

        assertThat(timeboundReply.join(), is(16));
    }

    @Test
    public void timeoutCallReturnsTimeoutExceptionOnTimeout() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("timebound"), TestMemory.class)
                .callerThreadExecutor()
                .timeout(1, TimeUnit.NANOSECONDS)
                .timeLimitedCall(dependency);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(timeLimited, dependency));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(dependency);
        Reply<Integer> timeboundReply = graphCall.call(timeLimited);

        Try.callCatchRuntime(timeboundReply::join);
        assertThat(timeboundReply.getEncounteredExceptionNow().get(), instanceOf(TimeoutException.class));
        assertFalse(dependencyReply.isDone());

        dependencyResponse.complete(15);
        assertThat(dependencyReply.join(), is(15));
    }

    @Test
    public void timeoutNodeSetsDependencyLifetimeToGraphAndAllowsForPrimingFailureStrategyToBeSet() {
        Node<TestMemory, Integer> dependency = nodeReturningValue(45);
        Node<TestMemory, Timeout> timeoutNode = FunctionNodes.synchronous(Role.of("timeout"), TestMemory.class)
                .getValue(Timeout.infinite());
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .timeoutNode(timeoutNode)
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP)
                .timeLimitedCall(dependency);

        assertThat(timeLimited.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
        assertThat(timeLimited.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
    }

    @Test
    public void timeoutNodeCallMirrorsDependencyBeforeTimeout() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Timeout> timeoutNode = FunctionNodes.synchronous(Role.of("timeout"), TestMemory.class)
                .getValue(FiniteTimeout.of(1, TimeUnit.MINUTES));
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("time-limited"), TestMemory.class)
                .callerThreadExecutor()
                .timeoutNode(timeoutNode)
                .timeLimitedCall(dependency);

        Reply<Integer> timeboundReply = TestData.callNodeInNewTestMemoryGraph(graphInput, timeLimited);

        assertFalse(timeboundReply.isDone());
        dependencyResponse.complete(16);

        assertThat(timeboundReply.join(), is(16));
    }

    @Test
    public void timeoutNodeCallReturnsTimeoutExceptionOnTimeout() {
        CompletableFuture<Integer> dependencyResponse = new CompletableFuture<>();
        Node<TestMemory, Integer> dependency = nodeBackedBy(dependencyResponse);
        Node<TestMemory, Timeout> timeoutNode = FunctionNodes.synchronous(Role.of("timeout"), TestMemory.class)
                .getValue(FiniteTimeout.of(1, TimeUnit.NANOSECONDS));
        Node<TestMemory, Integer> timeLimited = TimeLimitNodes.startNode(Role.of("timebound"), TestMemory.class)
                .callerThreadExecutor()
                .timeoutNode(timeoutNode)
                .timeLimitedCall(dependency);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"), Set.of(timeLimited, dependency));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Integer> dependencyReply = graphCall.call(dependency);
        Reply<Integer> timeboundReply = graphCall.call(timeLimited);

        Try.callCatchRuntime(timeboundReply::join);
        assertThat(timeboundReply.getEncounteredExceptionNow().get(), instanceOf(TimeoutException.class));
        assertFalse(dependencyReply.isDone());

        dependencyResponse.complete(15);
        assertThat(dependencyReply.join(), is(15));
    }

    @Test
    public void finiteTimeoutOfThrowsExceptionOnNullTimeUnit() {
        assertThrows(NullPointerException.class, () -> FiniteTimeout.of(1, null));
    }

    @Test
    public void timeoutsIdentifyThemsevlesAsFiniteOrInfinite() {
        assertTrue(Timeout.infinite().isInfinite());
        assertFalse(Timeout.infinite().isFinite());
        assertFalse(FiniteTimeout.of(1, TimeUnit.MINUTES).isInfinite());
        assertTrue(FiniteTimeout.of(1, TimeUnit.MINUTES).isFinite());
    }

}
