package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES;
import static io.github.graydavid.aggra.core.ExceptionStrategy.SUPPRESS_DEPENDENCY_FAILURES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.graydavid.aggra.core.TestData.TestMemory;

public class ExceptionStrategyTest {
    private static final Supplier<Collection<CompletionException>> EMPTY_DEPENDENCY_FAILURES_SUPPLIER = () -> Collections
            .emptyList();
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Node<?, ?> failedNode = Node.inputBuilder(TestMemory.class).role(Role.of("placeholder")).build();

    @ParameterizedTest
    @EnumSource
    public void returnsNodeFailureUntouchedIfAlreadyFullContainerExceptionAndNoDependencies(
            ExceptionStrategy strategy) {
        Throwable root = new Throwable();
        CallException callException = new CallException(caller, failedNode, root);
        CompletionException completionException = new CompletionException("message", callException);

        CompletionException container = strategy.constructContainerException(caller, failedNode, completionException,
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container, sameInstance(completionException));
    }

    @ParameterizedTest
    @EnumSource
    public void wrapsNodeFailureInCompletionExceptionWithOwnMessageIfCallExceptionAndNoDependencies(
            ExceptionStrategy strategy) {
        Throwable root = new Throwable();
        CallException callException = new CallException(caller, failedNode, root);

        CompletionException container = strategy.constructContainerException(caller, failedNode, callException,
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container.getCause(), sameInstance(callException));
        assertThat(container.getMessage(), is("Node call failed: " + failedNode.getSynopsis()));
    }

    @ParameterizedTest
    @EnumSource
    public void doublyWrapsNodeFailureInCompletionAndCallExceptionsWithOwnMessageIfIsNeitherOne(
            ExceptionStrategy strategy) {
        Throwable root = new Throwable();

        CompletionException container = strategy.constructContainerException(caller, failedNode, root,
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container.getCause(), instanceOf(CallException.class));
        assertThat(container.getCause().getCause(), sameInstance(root));
        assertThat(container.getMessage(), is("Node call failed: " + failedNode.getSynopsis()));
    }

    @ParameterizedTest
    @EnumSource
    public void doublyWrapsNodeFailureInCompletionAndCallExceptionsIfIsCompletionWithoutCall(
            ExceptionStrategy strategy) {
        CompletionException completionException = new CompletionException("message", new Throwable());

        CompletionException container = strategy.constructContainerException(caller, failedNode, completionException,
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container.getCause(), instanceOf(CallException.class));
        assertThat(container.getCause().getCause(), sameInstance(completionException));
    }

    @ParameterizedTest
    @EnumSource
    public void doublyWrapsNodeFailureInCompletionAndCallExceptionsIfIsNonCompletionWithCall(
            ExceptionStrategy strategy) {
        CallException callException = new CallException(caller, failedNode, new Throwable());
        Throwable root = new Throwable(callException);

        CompletionException container = strategy.constructContainerException(caller, failedNode, root,
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container.getCause(), instanceOf(CallException.class));
        assertThat(container.getCause().getCause(), sameInstance(root));
    }

    @ParameterizedTest
    @EnumSource
    public void addsNodeCallStackToCallExceptionGivenNoDependencies(ExceptionStrategy strategy) {
        CallException similarCallException = new CallException(caller, failedNode, new Throwable());
        similarCallException.addNodeCallStackTraceElement(caller, failedNode);

        CompletionException container = strategy.constructContainerException(caller, failedNode, new Throwable(),
                EMPTY_DEPENDENCY_FAILURES_SUPPLIER);

        assertThat(container.getCause().getMessage(), is(similarCallException.getMessage()));
    }


    @Test
    public void discardDependencyFailuresDoesNotAddDependencyFailuresAsSuppressed() {
        Supplier<Collection<CompletionException>> dependencyFailuresSupplier = mockDependencyFailureSupplier();

        CompletionException container = DISCARD_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                new Throwable(), dependencyFailuresSupplier);

