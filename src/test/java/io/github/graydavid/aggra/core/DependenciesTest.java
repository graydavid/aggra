package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.Dependencies.newAncestorMemoryDependency;
import static io.github.graydavid.aggra.core.Dependencies.newNewMemoryDependency;
import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.ConsumerCallToDependencyReplyCardinality;
import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.Visitor;
import io.github.graydavid.aggra.core.TestData.TestMemory;

public class DependenciesTest {

    @Test
    public void newSameMemoryDependencyThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> newSameMemoryDependency(null, PrimingMode.PRIMED));
        assertThrows(NullPointerException.class, () -> newSameMemoryDependency(NodeMocks.node(), null));
    }

    @Test
    public void newNewMemoryDependencyThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> newNewMemoryDependency(null));
    }

    @Test
    public void newAncestorMemoryDependencyThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> newAncestorMemoryDependency(null));
    }

    @Test
    public void sameMemoryDependencyReturnsExpectedProperties() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        SameMemoryDependency<TestMemory, Integer> primedDependency = newSameMemoryDependency(node, PrimingMode.PRIMED);
        SameMemoryDependency<TestMemory, Integer> unprimedDependency = newSameMemoryDependency(node,
                PrimingMode.UNPRIMED);

        assertThat(primedDependency.getNode(), is(node));
        assertThat(primedDependency.getPrimingMode(), is(PrimingMode.PRIMED));
        assertThat(unprimedDependency.getPrimingMode(), is(PrimingMode.UNPRIMED));
        assertThat(unprimedDependency.getConsumerCallToDependencyReplyCardinality(),
                is(ConsumerCallToDependencyReplyCardinality.ONE_TO_ONE));
    }

    @Test
    public void newMemoryDependencyReturnsExpectedProperties() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        NewMemoryDependency<TestMemory, Integer> dependency = newNewMemoryDependency(node);

        assertThat(dependency.getNode(), is(node));
        assertThat(dependency.getPrimingMode(), is(PrimingMode.UNPRIMED));
        assertThat(dependency.getConsumerCallToDependencyReplyCardinality(),
                is(ConsumerCallToDependencyReplyCardinality.ONE_TO_MANY));
    }

    @Test
    public void ancestorMemoryDependencyReturnsExpectedProperties() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        AncestorMemoryDependency<TestMemory, Integer> dependency = newAncestorMemoryDependency(node);

        assertThat(dependency.getNode(), is(node));
        assertThat(dependency.getPrimingMode(), is(PrimingMode.UNPRIMED));
        assertThat(dependency.getConsumerCallToDependencyReplyCardinality(),
                is(ConsumerCallToDependencyReplyCardinality.MANY_TO_ONE));
    }

    @Test
    public void dependencyEqualsObeysContract() {
        Node<TestMemory, Integer> node1 = NodeMocks.node();
        Node<TestMemory, Integer> node2 = NodeMocks.node();
        SameMemoryDependency<TestMemory, Integer> same = Dependencies.newSameMemoryDependency(node1,
                PrimingMode.UNPRIMED);
        SameMemoryDependency<TestMemory, Integer> sameCopy = Dependencies.newSameMemoryDependency(node1,
                PrimingMode.UNPRIMED);
        SameMemoryDependency<TestMemory, Integer> sameDifferentNode = Dependencies.newSameMemoryDependency(node2,
                PrimingMode.UNPRIMED);
        SameMemoryDependency<TestMemory, Integer> sameDifferentPrimingMode = Dependencies.newSameMemoryDependency(node2,
                PrimingMode.PRIMED);
        NewMemoryDependency<TestMemory, Integer> newD = Dependencies.newNewMemoryDependency(node1);
        NewMemoryDependency<TestMemory, Integer> newDCopy = Dependencies.newNewMemoryDependency(node1);
        NewMemoryDependency<TestMemory, Integer> newDDifferentNode = Dependencies.newNewMemoryDependency(node2);
        AncestorMemoryDependency<TestMemory, Integer> ancestor = Dependencies.newAncestorMemoryDependency(node1);
        AncestorMemoryDependency<TestMemory, Integer> ancestorCopy = Dependencies.newAncestorMemoryDependency(node1);
        AncestorMemoryDependency<TestMemory, Integer> ancestorDifferentNode = Dependencies
                .newAncestorMemoryDependency(node2);

        assertThat(same, equalTo(same));
        assertThat(same, equalTo(sameCopy));
        assertThat(sameCopy, equalTo(same));
        assertThat(same, not(equalTo(sameDifferentNode)));
        assertThat(sameDifferentNode, not(equalTo(same)));
        assertThat(same, not(equalTo(sameDifferentPrimingMode)));
        assertThat(same, not(equalTo(null)));
        assertThat(same, not(equalTo(newD)));
        assertThat(newD, not(equalTo(same)));
        assertThat(same, not(equalTo(ancestor)));
        assertThat(ancestor, not(equalTo(same)));

        assertThat(newD, equalTo(newD));
        assertThat(newD, equalTo(newDCopy));
        assertThat(newDCopy, equalTo(newD));
        assertThat(newD, not(equalTo(newDDifferentNode)));
        assertThat(newDDifferentNode, not(equalTo(newD)));
        assertThat(newD, not(equalTo(null)));
        assertThat(newD, not(equalTo(ancestor)));
        assertThat(ancestor, not(equalTo(newD)));

        assertThat(ancestor, equalTo(ancestor));
        assertThat(ancestor, equalTo(ancestorCopy));
        assertThat(ancestorCopy, equalTo(ancestor));
        assertThat(ancestor, not(equalTo(ancestorDifferentNode)));
        assertThat(ancestorDifferentNode, not(equalTo(ancestor)));
        assertThat(ancestor, not(equalTo(null)));
    }

    @Test
    public void dependencyHashCodeObeysContract() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        SameMemoryDependency<TestMemory, Integer> same = Dependencies.newSameMemoryDependency(node, PrimingMode.PRIMED);
        SameMemoryDependency<TestMemory, Integer> sameCopy = Dependencies.newSameMemoryDependency(node,
                PrimingMode.PRIMED);
        NewMemoryDependency<TestMemory, Integer> newD = Dependencies.newNewMemoryDependency(node);
        NewMemoryDependency<TestMemory, Integer> newDCopy = Dependencies.newNewMemoryDependency(node);
        AncestorMemoryDependency<TestMemory, Integer> ancestor = Dependencies.newAncestorMemoryDependency(node);
        AncestorMemoryDependency<TestMemory, Integer> ancestorCopy = Dependencies.newAncestorMemoryDependency(node);

        assertThat(same.hashCode(), equalTo(same.hashCode()));
        assertThat(same.hashCode(), equalTo(sameCopy.hashCode()));

        assertThat(newD.hashCode(), equalTo(newD.hashCode()));
        assertThat(newD.hashCode(), equalTo(newDCopy.hashCode()));

        assertThat(ancestor.hashCode(), equalTo(ancestor.hashCode()));
        assertThat(ancestor.hashCode(), equalTo(ancestorCopy.hashCode()));
    }

    @Test
    public void dependencyToStringProvidesSynopsisOfDependency() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(1));
        SameMemoryDependency<TestMemory, Integer> same = Dependencies.newSameMemoryDependency(node, PrimingMode.PRIMED);
        NewMemoryDependency<TestMemory, Integer> newD = Dependencies.newNewMemoryDependency(node);
        AncestorMemoryDependency<TestMemory, Integer> ancestor = Dependencies.newAncestorMemoryDependency(node);

        assertThat(same.toString(), allOf(containsString(SameMemoryDependency.class.getSimpleName()),
                containsString(PrimingMode.PRIMED.toString()), containsString(node.getSynopsis())));

        assertThat(newD.toString(), allOf(containsString(NewMemoryDependency.class.getSimpleName()),
                containsString(PrimingMode.UNPRIMED.toString()), containsString(node.getSynopsis())));

        assertThat(ancestor.toString(), allOf(containsString(AncestorMemoryDependency.class.getSimpleName()),
                containsString(PrimingMode.UNPRIMED.toString()), containsString(node.getSynopsis())));
    }

    @Test
    public void visitorDefaultVisitReturnsNull() {
        Visitor<?> visitor = new Visitor<>() {};

        assertNull(visitor.defaultVisit(null));
    }

    @Test
    public void visitorVisitMethodsCallDefaultVisitByDefault() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        Visitor<Integer> returnFive = new Visitor<>() {
            @Override
            public Integer defaultVisit(Dependency<?, ?> dependency) {
                return 5;
            }
        };

        assertThat(returnFive.visitSame(newSameMemoryDependency(node, PrimingMode.PRIMED)), is(5));
        assertThat(returnFive.visitNew(newNewMemoryDependency(node)), is(5));
        assertThat(returnFive.visitAncestor(newAncestorMemoryDependency(node)), is(5));
    }

    @Test
    public void dependencyAcceptCallsSpecificMethodPerType() {
        Node<TestMemory, Integer> node = NodeMocks.node();
        SameMemoryDependency<TestMemory, Integer> same = newSameMemoryDependency(node, PrimingMode.PRIMED);
        NewMemoryDependency<TestMemory, Integer> newD = newNewMemoryDependency(node);
        AncestorMemoryDependency<TestMemory, Integer> ancestor = newAncestorMemoryDependency(node);
        // Suppress justification: returned mocks only ever used in compatible way for declared type.
        @SuppressWarnings("unchecked")
        Visitor<String> visitor = mock(Visitor.class);
        when(visitor.visitSame(same)).thenReturn("Same");
        when(visitor.visitNew(newD)).thenReturn("New");
        when(visitor.visitAncestor(ancestor)).thenReturn("Ancestor");

        assertThat(same.accept(visitor), is("Same"));
        assertThat(newD.accept(visitor), is("New"));
        assertThat(ancestor.accept(visitor), is("Ancestor"));
    }
}
