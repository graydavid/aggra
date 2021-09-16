package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.CallObservers.Observer;
import io.github.graydavid.aggra.core.Graph;
import io.github.graydavid.aggra.core.GraphCall;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.FunctionNodes.SpecifyFunctionAndDependencies;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the synchronous nodes created in FunctionNodes. You can find other FunctionNodes-related tests under
 * FunctionNodes_*Test classes.
 */
public class FunctionNodes_SynchronousTest extends FunctionNodes_TestBase {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    @Override
    protected Type functionType() {
        return FunctionNodes.SYNCHRONOUS_FUNCTION_TYPE;
    }

    @Override
    protected SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role) {
        return FunctionNodes.synchronous(Role.of(role), TestMemory.class);
    }

    @Test
    public void synchronousFunctionTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(FunctionNodes.SYNCHRONOUS_FUNCTION_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void runsOnCallingThreadWhenNoDependencies() {
        Thread callingThread = Thread.currentThread();
        Node<TestMemory, Thread> captureExecutingThread = functionNodesToTest("capture-calling-thread")
                .get(Thread::currentThread);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, captureExecutingThread).join(), is(callingThread));
    }

    @Test
    public void runsOnDependentThreadWhenDependenciesNotFinishedBeforeComposition() {
        CountDownLatch synchronousBehaviorComposed = new CountDownLatch(1);
        Executor asynchronousExecutor = Executors.newCachedThreadPool();
        Node<TestMemory, Thread> captureAsynchronousExecutingThread = FunctionNodes
                .asynchronous(Role.of("capture-asynchronous-executing-thread"), TestMemory.class)
                .executor(asynchronousExecutor)
                .get(() -> {
                    Try.runUnchecked(() -> synchronousBehaviorComposed.await());
                    return Thread.currentThread();
                });
        Node<TestMemory, Thread> waitForAsynchronousThenCaptureExecutingThread = functionNodesToTest(
                "wait-for-asynchronous-then-capture-executing-thread")
                        .apply(asynchronousThread -> Thread.currentThread(), captureAsynchronousExecutingThread);
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("top-level-caller"),
                Set.of(waitForAsynchronousThenCaptureExecutingThread, captureAsynchronousExecutingThread));
        GraphCall<TestMemory> graphCall = graph.openCancellableCall(scope -> new TestMemory(scope, graphInput),
                Observer.doNothing());

        Reply<Thread> synchronousExecutingThreadFuture = graphCall.call(waitForAsynchronousThenCaptureExecutingThread);
        // If we didn't have this synchronization point, the asynchronous function may finish before the synchronous
        // function is composed with it. In cases like that, the composition is run immediately on the current thread
        // rather than the asynchronous thread
        synchronousBehaviorComposed.countDown();
        Thread synchronousExecutingThread = synchronousExecutingThreadFuture.join();
        Thread asynchronousExecutingThread = graphCall.call(captureAsynchronousExecutingThread).join();

        assertThat(synchronousExecutingThread, is(asynchronousExecutingThread));
    }
}
