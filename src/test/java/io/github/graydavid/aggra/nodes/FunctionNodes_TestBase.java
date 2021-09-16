package io.github.graydavid.aggra.nodes;

import static io.github.graydavid.aggra.core.Dependencies.newSameMemoryDependency;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Streams;

import io.github.graydavid.aggra.core.Dependencies.Dependency;
import io.github.graydavid.aggra.core.Dependencies.PrimingMode;
import io.github.graydavid.aggra.core.DependencyLifetime;
import io.github.graydavid.aggra.core.ExceptionStrategy;
import io.github.graydavid.aggra.core.GraphValidators.ForNodeGraphValidatorFactory;
import io.github.graydavid.aggra.core.Node;
import io.github.graydavid.aggra.core.PrimingFailureStrategy;
import io.github.graydavid.aggra.core.Reply;
import io.github.graydavid.aggra.core.Role;
import io.github.graydavid.aggra.core.TestData;
import io.github.graydavid.aggra.core.TestData.TestMemory;
import io.github.graydavid.aggra.core.Type;
import io.github.graydavid.aggra.nodes.FunctionNodes.SpecifyFunctionAndDependencies;

/**
 * A common base class for testing FunctionNodes. You can find other FunctionNodes-related tests under
 * FunctionNodes_*Test classes.
 */
public abstract class FunctionNodes_TestBase {
    private final CompletableFuture<Integer> graphInput = CompletableFuture.completedFuture(55);

    protected abstract Type functionType();

    protected abstract SpecifyFunctionAndDependencies<TestMemory> functionNodesToTest(String role);

    protected Collection<Node<?, ?>> getExtraDependencies() {
        return List.of();
    }

    @Test
    public void catchesAndWrapsThrownRuntimeException() {
        RuntimeException runtimeException = new RuntimeException();
        Node<TestMemory, Void> runFunction = functionNodesToTest("catch-runtime-exception").run(() -> {
            throw runtimeException;
        });

        Reply<Void> reply = TestData.callNodeInNewTestMemoryGraph(graphInput, runFunction);

        assertThrows(Throwable.class, reply::join);
        assertThat(reply.getFirstNonContainerExceptionNow().get(), sameInstance(runtimeException));
    }

    @Test
    public void catchesAndWrapsThrownError() {
        Error error = new Error();
        Node<TestMemory, Void> runFunction = functionNodesToTest("catch-error").run(() -> {
            throw error;
        });

        Reply<Void> reply = TestData.callNodeInNewTestMemoryGraph(graphInput, runFunction);

        assertThrows(Throwable.class, reply::join);
        assertThat(reply.getFirstNonContainerExceptionNow().get(), sameInstance(error));
    }

    @Test
    public void allowsOptionalPrimingFailureStrategyToBeSet() {
        Node<TestMemory, Void> runFunction = functionNodesToTest("run-function")
                .primingFailureStrategy(PrimingFailureStrategy.FAIL_FAST_STOP, DependencyLifetime.GRAPH)
                .run(() -> {
                });

        assertThat(runFunction.getPrimingFailureStrategy(), is(PrimingFailureStrategy.FAIL_FAST_STOP));
        assertThat(runFunction.getDeclaredDependencyLifetime(), is(DependencyLifetime.GRAPH));
    }

    @Test
    public void allowsOtherOptionalSettingsToBeSet() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Void> runFunction = functionNodesToTest("run-function")
                .dependencyLifetime(DependencyLifetime.NODE_FOR_ALL)
                .exceptionStrategy(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES)
                .graphValidatorFactory(validatorFactory)
                .run(() -> {
                });

