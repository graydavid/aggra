package io.github.graydavid.aggra.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.graydavid.aggra.core.TestData.TestMemory;

public class CallExceptionTest {
    private final Caller caller = () -> Role.of("top-level-caller");
    private final Node<TestMemory, ?> failedNode = Node.communalBuilder(TestMemory.class)
            .type(Type.generic("test"))
            .role(Role.of("failedNode"))
            .build(device -> CompletableFuture.completedFuture(null));

    private Node<TestMemory, ?> createArbitraryNodeWithRole(String role) {
        return Node.communalBuilder(TestMemory.class)
                .type(Type.generic("test"))
                .role(Role.of(role))
                .build(device -> CompletableFuture.completedFuture(null));
    }

    @Test
    public void callerNodeCauseConstructorThrowsExceptionGivenNullCaller() {
        assertThrows(NullPointerException.class, () -> new CallException(null, failedNode, new Throwable()));
    }

    @Test
    public void callerNodeCauseConstructorThrowsExceptionGivenNullFailedNode() {
        assertThrows(NullPointerException.class, () -> new CallException(caller, null, new Throwable()));
    }

    @Test
    public void callerNodeCauseConstructorSetsProperties() {
        CallException exception = new CallException(caller, failedNode, new Throwable());

        assertThat(exception.getCaller(), is(caller));
        assertThat(exception.getFailedNode(), is(failedNode));
        assertThat(exception.getSuppressed(), emptyArray());
    }


    @Test
    public void getPrintedNodeCallStackReturnsEmptyStringWhenNoNodeCallStackTraceElementsAdded() {
        CallException exception = new CallException(caller, failedNode, new Throwable());

        assertThat(exception.getPrintedNodeCallStackTrace(), is(""));
    }

    @Test
    public void getPrintedNodeCallStackIndentsCallStackForOneNodeCallStackTraceElementAdded() {
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(caller, failedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller.getSynopsis() + "\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackIndentsCallersOfCallersForEachLevel() {
        Node<TestMemory, ?> callsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        Node<TestMemory, ?> callsCallerOfFailedNode = createArbitraryNodeWithRole("callsCallerOfFailedNode");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(callsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(callsCallerOfFailedNode, callsFailedNode);
        exception.addNodeCallStackTraceElement(caller, callsCallerOfFailedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + callsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + callsCallerOfFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t\t" + caller.getSynopsis() + "\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackIndentsCallersOfSameNodeAtSameLevel() {
        Caller caller2 = () -> Role.of("caller2");
        Node<TestMemory, ?> callsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        Node<TestMemory, ?> alsoCallsFailedNode = createArbitraryNodeWithRole("alsoCallsFailedNode");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(callsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller, callsFailedNode);
        exception.addNodeCallStackTraceElement(alsoCallsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller2, alsoCallsFailedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + callsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller.getSynopsis() + "\n");
        expectedOutput.append("\t" + alsoCallsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller2.getSynopsis() + "\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackTraceTriesToMaintainOrderOfAddedNodeCallStackTraceElements() {
        Caller caller2 = () -> Role.of("caller2");
        Caller caller3 = () -> Role.of("caller3");
        Caller caller4 = () -> Role.of("caller4");
        Caller caller5 = () -> Role.of("caller5");
        Caller caller6 = () -> Role.of("caller6");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(caller, failedNode);
        exception.addNodeCallStackTraceElement(caller2, failedNode);
        exception.addNodeCallStackTraceElement(caller3, failedNode);
        exception.addNodeCallStackTraceElement(caller4, failedNode);
        exception.addNodeCallStackTraceElement(caller5, failedNode);
        exception.addNodeCallStackTraceElement(caller6, failedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller2.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller3.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller4.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller5.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller6.getSynopsis() + "\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackTracePrintsCountWhenSameCallHappensMultipleTimes() {
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(caller, failedNode);
        exception.addNodeCallStackTraceElement(caller, failedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller.getSynopsis() + " -- x 2\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackTracePrintsAlreadyPrintedMessageWhenItFindsSameCallerAgain() {
        Node<TestMemory, ?> callsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        Node<TestMemory, ?> alsoCallsFailedNode = createArbitraryNodeWithRole("alsoCallsFailedNode");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(callsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller, callsFailedNode);
        exception.addNodeCallStackTraceElement(alsoCallsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller, alsoCallsFailedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + callsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller.getSynopsis() + "\n");
        expectedOutput.append("\t" + alsoCallsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller.getSynopsis() + " -- already printed; see above\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackTracePrintsAlreadyPrintedMessageForLoop() {
        Node<TestMemory, ?> callsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(callsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(failedNode, callsFailedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + callsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + failedNode.getSynopsis() + " -- already printed; see above\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getPrintedNodeCallStackTracePrintsCountAndAlreadyPrintedMessageWhenSameCallHappensMultipleTimesAndItFindsSameCallerAgain() {
        Node<TestMemory, ?> callsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        Node<TestMemory, ?> alsoCallsFailedNode = createArbitraryNodeWithRole("alsoCallsFailedNode");
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(callsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller, callsFailedNode);
        exception.addNodeCallStackTraceElement(alsoCallsFailedNode, failedNode);
        exception.addNodeCallStackTraceElement(caller, alsoCallsFailedNode);
        exception.addNodeCallStackTraceElement(caller, alsoCallsFailedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + callsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller.getSynopsis() + "\n");
        expectedOutput.append("\t" + alsoCallsFailedNode.getSynopsis() + "\n");
        expectedOutput.append("\t\t" + caller.getSynopsis() + " -- x 2 -- already printed; see above\n");

        assertThat(exception.getPrintedNodeCallStackTrace(), is(expectedOutput.toString()));
    }

    @Test
    public void getMessageIncludesPrintedNodeCallStack() {
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(caller, failedNode);
        StringBuilder expectedOutput = new StringBuilder();
        expectedOutput.append("Error calling node: " + failedNode.getSynopsis() + "\n");
        expectedOutput.append("****Start node call stack****\n");
        expectedOutput.append(failedNode.getSynopsis() + "\n");
        expectedOutput.append("\t" + caller.getSynopsis() + "\n");
        expectedOutput.append("****Stop node call stack****");

        assertThat(exception.getMessage(), is(expectedOutput.toString()));
    }

    @Test
    public void getMessageUpdatesItsMessageWhenTraceElementsAdded() {
        CallException exception = new CallException(caller, failedNode, new Throwable());
        exception.addNodeCallStackTraceElement(caller, failedNode);

        String firstOutput = exception.getMessage();
        String secondOutput = exception.getMessage();
        Node<TestMemory, ?> alsoCallsFailedNode = createArbitraryNodeWithRole("callsFailedNode");
        exception.addNodeCallStackTraceElement(alsoCallsFailedNode, failedNode);
        String thirdOutput = exception.getMessage();

        assertThat(secondOutput, is(firstOutput));
        assertThat(thirdOutput, not(firstOutput));
    }
}
