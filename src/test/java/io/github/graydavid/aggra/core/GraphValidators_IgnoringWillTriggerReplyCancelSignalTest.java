package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.GraphValidators.GraphCandidate;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests the ignoringWillTriggerReplyCancelSignal validator from GraphValidators. You can find other
 * GraphValidators-related tests under GraphValidators_*Test classes.
 */
public class GraphValidators_IgnoringWillTriggerReplyCancelSignalTest {
    private final Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("node"))
            .role(Role.of("node"))
            .build(device -> CompletableFuture.completedFuture(15));

    @Test
    public void ignoringWillTriggerReplyCancelSignalThrowsExceptionIfPassedInNodeIsNull() {
        assertThrows(NullPointerException.class,
                () -> GraphValidators.ignoringWillTriggerReplyCancelSignal().create(null));
    }

    @Test
    public void ignoringWillTriggerReplyCancelSignalIsWellDescribed() {
        assertThat(GraphValidators.ignoringWillTriggerReplyCancelSignal().getDescription(),
                is("Ignores will trigger Reply cancel signal"));
    }

    @Test
    public void throwsExceptionWheNodeIsNotAPartOfTheCandidate() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of());
        GraphValidator validator = GraphValidators.ignoringWillTriggerReplyCancelSignal().create(node);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(candidate));
    }

    @Test
    public void passesWhenIgnoringNodeWillTriggerReplyCancellationSignal() {
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node));
        assertTrue(candidate.ignoringWillTriggerReplyCancelSignal(node));
        GraphValidator validator = GraphValidators.ignoringWillTriggerReplyCancelSignal().create(node);

        validator.validate(candidate);
    }

    @Test
    public void throwsExceptionIgnoringNodeWillNotTriggerReplyCancellationSignal() {
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.sameMemoryUnprimedDependency(node);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumerBuilder"))
                .role(Role.of("consumerBuilder"))
                .build(device -> CompletableFuture.completedFuture(15));
        GraphCandidate<TestMemory> candidate = GraphCandidate.fromRoots(Set.of(node, consumer));
        assertFalse(candidate.ignoringWillTriggerReplyCancelSignal(node));
        GraphValidator validator = GraphValidators.ignoringWillTriggerReplyCancelSignal().create(node);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(candidate));

        assertThat(thrown.getMessage(), containsString("Ignoring will not trigger Reply cancellation signal"));
    }
}
