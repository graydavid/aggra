package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

public class MemoryTest {
    private static class TestMemory extends Memory<Integer> {
        TestMemory(CompletableFuture<Integer> input, Supplier<? extends Storage> storageFactory) {
            super(mock(MemoryScope.class), input, Set.of(), storageFactory);
        }

        TestMemory(MemoryScope scope, CompletableFuture<Integer> input, Set<Memory<?>> parents,
                Supplier<? extends Storage> storageFactory) {
            super(scope, input, parents, storageFactory);
        }
    }

    private final Storage storage = mock(Storage.class);

    @Test
    public void constructorThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class,
                () -> new TestMemory(null, CompletableFuture.completedFuture(5), Set.of(), () -> storage));
        assertThrows(NullPointerException.class,
                () -> new TestMemory(mock(MemoryScope.class), null, Set.of(), () -> storage));
        assertThrows(NullPointerException.class, () -> new TestMemory(mock(MemoryScope.class),
                CompletableFuture.completedFuture(5), null, () -> storage));
        assertThrows(NullPointerException.class,
                () -> new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(5), Set.of(), null));
    }

    @Test
    public void constructorThrowsExceptionGivenNullStorageFromFactory() {
        assertThrows(NullPointerException.class,
                () -> new TestMemory(CompletableFuture.completedFuture(5), () -> null));
    }

    @Test
    public void constructorCallsStorageFactoryOnce() {
        // Suppress justification: mock only ever used in compatible way for declared type
        @SuppressWarnings("unchecked")
        Supplier<Storage> storageFactory = mock(Supplier.class);
        when(storageFactory.get()).thenReturn(storage);

        TestMemory memory = new TestMemory(CompletableFuture.completedFuture(5), storageFactory);

        verify(storageFactory, times(1)).get();

        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("node"))
                .build(NodeMocks.behavior());
        Reply<Integer> reply = NodeMocks.reply();
        memory.computeIfAbsent(node, () -> reply);

        verifyNoMoreInteractions(storageFactory);
    }

    @Test
    public void accessorsReturnArgumentsPassedToConstructor() {
        MemoryScope scope = mock(MemoryScope.class);
        CompletableFuture<Integer> input = CompletableFuture.completedFuture(5);

        TestMemory memory = new TestMemory(scope, input, Set.of(), () -> storage);

        assertThat(memory.getScope(), sameInstance(scope));
        assertThat(memory.getScopeToString(), is(scope.toString()));
        assertThat(memory.getInput(), sameInstance(input));
        assertThat(memory.getParents(), empty());
    }

    @Test
    public void getParentsReturnsParents() {
        Memory<Integer> parent1 = NodeMocks.memory();
        Memory<Integer> parent2 = NodeMocks.memory();
        TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(5),
                Set.of(parent1, parent2), () -> storage);

        Set<Memory<?>> parents = memory.getParents();

        assertThat(parents, containsInAnyOrder(parent1, parent2));
    }

    @Test
    public void callIfAbsentDelegatesToStorageFromConstructor() {
        TestMemory memory = new TestMemory(CompletableFuture.completedFuture(5), () -> storage);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("node"))
                .build(NodeMocks.behavior());
        Reply<Integer> reply = NodeMocks.reply();
        Supplier<Reply<Integer>> replySupplier = () -> reply;
        when(storage.computeIfAbsent(node, replySupplier)).thenReturn(reply);

        Reply<Integer> actualReply = memory.computeIfAbsent(node, replySupplier);

        assertThat(actualReply, sameInstance(reply));
    }

    private static class DifferentTestMemory extends Memory<Integer> {
        DifferentTestMemory(CompletableFuture<Integer> input, Supplier<? extends Storage> storageFactory) {
            super(mock(MemoryScope.class), input, Set.of(), storageFactory);
        }
    }

    @Test
    public void callIfAbsentThrowsExceptionWhenPassedNodesAssociatedWithDifferentMemory() {
        TestMemory memory = new TestMemory(CompletableFuture.completedFuture(5), () -> storage);
        Node<DifferentTestMemory, Integer> nodeWithDifferentMemory = Node.communalBuilder(DifferentTestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of("node"))
                .build(NodeMocks.behavior());
        Reply<Integer> reply = NodeMocks.reply();
        Supplier<Reply<Integer>> replySupplier = () -> reply;

        assertThrows(IllegalArgumentException.class,
                () -> memory.computeIfAbsent(nodeWithDifferentMemory, replySupplier));
    }

    @Test
    public void toStringContainsSimpleMemoryInformationGivenNoParents() {
        MemoryScope scope = mock(MemoryScope.class);
        TestMemory memory = new TestMemory(scope, CompletableFuture.completedFuture(5), Set.of(), () -> storage);

        String toString = memory.toString();

        assertThat(toString, startsWith("TestMemory@"));
        assertThat(toString, containsString(Integer.toHexString(memory.hashCode())));
        assertThat(toString, containsString(scope.getClass().getSimpleName()));
        assertThat(toString, containsString(Integer.toHexString(scope.hashCode())));
        assertThat(toString, not(containsString(memory.getInput().toString())));
    }

    @Test
    public void toStringContainsParentInformationForMemorysWithParents() {
        Memory<Integer> parent1 = NodeMocks.memory();
        Memory<Integer> parent2 = NodeMocks.memory();
        TestMemory memory = new TestMemory(mock(MemoryScope.class), CompletableFuture.completedFuture(5),
                Set.of(parent1, parent2), () -> storage);

        String toString = memory.toString();

        assertThat(toString, containsString("->["));
        assertThat(toString, containsString(parent1.toString()));
        assertThat(toString, containsString(parent2.toString()));
        assertThat(toString, endsWith("]"));
    }
}
