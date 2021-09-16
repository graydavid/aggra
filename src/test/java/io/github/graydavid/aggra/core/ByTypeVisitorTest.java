package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

public class ByTypeVisitorTest {

    @Test
    public void factoryMethodThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> ByTypeVisitor.ofVisitors(null, node -> 5));
        assertThrows(NullPointerException.class, () -> ByTypeVisitor.ofVisitors(Map.of(), null));
    }

    @Test
    public void selectsFunctionalityByMatchingType() {
        Type type = Type.generic("type");
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(type)
                .role(Role.of("matching-role"))
                .build(device -> CompletableFuture.completedFuture(12));
        ByTypeVisitor<Role> roleReturningVisitor = ByTypeVisitor.ofVisitors(Map.of(type, Node::getRole),
                nd -> Role.of("default"));

        Role result = roleReturningVisitor.visit(node);

        assertThat(result, is(Role.of("matching-role")));
    }

    @Test
    public void usesDefaultFunctionalityWhenNoMatchByType() {
        Type type = Type.generic("type");
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(type)
                .role(Role.of("matching-role"))
                .build(device -> CompletableFuture.completedFuture(12));
        ByTypeVisitor<Role> roleReturningVisitor = ByTypeVisitor.ofVisitors(Map.of(), nd -> Role.of("default"));

        Role result = roleReturningVisitor.visit(node);

        assertThat(result, is(Role.of("default")));
    }
}
