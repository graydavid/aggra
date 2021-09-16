package io.github.graydavid.aggra.core;

import static io.github.graydavid.aggra.core.TestData.arbitraryBehaviorWithCustomCancelAction;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.Dependencies.AncestorMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.NewMemoryDependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.Dependencies.SameMemoryDependency;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.GraphValidators.GraphValidator;
import io.github.graydavid.aggra.core.TestData.TestChildMemory;
import io.github.graydavid.aggra.core.TestData.TestMemory;

/**
 * Tests all structural aspects of Node, like building methods and accessors. You can find other Node-related tests
 * under Node_*Test classes.
 */
public class Node_StructureTest {
    private final Node<TestMemory, Integer> primedDependency = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("prime-type"))
            .role(Role.of("dependency-to-prime"))
            .build(device -> CompletableFuture.completedFuture(1));
    private final Node<TestMemory, Integer> sameMemoryUnprimedDependency = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("not-prime-type"))
            .role(Role.of("dependency-not-to-prime"))
            .build(device -> CompletableFuture.completedFuture(1));
    private final Node<TestChildMemory, Integer> newMemoryDependency = Node.communalBuilder(TestChildMemory.class)
            .type(Type.generic("not-prime-type"))
            .role(Role.of("new-memory-dependency"))
            .build(device -> CompletableFuture.completedFuture(1));
    private final Node<TestChildMemory, Integer> ancestorMemoryDependency = Node.communalBuilder(TestChildMemory.class)
            .type(Type.generic("not-prime-type"))
            .role(Role.of("ancestor-memory-dependency"))
            .build(device -> CompletableFuture.completedFuture(1));

    @Test
    public void communalBuilderThrowsExceptionGivenNullMemory() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(null));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullType() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(TestMemory.class).type(null));
    }

    @Test
    public void communalBuilderBuildThrowsExceptionGivenUnsetType() {
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .role(Role.of("role"))
                        .build(device -> CompletableFuture.completedFuture(14)));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenIncompatibleType() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(new IncompatibleWithAllType("incompatible")));

        assertThat(thrown.getMessage(), containsString("'incompatible' is not compatible"));
    }

    private static class IncompatibleWithAllType extends Type {
        protected IncompatibleWithAllType(String name) {
            super(name);
        }

        @Override
        public boolean isCompatibleWithTypeInstance(TypeInstance instance) {
            return false;
        }
    }

    @Test
    public void communalBuilderThrowsExceptionGivenIncompatibleTypeTypeInstance() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .typeTypeInstance(new IncompatibleWithAllType("incompatible"), TypeInstance.defaultValue()));

        assertThat(thrown.getMessage(), containsString("'incompatible' is not compatible"));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullTypeInstance() {
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class).typeTypeInstance(Type.generic("generic"), null));
    }

    @Test
    public void builderThrowsExceptionGivenNullRole() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(TestMemory.class).role(null));
    }

    @Test
    public void builderBuildThrowsExceptionGivenUnsetRole() {
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .type(Type.generic("type"))
                        .build(device -> CompletableFuture.completedFuture(12)));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullDependencyMaxLifetime() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(TestMemory.class).dependencyLifetime(null));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullExceptionStrategy() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(TestMemory.class).exceptionStrategy(null));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullPrimedDependencies() {
        assertThrows(NullPointerException.class, () -> Node.communalBuilder(TestMemory.class).primedDependency(null));
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class).sameMemoryUnprimedDependency(null));
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class).newMemoryDependency(null));
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class).ancestorMemoryDependency(null));
    }

    @Test
    public void builderThrowsExceptionGivenNullGraphValidatorFactory() {
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .type(Type.generic("type"))
                        .role((Role) null)
                        .graphValidatorFactory(null));
    }

    @Test
    public void communalBuilderPrimingFailureStrategyThrowsExceptionGivenIncompatibleDependencyLifetime() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("invalid"));

        assertThrows(IllegalArgumentException.class,
                () -> builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThrows(IllegalArgumentException.class, () -> builder
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.NODE_FOR_DIRECT));
    }

    @Test
    public void communalBuilderDependencyLifetimeThrowsExceptionGivenIncompatiblePrimingFailureStrategy() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("invalid"))
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH);

        assertThrows(IllegalArgumentException.class,
                () -> builder.dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenSameNodeAddedAsPrimedAndUnprimed() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));
        builder.primedDependency(primedDependency);

        assertThrows(IllegalArgumentException.class, () -> builder.sameMemoryUnprimedDependency(primedDependency));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenSameNodeAddedAsSameMemoryUnprimedAndPrimed() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);

        assertThrows(IllegalArgumentException.class, () -> builder.primedDependency(sameMemoryUnprimedDependency));
    }

    @Test
    public void communalBuilderPassesGivenSameNodeAddedAsNewAndAncestorMemoryDependencyBoth() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));
        builder.newMemoryDependency(newMemoryDependency);

        builder.ancestorMemoryDependency(newMemoryDependency);
    }

    @Test
    public void communalBuilderPassesGivenSameNodeAddedAsAncestorAndNewMemoryDependencyBoth() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));
        builder.ancestorMemoryDependency(ancestorMemoryDependency);

        builder.newMemoryDependency(ancestorMemoryDependency);
    }

    @Test
    public void communalBuilderThrowsExceptionGivenPrimedDependencyAddedAsDifferentMemoryDependency() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));

        assertThrows(IllegalArgumentException.class, () -> builder.newMemoryDependency(primedDependency));
        assertThrows(IllegalArgumentException.class, () -> builder.ancestorMemoryDependency(primedDependency));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenSameMemoryUnprimedDependencyAddedAsDifferentMemoryDependency() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("overlapping"));

        assertThrows(IllegalArgumentException.class, () -> builder.newMemoryDependency(sameMemoryUnprimedDependency));
        assertThrows(IllegalArgumentException.class,
                () -> builder.ancestorMemoryDependency(sameMemoryUnprimedDependency));
    }

    @Test
    public void communalBuilderThrowsExceptionGivenNullBehaviorAfterPrimingDependencies() {
        assertThrows(NullPointerException.class,
                () -> Node.communalBuilder(TestMemory.class)
                        .type(Type.generic("type"))
                        .role(Role.of("role"))
                        .build(null));
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForNonEmptyCommunalNode() {
        TypeInstance typeInstance = mock(TypeInstance.class);
        ForNodeGraphValidatorFactory validatorFactory1 = mock(ForNodeGraphValidatorFactory.class);
        ForNodeGraphValidatorFactory validatorFactory2 = mock(ForNodeGraphValidatorFactory.class);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .typeTypeInstance(Type.generic("main-type"), typeInstance)
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory1)
                .graphValidatorFactory(validatorFactory2);
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getType(), is(Type.generic("main-type")));
        assertThat(main.getTypeInstance(), sameInstance(typeInstance));
        assertThat(main.getRole(), is(Role.of("main")));
        assertThat(main.getPrimedDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(primedDependency, PrimingMode.PRIMED)));
        assertThat(main.getDependencies(),
                containsInAnyOrder(Dependencies.newSameMemoryDependency(primedDependency, PrimingMode.PRIMED),
                        Dependencies.newSameMemoryDependency(sameMemoryUnprimedDependency, PrimingMode.UNPRIMED),
                        Dependencies.newNewMemoryDependency(newMemoryDependency),
                        Dependencies.newAncestorMemoryDependency(ancestorMemoryDependency)));
        assertThat(main.getDependencyNodes(), containsInAnyOrder(primedDependency, sameMemoryUnprimedDependency,
                newMemoryDependency, ancestorMemoryDependency));
        assertThat(main.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(main.getCancelMode(), is(CancelMode.DEFAULT));
        assertThat(main.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(main.getGraphValidatorFactories(), containsInAnyOrder(validatorFactory1, validatorFactory2));
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForEmptyCommunalNode() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getPrimedDependencies(), empty());
        assertThat(main.getDependencies(), empty());
        assertThat(main.getDependencyNodes(), empty());
        assertThat(main.getGraphValidatorFactories(), empty());
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForBehaviorWithCompositeCancelSignalCommunalNode() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(1));

        assertThat(main.getCancelMode(), is(CancelMode.COMPOSITE_SIGNAL));
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForNonInterruptibleBehaviorWithCustomCancelActionCommunalNode() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        assertThat(main.getCancelMode(), is(CancelMode.CUSTOM_ACTION));
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForInterruptibleBehaviorWithCustomCancelActionCommunalNode() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(true));

        assertThat(main.getCancelMode(), is(CancelMode.INTERRUPT));
    }

    @Test
    public void getDependencyNodesReturnsTheSameAnswerForMultipleCalls() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        Set<Node<?, ?>> call1Response = main.getDependencyNodes();
        Set<Node<?, ?>> call2Response = main.getDependencyNodes();

        assertThat(call1Response, is(call2Response));
    }

    @Test
    public void accessorsReturnUnmodifiableCollections() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory);
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThrows(UnsupportedOperationException.class, () -> main.getPrimedDependencies()
                .add(Dependencies.newSameMemoryDependency(primedDependency, PrimingMode.PRIMED)));
        assertThrows(UnsupportedOperationException.class, () -> main.getDependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> main.getDependencyNodes().add(NodeMocks.node()));
        assertThrows(UnsupportedOperationException.class,
                () -> main.getGraphValidatorFactories().add(mock(ForNodeGraphValidatorFactory.class)));
    }

    @Test
    public void communalNodeTypeDefaultsTypeInstance() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getType(), is(Type.generic("main-type")));
        assertThat(main.getTypeInstance(), sameInstance(TypeInstance.defaultValue()));
    }

    @Test
    public void communalNodeAllowsSettingOfPrimingFailureStrategyCompatibleWithExistingDependencyLifetime() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP)
                .build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThat(main.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
    }

    @Test
    public void communalNodeAllowsSettingOfPrimingFailureStrategyAndDependencyLifetimeSimultaneously() {
        Node<TestMemory, Integer> main = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThat(main.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
    }

    @Test
    public void accessorsReturnDataUsedToConstructNodeForInputNode() {
        ForNodeGraphValidatorFactory validatorFactory1 = mock(ForNodeGraphValidatorFactory.class);
        ForNodeGraphValidatorFactory validatorFactory2 = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, Integer> main = Node.inputBuilder(TestMemory.class)
                .role(Role.of("main"))
                .graphValidatorFactory(validatorFactory1)
                .graphValidatorFactory(validatorFactory2)
                .build();

        assertThat(main.getType(), is(Node.InputBuilder.INPUT_TYPE));
        assertThat(main.getRole(), is(Role.of("main")));
        assertThat(main.getPrimedDependencies(), empty());
        assertThat(main.getDependencies(), empty());
        assertThat(main.getDependencyNodes(), empty());
        assertThat(main.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(main.getCancelMode(), is(CancelMode.DEFAULT));
        assertThat(main.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(main.getGraphValidatorFactories(), containsInAnyOrder(validatorFactory1, validatorFactory2));
    }

    @Test
    public void inputTypeProtectsAgainstCounterfeits() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Node.communalBuilder(TestMemory.class).type(Node.InputBuilder.INPUT_TYPE));

        assertThat(thrown.getMessage(), containsString("is not compatible with"));
    }

    @Test
    public void communalBuilderSetsReasonableDefaultsForStrategies() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("type"))
                .role(Role.of("role"))
                .build(device -> CompletableFuture.completedFuture(1));

        assertThat(node.getPrimingFailureStrategy(), is(PrimingFailureStrategy.WAIT_FOR_ALL_CONTINUE));
        assertThat(node.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_DIRECT));
        assertThat(node.getExceptionStrategy(), is(ExceptionStrategy.SUPPRESS_DEPENDENCY_FAILURES));
    }

    @Test
    public void communalBuilderDeduplicatesSameNodeAddedAsSameDependency() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));
        SameMemoryDependency<TestMemory, Integer> samePrimed = builder.primedDependency(primedDependency);
        SameMemoryDependency<TestMemory, Integer> sameUnprimed = builder
                .sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        NewMemoryDependency<TestChildMemory, Integer> newD = builder.newMemoryDependency(newMemoryDependency);
        AncestorMemoryDependency<TestChildMemory, Integer> ancestor = builder
                .ancestorMemoryDependency(ancestorMemoryDependency);

        assertThat(builder.primedDependency(primedDependency), equalTo(samePrimed));
        assertThat(builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency), equalTo(sameUnprimed));
        assertThat(builder.newMemoryDependency(newMemoryDependency), equalTo(newD));
        assertThat(builder.ancestorMemoryDependency(ancestorMemoryDependency), equalTo(ancestor));
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));
        assertThat(main.getDependencies(), hasSize(4));
    }

    @Test
    public void communalBuilderClearDependenciesRemovesAllDependencies() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);

        builder.clearDependencies();
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getDependencies(), empty());
    }

    @Test
    public void communalBuilderClearDependenciesRemovesAssociationWithPreviouslyAddedNodes() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));
        builder.primedDependency(primedDependency);

        builder.clearDependencies();

        // This would otherwise throw an exception if associations not cleared
        builder.sameMemoryUnprimedDependency(primedDependency);
    }

    @Test
    public void builderClearGraphValidatorFactoriesRemovesAllValidators() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .graphValidatorFactory(validatorFactory);

        builder.clearGraphValidatorFactories();
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getGraphValidatorFactories(), empty());
    }

    @Test
    public void inputBuilderMethodsCanBeCalledMultipleTimesBeforeBuild() {
        Node.InputBuilder<TestMemory, Integer> builder = Node.inputBuilder(TestMemory.class);

        builder.clearGraphValidatorFactories();
        builder.clearGraphValidatorFactories();

        builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class));
        builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class));

        builder.role(Role.of("input"));
        builder.role(Role.of("input"));
    }

    @Test
    public void inputBuilderSupportStopsAfterOneBuild() {
        Node.InputBuilder<TestMemory, Integer> builder = Node.inputBuilder(TestMemory.class).role(Role.of("input"));

        builder.build();

        assertThrows(IllegalStateException.class, () -> builder.build());
        assertThrows(IllegalStateException.class, () -> builder.clearGraphValidatorFactories());
        assertThrows(IllegalStateException.class,
                () -> builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class)));
        assertThrows(IllegalStateException.class, () -> builder.role(Role.of("input")));
        assertThrows(IllegalStateException.class, () -> builder.build());
    }

    @Test
    public void communalBuilderMethodsCanBeCalledMultipleTimesBeforeBuild() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);

        builder.ancestorMemoryDependency(NodeMocks.node());
        builder.ancestorMemoryDependency(NodeMocks.node());

        builder.clearDependencies();
        builder.clearDependencies();

        builder.clearGraphValidatorFactories();
        builder.clearGraphValidatorFactories();

        builder.dependencyLifetime(DependencyLifetime.GRAPH);
        builder.dependencyLifetime(DependencyLifetime.GRAPH);

        builder.exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES);
        builder.exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES);

        builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class));
        builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class));

        builder.newMemoryDependency(NodeMocks.node());
        builder.newMemoryDependency(NodeMocks.node());

        builder.primedDependency(NodeMocks.node());
        builder.primedDependency(NodeMocks.node());

        builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP);
        builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP);

        builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH);
        builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH);

        builder.role(Role.of("input"));
        builder.role(Role.of("input"));

        builder.sameMemoryUnprimedDependency(NodeMocks.node());
        builder.sameMemoryUnprimedDependency(NodeMocks.node());

        builder.type(Type.generic("type"));
        builder.type(Type.generic("type"));
    }

    @Test
    public void communalBuilderSupportStopsAfterOneBuild() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));

        builder.build(device -> CompletableFuture.completedFuture(15));

        assertThrows(IllegalStateException.class, () -> builder.ancestorMemoryDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class, () -> builder.clearDependencies());
        assertThrows(IllegalStateException.class, () -> builder.clearGraphValidatorFactories());
        assertThrows(IllegalStateException.class, () -> builder.dependencyLifetime(DependencyLifetime.GRAPH));
        assertThrows(IllegalStateException.class,
                () -> builder.exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThrows(IllegalStateException.class,
                () -> builder.graphValidatorFactory(mock(ForNodeGraphValidatorFactory.class)));
        assertThrows(IllegalStateException.class, () -> builder.newMemoryDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class, () -> builder.primedDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class,
                () -> builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThrows(IllegalStateException.class,
                () -> builder.primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH));
        assertThrows(IllegalStateException.class, () -> builder.role(Role.of("input")));
        assertThrows(IllegalStateException.class, () -> builder.sameMemoryUnprimedDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class, () -> builder.type(Type.generic("type")));
        assertThrows(IllegalStateException.class, () -> builder.build(device -> CompletableFuture.completedFuture(15)));
        assertThrows(IllegalStateException.class, () -> builder
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(15)));
        assertThrows(IllegalStateException.class,
                () -> builder.buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false)));
    }

    @Test
    public void behaviorWithCompositeCancelSignalCommunalBuilderSupportStopsAfterOneBuild() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));

        builder.buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(15));

        assertThrows(IllegalStateException.class, () -> builder.ancestorMemoryDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class, () -> builder.build(device -> CompletableFuture.completedFuture(15)));
        assertThrows(IllegalStateException.class, () -> builder
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(15)));
    }

    @Test
    public void behaviorWithCustomCancelActionCommunalBuilderSupportStopsAfterOneBuild() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));

        builder.buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        assertThrows(IllegalStateException.class, () -> builder.ancestorMemoryDependency(NodeMocks.node()));
        assertThrows(IllegalStateException.class, () -> builder.build(device -> CompletableFuture.completedFuture(15)));
        assertThrows(IllegalStateException.class, () -> builder
                .buildWithCompositeCancelSignal((device, signal) -> CompletableFuture.completedFuture(15)));
        assertThrows(IllegalStateException.class,
                () -> builder.buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false)));
    }

    @Test
    public void calculatesMinimumDependencyLifetimeFromDeclaredValue() {
        Node<TestMemory, Integer> graphNode = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("graph"))
                .role(Role.of("graph"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(graphNode.getMinimumDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
    }

    @Test
    public void calculatesMinimumDependencyLifetimeFromDependenciesMinimumDependencyLifetimes() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> CompletableFuture.completedFuture(10));
        Node.CommunalBuilder<TestMemory> directNodeBuilder = Node.communalBuilder(TestMemory.class);
        directNodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> directNode = directNodeBuilder.type(Type.generic("direct"))
                .role(Role.of("direct"))
                .dependencyLifetime(DependencyLifetime.NODE_FOR_DIRECT)
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(directNode.getMinimumDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
    }

    @Test
    public void getHasOnlyPrimedDependenciesReturnsTrueForNoDependencies() {
        Node<TestMemory, Integer> noDependencies = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("no-dependencies"))
                .role(Role.of("no-dependencies"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertTrue(noDependencies.getHasOnlyPrimedDependencies());
        assertThat(noDependencies.getPrimedDependencies(), empty());
    }

    @Test
    public void getHasOnlyPrimedDependenciesReturnsTrueGivenOnlyPrimedDependencies() {
        Node<TestMemory, Integer> primed = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("primed-dependency"))
                .role(Role.of("primed-dependency"))
                .build(device -> CompletableFuture.completedFuture(10));
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> same = builder.primedDependency(primed);
        Node<TestMemory, Integer> noDependencies = builder.type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertTrue(noDependencies.getHasOnlyPrimedDependencies());
        assertThat(noDependencies.getPrimedDependencies(), contains(same));
    }

    @Test
    public void getHasOnlyPrimedDependenciesReturnsFalseGivenAnyUnprimedDependencies() {
        Node<TestMemory, Integer> primed = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("primed-dependency"))
                .role(Role.of("primed-dependency"))
                .build(device -> CompletableFuture.completedFuture(10));
        Node<TestMemory, Integer> unprimed = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("unprimed-dependency"))
                .role(Role.of("unprimed-dependency"))
                .build(device -> CompletableFuture.completedFuture(10));
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class);
        SameMemoryDependency<TestMemory, Integer> same = builder.primedDependency(primed);
        builder.sameMemoryUnprimedDependency(unprimed);
        Node<TestMemory, Integer> noDependencies = builder.type(Type.generic("main"))
                .role(Role.of("main"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertFalse(noDependencies.getHasOnlyPrimedDependencies());
        assertThat(noDependencies.getPrimedDependencies(), contains(same));
    }

    @Test
    public void doesntHardCodeMinimumDependencyLifetimeToAll() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(10));
        Node.CommunalBuilder<TestMemory> graphNodeBuilder = Node.communalBuilder(TestMemory.class);
        graphNodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> graphNode = graphNodeBuilder.type(Type.generic("graph"))
                .role(Role.of("graph"))
                .dependencyLifetime(DependencyLifetime.GRAPH)
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(graphNode.getMinimumDependencyLifetime(), is(DependencyLifetime.GRAPH));
    }

    @Test
    public void shadowDoesntSupportActiveCancellationHooksForNodeWithoutDependenciesThatDoesnt() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(node.getCancelMode(), is(CancelMode.DEFAULT));
        assertFalse(node.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void shadowSupportsActiveCancelHooksForNodeWithoutDependenciesThatDoes() {
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));

        assertThat(node.getCancelMode(), is(CancelMode.CUSTOM_ACTION));
        assertTrue(node.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void shadowDoesntSupportActiveCancellationHooksForNodeWithDependenciesWhereNeitherDoes() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .build(device -> CompletableFuture.completedFuture(10));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(dependency.getCancelMode(), is(CancelMode.DEFAULT));
        assertThat(node.getCancelMode(), is(CancelMode.DEFAULT));
        assertFalse(node.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void shadowSupportsActiveCancelHooksForNodeWithDependenciesWhereNodeDoesntButDependencyDoes() {
        Node<TestMemory, Integer> dependency = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("dependency"))
                .role(Role.of("dependency"))
                .buildWithCustomCancelAction(arbitraryBehaviorWithCustomCancelAction(false));
        Node.CommunalBuilder<TestMemory> nodeBuilder = Node.communalBuilder(TestMemory.class);
        nodeBuilder.primedDependency(dependency);
        Node<TestMemory, Integer> node = nodeBuilder.type(Type.generic("node"))
                .role(Role.of("node"))
                .build(device -> CompletableFuture.completedFuture(10));

        assertThat(dependency.getCancelMode(), is(CancelMode.CUSTOM_ACTION));
        assertThat(node.getCancelMode(), is(CancelMode.DEFAULT));
        assertTrue(node.shadowSupportsActiveCancelHooks());
    }

    @Test
    public void createGraphValidatorsInvokesEachFactory() {
        ForNodeGraphValidatorFactory validatorFactory1 = mock(ForNodeGraphValidatorFactory.class);
        ForNodeGraphValidatorFactory validatorFactory2 = mock(ForNodeGraphValidatorFactory.class);
        Node<TestMemory, Integer> node = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("node"))
                .role(Role.of("node"))
                .graphValidatorFactory(validatorFactory1)
                .graphValidatorFactory(validatorFactory2)
                .build(device -> CompletableFuture.completedFuture(5));
        GraphValidator validator1 = mock(GraphValidator.class);
        when(validatorFactory1.create(node)).thenReturn(validator1);
        GraphValidator validator2 = mock(GraphValidator.class);
        when(validatorFactory2.create(node)).thenReturn(validator2);

        Collection<GraphValidator> validators = node.createGraphValidators();

        assertThat(validators, containsInAnyOrder(validator1, validator2));
    }

    @Test
    public void getSynopsisContainsBriefDescriptionOfTheNodeAloneForCommunalNode() {
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"));
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.getSynopsis(), is("[TestMemory] [main-type] main"));
    }

    @Test
    public void toStringContainsMoreDetailedDescriptionOfTheNodeAlone() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);
        when(validatorFactory.getDescription()).thenReturn("Is Test");
        Node.CommunalBuilder<TestMemory> builder = Node.communalBuilder(TestMemory.class)
                .type(Type.generic("main-type"))
                .role(Role.of("main"))
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory);
        builder.primedDependency(primedDependency);
        builder.sameMemoryUnprimedDependency(sameMemoryUnprimedDependency);
        builder.newMemoryDependency(newMemoryDependency);
        builder.ancestorMemoryDependency(ancestorMemoryDependency);
        Node<TestMemory, Integer> main = builder.build(device -> CompletableFuture.completedFuture(1));

        assertThat(main.toString(), is(
                "[TestMemory] [main-type] [FAIL_FAST_STOP] [GRAPH] [DISCARD_DEPENDENCY_FAILURES] [validates=(Is Test)] main"));
    }

    @Test
    public void getSynopsisContainsBriefDescriptionOfTheNodeAloneForInputNode() {
        Node<TestMemory, Integer> main = Node.inputBuilder(TestMemory.class).role(Role.of("main")).build();

        assertThat(main.getSynopsis(), is("[TestMemory] [Input] main"));
    }
}
