package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.CreationTimeThreadJumpingStarter;
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.SpecifyFunctionAndDependencies;

/**
 * Tests the thread-jumping nodes created in CompletionFunctionNodes. You can find other CompletionFunctionNodes-related
 * tests under CompletionFunctionNodes_*Test classes.
 */
public class CompletionFunctionNodes_CreationTimeExecutorThreadJumpingTest extends CompletionFunctionNodes_TestBase {
    private final Executor jumpingExecutor = Executors.newCachedThreadPool();

    @Override
    protected Type completionFunctionType() {
        return CompletionFunctionNodes.JUMPING_COMPLETION_FUNCTION_TYPE;
    }

    @Override
    protected SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role) {
        return CompletionFunctionNodes.threadJumping(Role.of(role), TestMemory.class).executor(jumpingExecutor);
    }

    @Override
    protected void assertConsumingConsistentWithCompletionThread(Thread consumingThread, Thread completionThread) {
        // Should be jumping threads
        assertThat(consumingThread, not(completionThread));
    }

    @Test
    public void threadJumpingThrowsExceptionProvidedNullArguments() {
        assertThrows(NullPointerException.class, () -> CompletionFunctionNodes.threadJumping(null, TestMemory.class));
        assertThrows(NullPointerException.class, () -> CompletionFunctionNodes.threadJumping(Role.of("role"), null));
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
        assertThrows(NullPointerException.class, () -> CreationTimeThreadJumpingStarter.from(null));
    }

    // Although imperfect, this is the easiest way to assure that the CreationTimeThreadJumpingStarter is the same as
    // CompletionFunctionNodes.threadJumping itself
    @Test
    public void starterUsesExecutorPassedToIt() {
        // Mockito gives "Illegal reflective access by ...ReflectionMemberAccessor" when using "spy", so create our own
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        Executor executor = command -> {
            executorCalled.set(true);
            jumpingExecutor.execute(command);
        };
        CreationTimeThreadJumpingStarter starter = CreationTimeThreadJumpingStarter.from(executor);
        Node<TestMemory, Integer> node = starter.startNode(Role.of("test-node"), TestMemory.class)
                .getValue(CompletableFuture.completedFuture(2));

        Reply<Integer> reply = TestData.callNodeInNewTestMemoryGraph(CompletableFuture.completedFuture(1), node);

        assertTrue(executorCalled.get());
        assertThat(reply.join(), is(2));
    }

    @Test
    public void starterPassesAlongRole() {
        CreationTimeThreadJumpingStarter starter = CreationTimeThreadJumpingStarter.from(jumpingExecutor);

        Node<TestMemory, Integer> node = starter.startNode(Role.of("test-node"), TestMemory.class)
                .getValue(CompletableFuture.completedFuture(2));

        assertThat(node.getRole(), is(Role.of("test-node")));
    }
}
