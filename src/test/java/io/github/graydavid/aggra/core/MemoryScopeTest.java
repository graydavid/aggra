package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.TestData.arbitraryBehaviorWithCustomCancelAction;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

public class MemoryScopeTest {
    private final Caller caller = () -> Role.of("caller");

    @Test
    public void createForGraphThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class, () -> MemoryScope.createForGraph(null));
    }

    @Test
    public void createForGraphCreatesAResponsiveToPassiveCancelMemoryScopeForEmptyGraph() {
        MemoryScope scope = MemoryScope.createForGraph(graphWithRoots());

        assertTrue(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    // SafeVarargs justification: varargs not written to or passed to untrusted code
    @SafeVarargs
    private static Graph<TestMemory> graphWithRoots(Node<TestMemory, ?>... roots) {
        return Graph.fromRoots(Role.of("graph"), Set.copyOf(List.of(roots)));
    }

    @Test
    public void createForGraphCreatesAResponsiveToPassiveCancelMemoryScopeForNodesThatDontWaitForAllDependencies() {
        Node<TestMemory, Integer> dependency = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForGraph(graphWithRoots(consumer));

        assertTrue(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertThrows(UnsupportedOperationException.class, () -> scope.addReplyToTrack(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.addChildMemoryScopeToTrack(mock(MemoryScope.class)));
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    @Test
    public void createForGraphCreatesAResponsiveToPassiveCancelMemoryScopeForNodesThatWaitForAllDependencies() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForGraph(graphWithRoots(node));

        assertTrue(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertThrows(UnsupportedOperationException.class, () -> scope.addReplyToTrack(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.addChildMemoryScopeToTrack(mock(MemoryScope.class)));
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    @Test
    public void createForGraphCreatesANewResponsiveToPassiveCancelMemoryScopeEachTime() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope1 = MemoryScope.createForGraph(graphWithRoots(node));
        MemoryScope scope2 = MemoryScope.createForGraph(graphWithRoots(node));

        assertThat(scope1, not(scope2));
    }

    @Test
    public void createForGraphCreatesAFullyResponsiveScopeIfEvenOneNodesShadowSupportsActiveCancelHooks() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();

        Node<TestMemory, Integer> activeDependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("active-dependency"))
                .role(Role.of("active-dependency"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Node.CommunalBuilder<TestMemory> activeBuilder = Node.communalBuilder(TestMemory.class);
        activeBuilder.primedDependency(activeDependency);
        Node<TestMemory, Integer> active = activeBuilder.type(Type.generic("active"))
                .role(Role.of("active"))
                .build(device -> CompletableFuture.completedFuture(10));
        Reply<?> dependencyReply = Reply.forCall(caller, activeDependency);

        MemoryScope scope = MemoryScope.createForGraph(graphWithRoots(nonResponsive, active));

        assertTrue(scope.isResponsiveToCancelSignal());
        assertTrue(scope.supportsActiveCancelHooks());
        scope.addReplyToTrack(dependencyReply);// Doesn't throw
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    private static Node<TestMemory, Integer> nonResponsiveNode() {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("passive"))
                .role(Role.of("passive"))
                .build(device -> CompletableFuture.completedFuture(12));
    }

    @Test
    public void createForExternallyAccessibleNodeCreatesAResponsiveToPassiveCancelMemoryScopeForNodesThatDontWaitForAllDependencies() {
        Node<TestMemory, Integer> dependency = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(consumer, passiveHooksRootMemoryScope());

        assertTrue(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertThrows(UnsupportedOperationException.class, () -> scope.addReplyToTrack(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.addChildMemoryScopeToTrack(mock(MemoryScope.class)));
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    private static MemoryScope passiveHooksRootMemoryScope() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(nonResponsive));
        return MemoryScope.createForGraph(graph);
    }

    @Test
    public void createForExternallyAccessibleNodeThrowsExceptionGivenNullArguments() {
        assertThrows(NullPointerException.class,
                () -> MemoryScope.createForExternallyAccessibleNode(null, passiveHooksRootMemoryScope()));
        assertThrows(NullPointerException.class,
                () -> MemoryScope.createForExternallyAccessibleNode(nonResponsiveNode(), null));
    }

    @Test
    public void createForExternallyAccessibleNodeCreatesANonresponsiveScopeForNodesThatWaitForAllDependencies() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(node, passiveHooksRootMemoryScope());

        assertFalse(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertThrows(UnsupportedOperationException.class, () -> scope.addReplyToTrack(NodeMocks.reply()));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.addChildMemoryScopeToTrack(mock(MemoryScope.class)));
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertFalse(scope.isCancelSignalTriggered());
    }

    @Test
    public void createForExternallyAccessibleNodeCreatesANewNonresponsiveScopeEachTime() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope1 = MemoryScope.createForExternallyAccessibleNode(node, passiveHooksRootMemoryScope());
        MemoryScope scope2 = MemoryScope.createForExternallyAccessibleNode(node, passiveHooksRootMemoryScope());

        assertThat(scope1, not(scope2));
    }

    @Test
    public void createForExternallyAccessibleNodeBasesScopeOnMinimumDependencyLifetime() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                // Even though the declared DependencyLifetime is GRAPH, the minimum is NODE_FOR_ALL
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(node, passiveHooksRootMemoryScope());

        assertFalse(scope.isResponsiveToCancelSignal());
        assertFalse(scope.supportsActiveCancelHooks());
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertFalse(scope.isCancelSignalTriggered());
    }

    @Test
    public void createForExternallyAccessibleNodeCreatesAResponsiveScopeForNodesWhoseShadowSupportsActiveCancelHooks() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(10));
        Reply<?> dependencyReply = Reply.forCall(caller, dependency);

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(node, activeHooksRootMemoryScope());

        assertTrue(scope.isResponsiveToCancelSignal());
        assertTrue(scope.supportsActiveCancelHooks());
        scope.addReplyToTrack(dependencyReply);// Doesn't throw
        assertFalse(scope.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(scope.isCancelSignalTriggered());
    }

    private static MemoryScope activeHooksRootMemoryScope() {
        Node<TestMemory, Integer> active = activeHooksNode();
        Graph<TestMemory> graph = Graph.fromRoots(Role.of("graph"), Set.of(active));
        return MemoryScope.createForGraph(graph);
    }

    private static Node<TestMemory, Integer> activeHooksNode() {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("active"))
                .role(Role.of("active"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
    }

    @Test
    public void createForExternallyAccessibleNodeAddsActiveHooksSupportingChildScopesToParentsTrackedScopes() {
        MemoryScope activeParentScope = activeHooksRootMemoryScope();
        Node<TestMemory, Integer> activeChildNode = activeHooksNode();
        MemoryScope activeChildScope = MemoryScope.createForExternallyAccessibleNode(activeChildNode,
                activeParentScope);
        Reply<Integer> childNodeReply = Reply.forCall(() -> Role.of("first-caller"), activeChildNode);
        AtomicBoolean childReplyCancelTriggered = new AtomicBoolean(false);
        childNodeReply.setCustomCancelAction(mayInterrupt -> {
            childReplyCancelTriggered.set(true);
        });
        activeChildScope.addReplyToTrack(childNodeReply);

        activeParentScope.triggerCancelSignal();

        assertTrue(activeChildScope.isCancelSignalTriggered());
        assertTrue(childReplyCancelTriggered.get());
    }

    @Test
    public void rootScopesReturnThemselvesForNearestAncestorResponsiveToCancellationSignal() {
        MemoryScope activeRootScope = activeHooksRootMemoryScope();
        MemoryScope passiveRootScope = passiveHooksRootMemoryScope();

        assertThat(activeRootScope.getNearestAncestorOrSelfResponsiveToCancelSignal(), is(activeRootScope));
        assertThat(passiveRootScope.getNearestAncestorOrSelfResponsiveToCancelSignal(), is(passiveRootScope));
    }

    @Test
    public void nonRootResponsiveToCancelMemoryScopesReturnThemselvesForNearestAncestorResponsiveToCancellationSignal() {
        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(activeHooksNode(),
                activeHooksRootMemoryScope());

        assertTrue(scope.supportsActiveCancelHooks());
        assertThat(scope.getNearestAncestorOrSelfResponsiveToCancelSignal(), is(scope));
    }

    @Test
    public void nonRootResponsiveToPassiveCancelMemoryScopesReturnThemselvesForNearestAncestorResponsiveToCancellationSignal() {
        Node<TestMemory, Integer> dependency = TestData.nodeReturningValue(5);
        Node.CommunalBuilder<TestMemory> consumerBuilder = Node.communalBuilder(TestMemory.class);
        consumerBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> consumer = consumerBuilder.type(Type.generic("consumer"))
                .role(Role.of("consumer"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(12));

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(consumer, activeHooksRootMemoryScope());

        assertFalse(scope.supportsActiveCancelHooks());
        assertTrue(scope.isResponsiveToCancelSignal());
        assertThat(scope.getNearestAncestorOrSelfResponsiveToCancelSignal(), is(scope));
    }

    @Test
    public void nonResponsiveToCancelMemoryScopeUsesParentForNearestAncestorResponsiveToCancellationSignalWhenParentIsResponsiveToCancel() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();
        MemoryScope responsiveParentScope = activeHooksRootMemoryScope();

        MemoryScope nonResponsiveScope = MemoryScope.createForExternallyAccessibleNode(nonResponsive,
                responsiveParentScope);

        assertTrue(responsiveParentScope.isResponsiveToCancelSignal());
        assertFalse(nonResponsiveScope.isResponsiveToCancelSignal());
        assertThat(nonResponsiveScope.getNearestAncestorOrSelfResponsiveToCancelSignal(), is(responsiveParentScope));
        nonResponsiveScope.triggerCancelSignal();

        assertFalse(nonResponsiveScope.isCancelSignalTriggered());
        responsiveParentScope.triggerCancelSignal();

        assertTrue(nonResponsiveScope.isCancelSignalTriggered());
    }

    @Test
    public void nonResponsiveToCancelMemoryScopeUsesFirstResponsiveNonParentAncestorForNearestAncestorResponsiveToCancellationSignal() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();
        MemoryScope responsiveGrandparentScope = activeHooksRootMemoryScope();
        MemoryScope nonResponsiveParentScope = MemoryScope.createForExternallyAccessibleNode(nonResponsiveNode(),
                responsiveGrandparentScope);

        MemoryScope nonResponsiveScope = MemoryScope.createForExternallyAccessibleNode(nonResponsive,
                nonResponsiveParentScope);

        assertTrue(responsiveGrandparentScope.isResponsiveToCancelSignal());
        assertFalse(nonResponsiveParentScope.isResponsiveToCancelSignal());
        assertFalse(nonResponsiveScope.isResponsiveToCancelSignal());
        assertThat(nonResponsiveScope.getNearestAncestorOrSelfResponsiveToCancelSignal(),
                is(responsiveGrandparentScope));
        nonResponsiveScope.triggerCancelSignal();
        nonResponsiveParentScope.triggerCancelSignal();

        assertFalse(nonResponsiveScope.isCancelSignalTriggered());
        responsiveGrandparentScope.triggerCancelSignal();

        assertTrue(nonResponsiveScope.isCancelSignalTriggered());
    }

    @Test
    public void addChildMemoryScopeToTrackThrowsExceptionForMemoryScopesNotSupportingActiveCancelHooks() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();
        MemoryScope nonResponsiveScope = MemoryScope.createForExternallyAccessibleNode(nonResponsive,
                activeHooksRootMemoryScope());

        Node<TestMemory, Integer> active = activeHooksNode();
        MemoryScope activeScope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());

        assertTrue(activeScope.supportsActiveCancelHooks());
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> activeScope.addChildMemoryScopeToTrack(nonResponsiveScope));
        assertThat(thrown.getMessage(),
                containsString("Shouldn't add a memory scope that doesn't support active cancel hooks"));
    }

    @Test
    public void responsiveToCancelMemoryScopeAddReplyToTrackThrowsExceptionForReplyNodesNotSupportingActiveCancelHooks() {
        Node<TestMemory, Integer> nonResponsive = nonResponsiveNode();
        Node<TestMemory, Integer> active = activeHooksNode();
        Reply<?> passiveReply = Reply.forCall(caller, nonResponsive);

        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());

        assertTrue(scope.supportsActiveCancelHooks());
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> scope.addReplyToTrack(passiveReply));
        assertThat(thrown.getMessage(),
                containsString("Shouldn't add a reply whose Node doesn't support active cancel hooks"));
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersReplySignalViaAddReplyToTrackAndThenMemoryScopeCancelSignalTriggering() {
        Node<TestMemory, Integer> active = activeHooksNode();
        Reply<?> reply = Reply.forCall(caller, active);
        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());
        scope.addReplyToTrack(reply);

        assertFalse(reply.isCancelSignalTriggered());
        scope.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersReplySignalViaMemoryScopeCancelSignalTriggeringAndThenAddReplyToTrack() {
        Node<TestMemory, Integer> active = activeHooksNode();
        Reply<?> reply = Reply.forCall(caller, active);
        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());
        scope.triggerCancelSignal();

        assertFalse(reply.isCancelSignalTriggered());
        scope.addReplyToTrack(reply);

        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeAllowsMultipleTriggers() {
        Node<TestMemory, Integer> active = activeHooksNode();
        Reply<?> reply = Reply.forCall(caller, active);
        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());
        scope.addReplyToTrack(reply);

        scope.triggerCancelSignal();
        scope.triggerCancelSignal();
        scope.triggerCancelSignal();

        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersChildMemoryScopeCancelSignalViaAddChildMemoryScopeToToTrackAndThenMemoryScopeCancelSignalTriggering() {
        MemoryScope activeScope = activeHooksRootMemoryScope();
        MemoryScope childScope = MemoryScope.createForExternallyAccessibleNode(activeHooksNode(),
                activeHooksRootMemoryScope());
        activeScope.addChildMemoryScopeToTrack(childScope);

        assertFalse(childScope.isCancelSignalTriggered());
        activeScope.triggerCancelSignal();

        assertTrue(childScope.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersChildMemoryScopeCancelSignalViaMemoryScopeCancelSignalTriggeringAndThenAddChildMemoryScopeToToTrack() {
        MemoryScope activeScope = activeHooksRootMemoryScope();
        MemoryScope childScope = MemoryScope.createForExternallyAccessibleNode(activeHooksNode(),
                activeHooksRootMemoryScope());
        activeScope.triggerCancelSignal();

        assertFalse(childScope.isCancelSignalTriggered());
        activeScope.addChildMemoryScopeToTrack(childScope);

        assertTrue(childScope.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersEachReplyCancelSignalOnceRegardlessOfRaceConditions() {
        // Multi-threaded code is non-deterministic; so try multiple times.
        final int NUM_TRIES = 100;
        IntStream.range(0, NUM_TRIES)
                .forEach(
                        this::tryOnceToVerifyResponsiveToCancellationMemoryScopeTriggersEachReplyCancelSignalOnceRegardlessOfRaceConditions);
    }

    private void tryOnceToVerifyResponsiveToCancellationMemoryScopeTriggersEachReplyCancelSignalOnceRegardlessOfRaceConditions(
            int trialNum) {
        Node<TestMemory, Integer> active = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("active"))
                .role(Role.of("active"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Reply<?> reply = Reply.forCall(caller, active);
        MemoryScope scope = MemoryScope.createForExternallyAccessibleNode(active, activeHooksRootMemoryScope());
        Runnable trackReply = () -> scope.addReplyToTrack(reply);
        Runnable cancel = () -> scope.triggerCancelSignal();

        new ConcurrentLoadGenerator(List.of(trackReply, cancel)).run();

        assertTrue(reply.isCancelSignalTriggered());
    }

    @Test
    public void responsiveToCancellationMemoryScopeTriggersEachMemoryScopeCancelSignalExactlyOnceRegardlessOfRaceConditions() {
        // Multi-threaded code is non-deterministic; so try multiple times.
        final int NUM_TRIES = 100;
        IntStream.range(0, NUM_TRIES)
                .forEach(
                        this::tryOnceToVerifyResponsiveToCancellationMemoryScopeTriggersEachMemoryScopeCancelSignalOnceRegardlessOfRaceConditions);
    }

    private void tryOnceToVerifyResponsiveToCancellationMemoryScopeTriggersEachMemoryScopeCancelSignalOnceRegardlessOfRaceConditions(
            int trialNum) {
        MemoryScope activeScope = activeHooksRootMemoryScope();
        MemoryScope childScope = MemoryScope.createForExternallyAccessibleNode(activeHooksNode(),
                activeHooksRootMemoryScope());
        Runnable trackMemoryScope = () -> activeScope.addChildMemoryScopeToTrack(childScope);
        Runnable cancel = () -> activeScope.triggerCancelSignal();

        new ConcurrentLoadGenerator(List.of(trackMemoryScope, cancel)).run();

        assertTrue(childScope.isCancelSignalTriggered());
    }
}
