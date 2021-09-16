package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.FunctionNodes.CallTimeExecutorAsynchronousStarter;
import io.github.graydavid.aggra.nodes.FunctionNodes.SpecifyFunctionAndDependencies;
import io.github.graydavid.onemoretry.Try;

/**
 * Tests the call-time executor asynchronous nodes created in FunctionNodes. You can find other FunctionNodes-related
 * tests under FunctionNodes_*Test classes.
 */
public class FunctionNodes_CallTimeExecutorAsynchronousTest extends FunctionNodes_TestBase {
    private final Node<TestMemory, Executor> asynchronousExecutorNode = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("return"))
            .role(Role.of("provide-executor"))
            .build(device -> CompletableFuture.completedFuture(Executors.newCachedThreadPool()));

    @Override
    protected Type functionType() {
        return FunctionNodes.ASYNCHRONOUS_FUNCTION_TYPE;
    }

    @Override
    protected SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role) {
        return FunctionNodes.asynchronous(Role.of(role), TestMemory.class).executorNode(asynchronousExecutorNode);
    }

    @Override
    protected Collection<Node<?, ?>> getExtraDependencies() {
        return List.of(asynchronousExecutorNode);
    }

    @Test
    public void executesFunctionsAsynchronously() {
        testExecutesFunctionsAsynchronously(functionNodesToTest("wait-for-signal"));
    }

    private void testExecutesFunctionsAsynchronously(
            SpecifyFunctionAndDependencies<TestMemory> specifyFunctionAndDependencies) {
        CountDownLatch continueExecutingSignal = new CountDownLatch(1);
        Node<TestMemory, Boolean> waitForSignal = specifyFunctionAndDependencies
                .get(Try.uncheckedSupplier(() -> continueExecutingSignal.await(5, TimeUnit.SECONDS)));

        Reply<Boolean> waitResult = TestData.callNodeInNewTestMemoryGraph(CompletableFuture.completedFuture(55),
                waitForSignal);
        continueExecutingSignal.countDown();

        Boolean signalReceived = waitResult.join();
        assertTrue(signalReceived);
    }

    @Test
    public void starterThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class,
                () -> CallTimeExecutorAsynchronousStarter.from(null, asynchronousExecutorNode));
        assertThrows(NullPointerException.class,
                () -> CallTimeExecutorAsynchronousStarter.from(TestMemory.class, null));
    }

    @Test
    public void starterExecutesFunctionsAsynchronously() {
        CallTimeExecutorAsynchronousStarter<TestMemory> starter = CallTimeExecutorAsynchronousStarter
                .from(TestMemory.class, asynchronousExecutorNode);
        testExecutesFunctionsAsynchronously(starter.startNode(Role.of("wait-for-signal")));
    }

    @Test
    public void starterPassesAlongRole() {
        CallTimeExecutorAsynchronousStarter<TestMemory> starter = CallTimeExecutorAsynchronousStarter
                .from(TestMemory.class, asynchronousExecutorNode);

        Node<TestMemory, Integer> node = starter.startNode(Role.of("test-node")).getValue(2);

        assertThat(node.getRole(), is(Role.of("test-node")));
    }
}