        verifyNoInteractions(dependencyFailuresSupplier);
        assertThat(container.getCause().getSuppressed(), emptyArray());
    }

    // Suppression justification: returned mocks only ever used in compatible way for declared type.
    @SuppressWarnings("unchecked")
    private static Supplier<Collection<CompletionException>> mockDependencyFailureSupplier() {
        return mock(Supplier.class);
    }

    @Test
    public void suppressDependencyFailuresPropagatesOriginalNodeFailureIfItMatchesAllDependencyFailures() {
        CallException callException = new CallException(caller, failedNode, new Throwable());
        CompletionException nodeFailure = new CompletionException("message-1", callException);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeFailure, () -> List.of(nodeFailure, nodeFailure));

        assertThat(container, sameInstance(nodeFailure));
        assertThat(container.getCause().getSuppressed(), emptyArray());
    }

    @Test
    public void suppressDependencyFailuresAddsDependencyFailuresAsSuppressedIfDoesntHaveCallException() {
        Throwable nodeFailure = new Throwable();
        CallException dependencyCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException dependencyFailure = new CompletionException("message", dependencyCallException);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeFailure, () -> List.of(dependencyFailure));

        assertThat(container.getCause().getSuppressed(), arrayContaining(dependencyFailure));
    }

    @Test
    public void suppressDependencyFailuresShallowCopiesNodeFailureCallExceptionWhenHasToAddSuppressedExceptionAndIsFullContainerException() {
        Caller callerForNodeFailure = () -> Role.of("node-failure-caller");
        CallException nodeCallException = new CallException(callerForNodeFailure, failedNode, new Throwable());
        Caller callerUniqueToNodeFailure = () -> Role.of("unique-to-node-failure-caller");
        nodeCallException.addNodeCallStackTraceElement(callerUniqueToNodeFailure, failedNode);
        CompletionException nodeFailure = new CompletionException("node-message", nodeCallException);
        CallException dependencyCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException dependencyFailure = new CompletionException("dependency-message", dependencyCallException);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeFailure, () -> List.of(dependencyFailure));

        CallException containerCallException = (CallException) container.getCause();
        assertThat(containerCallException, not(sameInstance(nodeCallException)));
        assertThat(containerCallException.getCaller(), is(nodeCallException.getCaller()));
        assertThat(containerCallException.getFailedNode(), is(nodeCallException.getFailedNode()));
        assertThat(containerCallException.getCause(), is(nodeCallException.getCause()));
        assertThat(containerCallException.getMessage(), not(containsString(callerUniqueToNodeFailure.toString())));
        assertThat(nodeCallException.getMessage(), not(containsString(caller.toString())));
        assertThat(containerCallException.getSuppressed(), arrayContainingInAnyOrder(nodeFailure, dependencyFailure));
        assertThat(container.getMessage(), is("Node call failed: " + failedNode.getSynopsis()));
    }

    @Test
    public void suppressDependencyFailuresShallowCopiesNodeFailureCallExceptionWhenHasToAddSuppressedExceptionAndIsOnlyCallException() {
        Caller callerForNodeFailure = () -> Role.of("node-failure-caller");
        CallException nodeCallException = new CallException(callerForNodeFailure, failedNode, new Throwable());
        Caller callerUniqueToNodeFailure = () -> Role.of("unique-to-node-failure-caller");
        nodeCallException.addNodeCallStackTraceElement(callerUniqueToNodeFailure, failedNode);
        CallException dependencyCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException dependencyFailure = new CompletionException("dependency-message", dependencyCallException);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeCallException, () -> List.of(dependencyFailure));

        CallException containerCallException = (CallException) container.getCause();
        assertThat(containerCallException, not(sameInstance(nodeCallException)));
        assertThat(containerCallException.getCaller(), is(nodeCallException.getCaller()));
        assertThat(containerCallException.getFailedNode(), is(nodeCallException.getFailedNode()));
        assertThat(containerCallException.getCause(), is(nodeCallException.getCause()));
        assertThat(containerCallException.getMessage(), not(containsString(callerUniqueToNodeFailure.toString())));
        assertThat(nodeCallException.getMessage(), not(containsString(caller.toString())));
        List<Throwable> suppressed = new ArrayList<>(List.of(containerCallException.getSuppressed()));
        assertThat(suppressed, hasSize(2));
        assertTrue(suppressed.contains(dependencyFailure));
        suppressed.remove(dependencyFailure);
        assertThat(suppressed.get(0).getCause(), sameInstance(nodeCallException));
        assertThat(container.getMessage(), is("Node call failed: " + failedNode.getSynopsis()));
    }

    @Test
    public void suppressDependencyFailuresDeduplicatesNodeAndDependencyFailuresWhenAddingSuppressed() {
        CallException nodeCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException nodeFailure = new CompletionException("node-message", nodeCallException);
        CallException dependencyCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException dependencyFailure = new CompletionException("dependency-message", dependencyCallException);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeFailure, () -> List.of(nodeFailure, dependencyFailure, dependencyFailure));

        assertThat(container.getCause().getSuppressed(), arrayContainingInAnyOrder(nodeFailure, dependencyFailure));
    }

    @Test
    public void suppressDependencyFailuresAccessesDependencyFailuresSupplierOnlyOnce() {
        CallException nodeCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException nodeFailure = new CompletionException("node-message", nodeCallException);
        CallException dependencyCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException dependencyFailure = new CompletionException("dependency-message", dependencyCallException);
        Supplier<Collection<CompletionException>> dependencyFailuresSupplier = mockDependencyFailureSupplier();
        when(dependencyFailuresSupplier.get()).thenReturn(List.of(dependencyFailure));

        SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode, nodeFailure,
                dependencyFailuresSupplier);

        verify(dependencyFailuresSupplier, times(1)).get();
    }

    @Test
    public void suppressDependencyFailuresSuppressesFailuresGettingDependencyFailuresButOtherwiseWorksTheSame() {
        CallException nodeCallException = new CallException(caller, failedNode, new Throwable());
        CompletionException nodeFailure = new CompletionException("node-message", nodeCallException);
        Error supplierFailure = new AssertionError("Should have been suppressed.");
        Supplier<Collection<CompletionException>> dependencyFailuresSupplier = () -> {
            throw supplierFailure;
        };
        CallException similarCallException = new CallException(caller, failedNode, new Throwable());
        similarCallException.addNodeCallStackTraceElement(caller, failedNode);

        CompletionException container = SUPPRESS_DEPENDENCY_FAILURES.constructContainerException(caller, failedNode,
                nodeFailure, dependencyFailuresSupplier);

        assertThat(container, sameInstance(nodeFailure));
        assertThat(container.getSuppressed(), arrayContaining(supplierFailure));
        assertThat(container.getCause().getMessage(), is(similarCallException.getMessage()));
    }
}
