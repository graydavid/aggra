package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.CallTimeThreadJumpingStarter;
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.SpecifyFunctionAndDependencies;

/**
 * Tests the thread-jumping nodes created in CompletionFunctionNodes. You can find other CompletionFunctionNodes-related
 * tests under CompletionFunctionNodes_*Test classes.
 */
public class CompletionFunctionNodes_CallTimeExecutorThreadJumpingTest extends CompletionFunctionNodes_TestBase {
    private final Node<TestMemory, Executor> jumpingExecutorNode = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("return"))
            .role(Role.of("provide-executor"))
            .build(device -> CompletableFuture.completedFuture(Executors.newCachedThreadPool()));

    @Override
    protected Type completionFunctionType() {
        return CompletionFunctionNodes.JUMPING_COMPLETION_FUNCTION_TYPE;
    }

    @Override
    protected SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role) {
        return CompletionFunctionNodes.threadJumping(Role.of(role), TestMemory.class).executorNode(jumpingExecutorNode);
    }

    @Override
    protected void assertConsumingConsistentWithCompletionThread(Thread consumingThread, Thread completionThread) {
        // Should be jumping threads
        assertThat(consumingThread, not(completionThread));
    }

    @Override
    protected Collection<Node<?, ?>> getExtraDependencies() {
        return List.of(jumpingExecutorNode);
    }

    @Test
    public void jumpingCompletionFunctionTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .type(CompletionFunctionNodes.JUMPING_COMPLETION_FUNCTION_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void starterThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> CallTimeThreadJumpingStarter.from(null, jumpingExecutorNode));
        assertThrows(NullPointerException.class, () -> CallTimeThreadJumpingStarter.from(TestMemory.class, null));
    }

    // Although imperfect, this is the easiest way to assure that the CallTimeThreadJumpingStarter is the same as
    // CompletionFunctionNodes.threadJumping itself
    @Test
    public void starterUsesExecutorPassedToIt() {
        // Mockito gives "Illegal reflective access by ...ReflectionMemberAccessor" when using "spy", so create our own
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        Executor jumpingExecutor = Executors.newCachedThreadPool();
        Executor spyExecutor = command -> {
            executorCalled.set(true);
            jumpingExecutor.execute(command);
        };
        Node<TestMemory, Executor> jumpingExecutorNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("return"))
                .role(Role.of("provide-executor"))
                .build(device -> CompletableFuture.completedFuture(spyExecutor));
        CallTimeThreadJumpingStarter<TestMemory> starter = CallTimeThreadJumpingStarter.from(TestMemory.class,
                jumpingExecutorNode);
        Node<TestMemory, Integer> node = starter.startNode(Role.of("test-node"))
                .getValue(CompletableFuture.completedFuture(2));

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(CompletableFuture.completedFuture(1), node);

        assertTrue(executorCalled.get());
        assertThat(reply.join(), is(2));
    }

    @Test
    public void starterPassesAlongRole() {
        CallTimeThreadJumpingStarter<TestMemory> starter = CallTimeThreadJumpingStarter.from(TestMemory.class,
                jumpingExecutorNode);

        Node<TestMemory, Integer> node = starter.startNode(Role.of("test-node"))
                .getValue(CompletableFuture.completedFuture(2));

        assertThat(node.getRole(), is(Role.of("test-node")));
    }
}