        assertThat(runFunction.getDeclaredDependencyLifetime(), is(DependencyLifetime.NODE_FOR_ALL));
        assertThat(runFunction.getExceptionStrategy(), is(ExceptionStrategy.DISCARD_DEPENDENCY_FAILURES));
        assertThat(runFunction.getGraphValidatorFactories(), contains(validatorFactory));
    }

    @Test
    public void canClearGraphValidatorFactories() {
        ForNodeGraphValidatorFactory validatorFactory = mock(ForNodeGraphValidatorFactory.class);

        Node<TestMemory, Void> runFunction = functionNodesToTest("run-function").graphValidatorFactory(validatorFactory)
                .clearGraphValidatorFactories()
                .run(() -> {
                });

        assertThat(runFunction.getGraphValidatorFactories(), empty());
    }

    @Test
    public void runsFunction() {
        Runnable function = mock(Runnable.class);
        Node<TestMemory, Void> runFunction = functionNodesToTest("run-function").run(function);

        TestData.callNodeInNewTestMemoryGraph(graphInput, runFunction).join();

        verify(function).run();
        assertThat(runFunction.getType(), is(functionType()));
        assertThat(runFunction.getRole(), is(Role.of("run-function")));
        assertThat(runFunction.getDependencies(), containsDependencyNodes());
    }

    protected Matcher<Collection<? extends Dependency<?, ?>>> containsDependencyNodes(Node<?, ?>... dependencyNodes) {
        Set<Dependency<?, ?>> dependencies = Streams
                .concat(Arrays.stream(dependencyNodes), getExtraDependencies().stream())
                .map(node -> newSameMemoryDependency(node, PrimingMode.PRIMED))
                .collect(Collectors.toSet());
        return is(dependencies);
    }

    @Test
    public void getsRawValues() {
        Node<TestMemory, String> getRawValue = functionNodesToTest("supply-raw-value").getValue("raw-value");

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, getRawValue).join(), is("raw-value"));
        assertThat(getRawValue.getType(), is(functionType()));
        assertThat(getRawValue.getRole(), is(Role.of("supply-raw-value")));
        assertThat(getRawValue.getDependencies(), containsDependencyNodes());
    }

    @Test
    public void getsThroughSupplier() {
        Node<TestMemory, String> getThroughSupplier = functionNodesToTest("supply-through-supplier").get(() -> "value");

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, getThroughSupplier).join(), is("value"));
        assertThat(getThroughSupplier.getType(), is(functionType()));
        assertThat(getThroughSupplier.getRole(), is(Role.of("supply-through-supplier")));
        assertThat(getThroughSupplier.getDependencies(), containsDependencyNodes());
    }

    @Test
    public void appliesOneAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");

        Node<TestMemory, String> apply1AryFunctionToStrings = functionNodesToTest("apply-1-ary-function-to-strings")
                .apply(string1 -> "applied-" + string1, supplyString1);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply1AryFunctionToStrings).join(),
                is("applied-string-1"));
        assertThat(apply1AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply1AryFunctionToStrings.getRole(), is(Role.of("apply-1-ary-function-to-strings")));
        assertThat(apply1AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1));
    }

    @Test
    public void appliesTwoAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");

        Node<TestMemory, String> apply2AryFunctionToStrings = functionNodesToTest("apply-2-ary-function-to-strings")
                .apply((string1, string2) -> "applied-" + string1 + '-' + string2, supplyString1, supplyString2);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply2AryFunctionToStrings).join(),
                is("applied-string-1-string-2"));
        assertThat(apply2AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply2AryFunctionToStrings.getRole(), is(Role.of("apply-2-ary-function-to-strings")));
        assertThat(apply2AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2));
    }

    @Test
    public void appliesThreeAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");

        Node<TestMemory, String> apply3AryFunctionToStrings = functionNodesToTest("apply-3-ary-function-to-strings")
                .apply((string1, string2, string3) -> "applied-" + string1 + '-' + string2 + '-' + string3,
                        supplyString1, supplyString2, supplyString3);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply3AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3"));
        assertThat(apply3AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply3AryFunctionToStrings.getRole(), is(Role.of("apply-3-ary-function-to-strings")));
        assertThat(apply3AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3));
    }

    @Test
    public void appliesFourAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");

        Node<TestMemory, String> apply4AryFunctionToStrings = functionNodesToTest("apply-4-ary-function-to-strings")
                .apply((string1, string2, string3, string4) -> "applied-" + string1 + '-' + string2 + '-' + string3
                        + '-' + string4, supplyString1, supplyString2, supplyString3, supplyString4);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply4AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4"));
        assertThat(apply4AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply4AryFunctionToStrings.getRole(), is(Role.of("apply-4-ary-function-to-strings")));
        assertThat(apply4AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4));
    }

    @Test
    public void appliesFiveAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");

        Node<TestMemory, String> apply5AryFunctionToStrings = functionNodesToTest("apply-5-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5) -> "applied-" + string1 + '-' + string2 + '-'
                        + string3 + '-' + string4 + '-' + string5, supplyString1, supplyString2, supplyString3,
                        supplyString4, supplyString5);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply5AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5"));
        assertThat(apply5AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply5AryFunctionToStrings.getRole(), is(Role.of("apply-5-ary-function-to-strings")));
        assertThat(apply5AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5));
    }

    @Test
    public void appliesSixAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = functionNodesToTest("supply-string-6").getValue("string-6");

        Node<TestMemory, String> apply6AryFunctionToStrings = functionNodesToTest("apply-6-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6) -> "applied-" + string1 + '-' + string2
                        + '-' + string3 + '-' + string4 + '-' + string5 + '-' + string6, supplyString1, supplyString2,
                        supplyString3, supplyString4, supplyString5, supplyString6);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply6AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6"));
        assertThat(apply6AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply6AryFunctionToStrings.getRole(), is(Role.of("apply-6-ary-function-to-strings")));
        assertThat(apply6AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6));
    }

    @Test
    public void appliesSevenAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = functionNodesToTest("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = functionNodesToTest("supply-string-7").getValue("string-7");

        Node<TestMemory, String> apply7AryFunctionToStrings = functionNodesToTest("apply-7-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7) -> "applied-" + string1 + '-'
                        + string2 + '-' + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-' + string7,
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply7AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7"));
        assertThat(apply7AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply7AryFunctionToStrings.getRole(), is(Role.of("apply-7-ary-function-to-strings")));
        assertThat(apply7AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6, supplyString7));
    }

    @Test
    public void appliesEightAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = functionNodesToTest("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = functionNodesToTest("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = functionNodesToTest("supply-string-8").getValue("string-8");

        Node<TestMemory, String> apply8AryFunctionToStrings = functionNodesToTest("apply-8-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7, string8) -> "applied-" + string1
                        + '-' + string2 + '-' + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-' + string7
                        + '-' + string8, supplyString1, supplyString2, supplyString3, supplyString4, supplyString5,
                        supplyString6, supplyString7, supplyString8);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply8AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8"));
        assertThat(apply8AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply8AryFunctionToStrings.getRole(), is(Role.of("apply-8-ary-function-to-strings")));
        assertThat(apply8AryFunctionToStrings.getDependencies(), containsDependencyNodes(supplyString1, supplyString2,
                supplyString3, supplyString4, supplyString5, supplyString6, supplyString7, supplyString8));
    }

    @Test
    public void appliesNineAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = functionNodesToTest("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = functionNodesToTest("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = functionNodesToTest("supply-string-8").getValue("string-8");
        Node<TestMemory, String> supplyString9 = functionNodesToTest("supply-string-9").getValue("string-9");

        Node<TestMemory, String> apply9AryFunctionToStrings = functionNodesToTest("apply-9-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7, string8, string9) -> "applied-"
                        + string1 + '-' + string2 + '-' + string3 + '-' + string4 + '-' + string5 + '-' + string6 + '-'
                        + string7 + '-' + string8 + '-' + string9, supplyString1, supplyString2, supplyString3,
                        supplyString4, supplyString5, supplyString6, supplyString7, supplyString8, supplyString9);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply9AryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8-string-9"));
        assertThat(apply9AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply9AryFunctionToStrings.getRole(), is(Role.of("apply-9-ary-function-to-strings")));
        assertThat(apply9AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5,
                        supplyString6, supplyString7, supplyString8, supplyString9));
    }

    @Test
    public void appliesTenAryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");
        Node<TestMemory, String> supplyString5 = functionNodesToTest("supply-string-5").getValue("string-5");
        Node<TestMemory, String> supplyString6 = functionNodesToTest("supply-string-6").getValue("string-6");
        Node<TestMemory, String> supplyString7 = functionNodesToTest("supply-string-7").getValue("string-7");
        Node<TestMemory, String> supplyString8 = functionNodesToTest("supply-string-8").getValue("string-8");
        Node<TestMemory, String> supplyString9 = functionNodesToTest("supply-string-9").getValue("string-9");
        Node<TestMemory, String> supplyString10 = functionNodesToTest("supply-string-10").getValue("string-10");

        Node<TestMemory, String> apply10AryFunctionToStrings = functionNodesToTest("apply-10-ary-function-to-strings")
                .apply((string1, string2, string3, string4, string5, string6, string7, string8, string9,
                        string10) -> "applied-" + string1 + '-' + string2 + '-' + string3 + '-' + string4 + '-'
                                + string5 + '-' + string6 + '-' + string7 + '-' + string8 + '-' + string9 + '-'
                                + string10,
                        supplyString1, supplyString2, supplyString3, supplyString4, supplyString5, supplyString6,
                        supplyString7, supplyString8, supplyString9, supplyString10);

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, apply10AryFunctionToStrings).join(), is(
                "applied-string-1-string-2-string-3-string-4-string-5-string-6-string-7-string-8-string-9-string-10"));
        assertThat(apply10AryFunctionToStrings.getType(), is(functionType()));
        assertThat(apply10AryFunctionToStrings.getRole(), is(Role.of("apply-10-ary-function-to-strings")));
        assertThat(apply10AryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4, supplyString5,
                        supplyString6, supplyString7, supplyString8, supplyString9, supplyString10));
    }

    @Test
    public void appliesNaryFunctionToOtherNodes() {
        Node<TestMemory, String> supplyString1 = functionNodesToTest("supply-string-1").getValue("string-1");
        Node<TestMemory, String> supplyString2 = functionNodesToTest("supply-string-2").getValue("string-2");
        Node<TestMemory, String> supplyString3 = functionNodesToTest("supply-string-3").getValue("string-3");
        Node<TestMemory, String> supplyString4 = functionNodesToTest("supply-string-4").getValue("string-4");

        Node<TestMemory, String> applyNaryFunctionToStrings = functionNodesToTest("apply-nary-function-to-strings")
                .apply(allStrings -> "applied-" + allStrings.stream().collect(Collectors.joining("-")),
                        List.of(supplyString1, supplyString2, supplyString3, supplyString4));

        assertThat(applyNaryFunctionToStrings.getRole(), is(Role.of("apply-nary-function-to-strings")));
        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, applyNaryFunctionToStrings).join(),
                is("applied-string-1-string-2-string-3-string-4"));
        assertThat(applyNaryFunctionToStrings.getDependencies(),
                containsDependencyNodes(supplyString1, supplyString2, supplyString3, supplyString4));
    }

    @Test
    public void nAryApplyAllowsDependenciesOfMixedTypes() {
        Node<TestMemory, String> supplyString = functionNodesToTest("supply-string").getValue("string");
        Node<TestMemory, Integer> supplyInteger = functionNodesToTest("supply-integer").getValue(212);

        Node<TestMemory, String> applyNaryFunctionToMixedTypes = functionNodesToTest(
                "apply-nary-function-to-mixed-types")
                        .apply(allStrings -> "applied-"
                                + allStrings.stream().map(Object::toString).collect(Collectors.joining("-")),
                                List.of(supplyString, supplyInteger));

        assertThat(TestData.callNodeInNewTestMemoryGraph(graphInput, applyNaryFunctionToMixedTypes).join(),
                is("applied-string-212"));
    }
}
