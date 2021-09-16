package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

public class ConcurrentHashMapStorageTest {
    private final Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("test"))
            .role(Role.of("node"))
            .build(NodeMocks.behavior());

    @Test
    public void throwsExceptionForNegativeInitialCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentHashMapStorage(-1));
    }

    @Test
    public void handlesNonNegativeInitialCapacity() {
        new ConcurrentHashMapStorage(0);
        new ConcurrentHashMapStorage(1);
        new ConcurrentHashMapStorage(1000);
    }

    @Test
    public void atomicallyRunsReplySupplierOnlyEverOnce() {
        final int NUM_TRIES = 500; // Multi-threaded code is non-deterministic; so try multiple times
        IntStream.range(0, NUM_TRIES).forEach(this::tryOnceToVerifyReplySuppierCalledOnlyOnce);
    }

    private void tryOnceToVerifyReplySuppierCalledOnlyOnce(int trialNum) {
        // Suppress justification: mock only ever used in compatible way for declared type
        @SuppressWarnings("unchecked")
        Supplier<Reply<Integer>> replySupplier = mock(Supplier.class);
        Reply<Integer> reply = NodeMocks.reply();
        when(replySupplier.get()).thenReturn(reply);
        ConcurrentHashMapStorage storage = new ConcurrentHashMapStorage();

        new ConcurrentLoadGenerator(10, () -> storage.computeIfAbsent(node, replySupplier)).run();
        Reply<Integer> actualReply = storage.computeIfAbsent(node, replySupplier);

        assertThat(actualReply, sameInstance(reply));
        verify(replySupplier, times(1)).get();
    }

    @Test
    public void doesntEstablishMappingIfSupplierThrowsException() {
        ConcurrentHashMapStorage storage = new ConcurrentHashMapStorage();
        Reply<Integer> reply = NodeMocks.reply();

        assertThrows(UnsupportedOperationException.class, () -> storage.computeIfAbsent(node, () -> {
            throw new UnsupportedOperationException();
        }));
        Reply<Integer> actualReply = storage.computeIfAbsent(node, () -> reply);

        assertThat(actualReply, sameInstance(reply));
    }

    @Test
    public void throwsExceptionIfSupplierReturnsNullButDoesntEstablishMapping() {
        ConcurrentHashMapStorage storage = new ConcurrentHashMapStorage();
        Reply<Integer> reply = NodeMocks.reply();

        assertThrows(NullPointerException.class, () -> storage.computeIfAbsent(node, () -> null));
        Reply<Integer> actualReply = storage.computeIfAbsent(node, () -> reply);

        assertThat(actualReply, sameInstance(reply));
    }
}
