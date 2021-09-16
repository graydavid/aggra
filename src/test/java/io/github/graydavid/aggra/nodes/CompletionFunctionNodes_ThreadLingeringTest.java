package io.github.graydavid.aggra.nodes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.CompletionFunctionNodes.SpecifyFunctionAndDependencies;

/**
 * Tests the thread-lingering nodes created in CompletionFunctionNodes. You can find other
 * CompletionFunctionNodes-related tests under CompletionFunctionNodes_*Test classes.
 */
public class CompletionFunctionNodes_ThreadLingeringTest extends CompletionFunctionNodes_TestBase {

    @Override
    protected Type completionFunctionType() {
        return CompletionFunctionNodes.LINGERING_COMPLETION_FUNCTION_TYPE;
    }

    @Override
    protected SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role) {
        return CompletionFunctionNodes.threadLingering(Role.of(role), TestMemory.class);
    }

    @Override
    protected void assertConsumingConsistentWithCompletionThread(Thread consumingThread, Thread completionThread) {
        // Should be lingering on threads
        assertThat(consumingThread, is(completionThread));
    }

    @Test
    public void lingeringCompletionFunctionTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .type(CompletionFunctionNodes.LINGERING_COMPLETION_FUNCTION_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }
}
