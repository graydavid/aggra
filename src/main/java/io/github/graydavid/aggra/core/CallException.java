/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

/**
 * An exception thrown when a node call fails. This class provides the ability to add contextual information as it's
 * propagated up a "node call stack", like what nodes were called (and by whom).
 * 
 * Warning: {@link Throwable#Throwable(Throwable)}, as a part of calculating its own non-existent message, will call
 * {@link #getMessage()} on its cause and use that instead. Since CallException's {@link #getMessage()} can be so
 * costly, since the initial message probably doesn't contain the full node call stack, and since this stack can be so
 * busy (visually); it's recommended that you use a different constructor for a Throwable (or Throwable-derived) when
 * CallException is the cause.
 */
public class CallException extends RuntimeException {
    private static final long serialVersionUID = 1;

    private static final String PRINTED_NO_NODE_CALL_STACK_TRACE = "";

    private final Caller caller;
    private final Node<?, ?> failedNode;
    private final Collection<NodeCallStackTraceElement> nodeCallStackTraceElements;
    private volatile String message = null;

    public CallException(Caller caller, Node<?, ?> failedNode, Throwable cause) {
        super("Error calling node: " + failedNode.getSynopsis(), cause);
        this.caller = Objects.requireNonNull(caller);
        this.failedNode = Objects.requireNonNull(failedNode);
        // ConcurrentLinkedQueue to try to maintain as much order of added node calls in output
        this.nodeCallStackTraceElements = new ConcurrentLinkedQueue<>();
    }


    /** Returns the caller of the failed node's call method. */
    public Caller getCaller() {
        return caller;
    }

    /** Returns the node whose call method failed. */
    public Node<?, ?> getFailedNode() {
        return failedNode;
    }

    /**
     * {@inheritDoc}
     * 
     * In addition to RuntimeException's implementation, this method also includes the "node call stack trace" in the
     * response. "node call stack trace" is similar to {@link Throwable#printStackTrace()} except instead of a typical
     * stack trace, it's a "stack trace" of all the recorded node calls added through
     * {@link #addNodeCallStackTraceElement}. These stack trace elements are added to a CallException as the exception
     * makes its way from the node that throws it through its consuming nodes. So, rather than the typical lines of code
     * present in traditional stack traces, you end up with (potentially graph-like) stacks of node calls. This type of
     * idea is useful, because traditional stack traces don't contain a lot of useful calling contextual information on
     * their own.
     *
     * Due to the concurrent nature of the Aggra framework, this method only guarantees that you get the node call stack
     * trace as it was at some point in the CallException's lifetime. In order to get the most current trace, make sure
     * to establish a happens-before relationship with any code that could have called
     * {@link #addNodeCallStackTraceElement(Caller, Node)} on this Exception. From a Node client's perspective, this
     * means making sure that all consuming Nodes of the Node that throws the CallException, consumers of consumers,
     * consumers of consumers of consumers, etc. are all complete before calling this method.
     * 
     * @apiNote the node call stack trace is definitely a lot of verbose information to include in an exception's
     *          message. I could have exposed this same information in a separate information instead and forced
     *          client's to call that if they wanted. However, I feel like this trace information is crucial, and
     *          forcing clients to remember to call a separate method would be too brittle.
     * @implNote this method uses single-lock initialization for memoization. Memoization is used at all, because I
     *           could imagine developers calling the expensive-to-compute getMessage multiple times for an exception,
     *           either intentionally or unintentionally (which is a common mistake with logging frameworks). Meanwhile,
     *           calculating the node call stack trace can be expensive, with average time complexity proportional to
     *           the number of node call stack trace elements and the worst case proportional to its square. At the same
     *           time, I think it's highly unlikely that getMessage would be called from multiple threads
     *           simultaneously. So, I think it's acceptable to live with single-lock initialization rather than
     *           double-lock initialization.
     */
    @Override
    public String getMessage() {
        if (message == null) {
            message = calculateMessage();
        }
        return message;
    }

    private String calculateMessage() {
        StringBuilder message = new StringBuilder();
        message.append(super.getMessage());
        message.append("\n****Start node call stack****\n");
        message.append(getPrintedNodeCallStackTrace());
        message.append("****Stop node call stack****");
        return message.toString();
    }

