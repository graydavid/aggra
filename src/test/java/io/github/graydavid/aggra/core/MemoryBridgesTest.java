package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.MemoryBridges.MemoryFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoInputFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceFactory;
import io.github.graydavid.aggra.core.MemoryBridges.MemoryNoSourceInputFactory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class MemoryBridgesTest {

    private final CompletableFuture<Integer> input = CompletableFuture.completedFuture(1);

    @Test
    public void memoryFactoryToNoInputFactoryThrowsExceptionIfDoesntHaveSpecifiedInput() {
        CompletableFuture<Integer> differentInput = CompletableFuture.completedFuture(2);
        TestMemory memoryWithDifferentInput = new TestMemory(differentInput);
        TestMemory source = new TestMemory(CompletableFuture.completedFuture(0));
        MemoryFactory<TestMemory, Integer, TestMemory> memoryFactory = (scope, input, src) -> memoryWithDifferentInput;
        MemoryNoInputFactory<TestMemory, TestMemory> memoryNoInputFactory = MemoryFactory
                .toNoInputFactory(memoryFactory, input);

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> memoryNoInputFactory.createNew(mock(MemoryScope.class), source));

        assertThat(thrown.getMessage(), allOf(containsString(input.toString()),
                containsString(differentInput.toString()), containsString(memoryWithDifferentInput.toString())));
    }

    @Test
    public void memoryNoSourceFactoryToNoSourceInputFactoryThrowsExceptionIfDoesntHaveSpecifiedInput() {
        CompletableFuture<Integer> differentInput = CompletableFuture.completedFuture(2);
        TestMemory memoryWithDifferentInput = new TestMemory(differentInput);
        MemoryNoSourceFactory<Integer, TestMemory> memoryFactory = (scope, input) -> memoryWithDifferentInput;
        MemoryNoSourceInputFactory<TestMemory> memoryNoInputFactory = MemoryNoSourceFactory
                .toNoSourceInputFactory(memoryFactory, input);

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> memoryNoInputFactory.createNew(mock(MemoryScope.class)));

        assertThat(thrown.getMessage(), allOf(containsString(input.toString()),
                containsString(differentInput.toString()), containsString(memoryWithDifferentInput.toString())));
    }

    @Test
    public void requireMemoryHasInputThrowsExceptionIfDoesntHaveSpecifiedInput() {
        CompletableFuture<Integer> differentInput = CompletableFuture.completedFuture(2);
        TestMemory memory = new TestMemory(differentInput);

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> MemoryBridges.requireMemoryHasInput(memory, input));

        assertThat(thrown.getMessage(), allOf(containsString(input.toString()),
                containsString(differentInput.toString()), containsString(memory.toString())));
    }

    @Test
    public void requireMemoryHasInputReturnsMemoryIfInputMatches() {
        TestMemory memory = new TestMemory(input);

        TestMemory actual = MemoryBridges.requireMemoryHasInput(memory, input);

        assertThat(actual, sameInstance(memory));
    }

    @Test
    public void requireMemoryHasScopeThrowsExceptionWhenMemoryHasDifferentScope() {
        MemoryScope actual = mock(MemoryScope.class);
        TestMemory memory = new TestMemory(actual, input);
        MemoryScope desired = mock(MemoryScope.class);

        MisbehaviorException thrown = assertThrows(MisbehaviorException.class,
                () -> MemoryBridges.requireMemoryHasScope(memory, desired));

        assertThat(thrown.getMessage(), allOf(containsString("Expected Memory"), containsString("to have scope")));
    }

    @Test
    public void requireMemoryHasScopeReturnsMemoryIfScopeMatches() {
        MemoryScope scope = mock(MemoryScope.class);
        TestMemory memory = new TestMemory(scope, input);

        TestMemory actual = MemoryBridges.requireMemoryHasScope(memory, scope);

        assertThat(actual, sameInstance(memory));
    }
}