    /**
     * A package private method to allow {@link Call} to add "node call stack trace elements" to the "node call stack
     * trace" as an exception propagates up a graph. This method powers {@link #getPrintedNodeCallStackTrace()}.
     */
    void addNodeCallStackTraceElement(Caller caller, Node<?, ?> callee) {
        nodeCallStackTraceElements.add(new NodeCallStackTraceElement(caller, callee));
        // There's a (acceptable) race condition here where a trace element could be added before the message is nulled
        // out, meaning that element wouldn't end up in the reponse from getMessage(). However, getMessage()'s javadoc
        // allows for the eventual consistency that would result once the following line of code was run.
        message = null;
    }

    /**
     * A helper class to record the caller and callee in a single "node call stack trace element" in a "node call stack
     * trace".
     */
    private static class NodeCallStackTraceElement {
        private final Caller caller;
        private final Node<?, ?> callee;

        NodeCallStackTraceElement(Caller caller, Node<?, ?> callee) {
            this.caller = caller;
            this.callee = callee;
        }
    }

    String getPrintedNodeCallStackTrace() {
        StackCallGraph stackCallGraph = new StackCallGraph();
        nodeCallStackTraceElements.forEach(stackCallGraph::addCall);
        CallGraphNode rootCallGraphNode = stackCallGraph.getCallGraphNode(failedNode);
        if (rootCallGraphNode == null) {
            return PRINTED_NO_NODE_CALL_STACK_TRACE;
        }
        PrintingCallGraphVisitor visitor = new PrintingCallGraphVisitor();
        visitor.visit(rootCallGraphNode, 1);
        return visitor.toString();
    }

    /**
     * A directed graph where of Callers, where edges represent one callee being called by another (hence the "Stack").
     */
    private static class StackCallGraph {
        private final Map<Caller, CallGraphNode> nodeToCallGraphNode = new HashMap<>();

        private void addCall(NodeCallStackTraceElement element) {
            CallGraphNode callerCallGraphNode = nodeToCallGraphNode.computeIfAbsent(element.caller, CallGraphNode::new);
            CallGraphNode calleeCallGraphNode = nodeToCallGraphNode.computeIfAbsent(element.callee, CallGraphNode::new);
            calleeCallGraphNode.addEdge(callerCallGraphNode);
        }

        public CallGraphNode getCallGraphNode(Caller caller) {
            return nodeToCallGraphNode.get(caller);
        }
    }

    /** A node in an call graph, representing a caller and children nodes representing edges in the graph. */
    private static class CallGraphNode {
        private final Caller subject;
        private final Map<CallGraphNode, Count> edges;

        CallGraphNode(Caller subject) {
            this.subject = subject;
            // LinkedHashMap to try to maintain order of added node calls in output
            this.edges = new LinkedHashMap<>();
        }

        public void addEdge(CallGraphNode callGraphNode) {
            edges.computeIfAbsent(callGraphNode, node -> new Count()).increment();
        }
    }

    /** A mutable integer class. */
    private static class Count {
        private int count = 0;

        private void increment() {
            count += 1;
        }
    }

    /**
     * Visits an call graph starting at a node and moving along edges until all connects nodes are visited, printing the
     * path along the way.
     */
    private static class PrintingCallGraphVisitor {
        private final Set<CallGraphNode> visitedNodes = new HashSet<>();
        private final StringBuilder output = new StringBuilder();
        private int depth = 0;

        public void visit(CallGraphNode callGraphNode, int count) {
            IntStream.range(0, depth).forEach(i -> output.append('\t'));
            output.append(callGraphNode.subject.getSynopsis());
            if (count > 1) {
                output.append(" -- x " + count);
            }
            boolean nodeAlreadyVisited = !visitedNodes.add(callGraphNode);
            if (nodeAlreadyVisited) {
                output.append(" -- already printed; see above");
            }
            output.append('\n');
            if (!nodeAlreadyVisited) {
                depth += 1;
                callGraphNode.edges.entrySet().stream().forEach(entry -> visit(entry.getKey(), entry.getValue().count));
                depth -= 1;
            }
        }

        @Override
        public String toString() {
            return output.toString();
        }
    }
}
