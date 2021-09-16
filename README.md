# Aggra

Aggra is a java library that tries to simplify parallel programming in already-complicated situations. Aggra models programs via Aggregating Graphs (this is where the name "Aggra" comes from). "Aggregating" comes from how Aggra specializes in aggregating information from various data sources into a single model. "Graphs" comes from how Aggra builds programs from connected nodes, where each Node in the graph represents a function that produces a result, and input to the function comes from declaring dependencies on other nodes.

### Features

* Optimal -- Aggra is designed to optimize in a few different ways
    * Latency -- executes nodes as soon as possible. Think optimal scheduling.
    * Usage -- executes nodes only when a consumer says it needs them. Think conditional execution. Nodes are called in a pull-based manner from the top down: a node is only called if consumers nodes/clients call them. This allows pruning decisions to be localized compared to a push-based strategy from the bottom up.
    * Threads -- built upon CompletableFutures, a push-based Future that prioritizes freeing Threads from waiting for results.
* Asynchronous/concurrent -- Aggra is designed to execute nodes concurrently as much as possible.
* Dependency graph -- Aggra's graphs are data dependency graphs: graphs are directed acyclic graphs where each node represents a value-returning function and "points" to other dependency nodes whose data it needs to execute its inherent functionality.
* Static -- Aggra builds graphs that are intended to work across multiple requests, potentially up to the lifetime of the program. If, instead, nodes were created dynamically for every request, it would be near impossible to figure out, in a generic way, when nodes should be the same and different. In addition, being static allows clients to query, display, and reason about their graph structure outside of any specific request.
* Easy integration -- each graph can have multiple, independent nodes called externally, allowing integration with libraries like GraphQL. In addition, because graphs are static, clients can use dependency injection frameworks to create/manage its structure.
* Convenient -- Aggra defines a set of common node types that should cover a wide variety of use cases. These node types help free clients from having to program efficient parallel logic using CompletableFutures.
* Strongly typed -- nodes are generically types based on both the memory they're a part of and the type of their response. The former helps ensure that nodes have access to the input needed to do their jobs, despite never explicitly accepting that input themselves. The latter helps guide program creators/refactorers, making sure that nodes depend on the right types of dependencies.
* Safe memoization -- for a single graph call, the response from each node is memoized relative to the memory used to execute it. This helps keep decisions local about when to execute a given node. In addition, because each node receives its inputs from dependencies rather than consumers, it represents a zero-argument function. This helps avoid pitfalls with the memoization process, specifically the risk that different consumers accidentally pass slightly different input, resulting in multiple executions.
* Request-response -- Aggra focuses on request-response workflows: Aggra does not focus on streaming workflows.
* Modular -- each node contains the complete logic about how to perform its inherent function as well as when/if to call its dependencies.
* Observable -- the first call to a node, every call to a node (including those cached), the inherent functionality associated with a node, and custom cancel actions all support observation. This allows clients to measure latency, find inefficiencies, display request structure, etc.
* Exceptionally informative -- to help with debugging, as an exception propagates from dependencies through consumers, Aggra keeps track of the path followed. The result is, instead of a stack trace, more of a graph trace. This helps somewhat offset a traditional sorespot with push-based frameworks (e.g. raw CompletableFutures).
* Low request-scope barrier -- Aggra has a low barrier to adding new request-scoped data. In Aggra, data flows from dependencies to where it's needed, and nodes can choose which dependencies they care about. Compare that to traditional programs, where data flows from and must pass through consumers to where it's needed. By lowering the distance between where data is produced to where it's needed, this makes it easier to add and use new request-scoped data. 
* Cancellable -- graph calls are cancellable at the individual node and graph levels. Responses to cancellation range from automatic cancellation, cancel signals, custom actions, and interruption support.
* Structured concurrency -- Aggra follows the principle of structured concurrency. By default, all of a consumer's dependencies must complete before the consumer itself can. There's an option to push dependency completion to the level of the graph call instead of the consumer, but nothing further than that. Of course, because implementors can create threads on their own, and because Aggra delegates multithreaded code to existing utilities (e.g. ThreadPoolExecutorService), the extent to which Aggra can follow structured concurrency is limited by implementor behavior. As far as Aggra itself goes, though, Aggra complies with structured concurrency.
* Easy to maintain -- Aggra is a minimal framework built on top of CompletableFutures. Most of the heavy lifting is done by that class. That makes the framework itself easier to maintain.

### Non-features

* High barrier to entry -- Aggra is a different programming paradigm compared to the standard way that programs are written. It's much easier to write and reason about a simple, traditional program rather than translate it into a graph form. You should only use Aggra if you need the high-powered features it offers (usually optimality): if you can avoid Aggra, you should! Another strategy is to minimize the number of nodes that you use, since each node individually represents a traditional mini program.
* Memoization across nodes -- Aggra applies memoization at the individual node level. It makes no attempt to memoize results *across* nodes. If you know that two nodes produce the same result, try to model them together as a single node. If that's not possible, you'll have to find some other way to memoize across nodes. One easy way to do that would be to create a request-scope map and pass it around the graph. Just remember to take precautions against how brittle memoization can be: minimize the number of arguments, minimize the distance between where the data to construct the arguments is produced and where it's used, and use as much common code to construct the arguments as possible (between the many places it may be used).
* Thread context propagation -- thread-local variables are a useful way to avoid passing data through consumers to where it's used. Although Aggra makes it easier to avoid this pattern, doing so may not always be possible. When using multithreaded code along with thread locals, it becomes necessary to propagate this thread state from one thread to another. Aggra provides no direct support for this. For one possible provider, see the [thread-state-propagation-runnable-adorner](https://github.com/graydavid/thread-state-propagation-runnable-adorner) library.
* Recursion -- nodes cannot call themselves: graphs are without cycles.

## Adding this project to your build

This project follows [semantic versioning](https://semver.org/). It's currently available via the Maven snapshot repository only as version 0.0.1-SNAPSHOT. The "0.0.1" part is because it's still in development, waiting for feedback or sufficient usage until its first official release. The "SNAPSHOT" part is because no one has requested a stable, non-SNAPSHOT development version. If you need a non-SNAPSHOT development version, feel free to reach out, and I can build this project into the Maven central repository.

## Usage

This project requires JDK version 11 or higher.

You can find the main user's guide in [the Aggra wiki](https://graydavid.github.io/aggra-guide). Below is just a simple motivating example.

Suppose you're implementing a request-response service operation. It has a top-level request object. It always calls Service1 and then Service2. Meanwhile, the service operation also always calls ServiceA and then ServiceB, which also needs the response from Service1. Lastly, it combines the responses from Service2 and ServiceB into a top-level response. 

Here's what that looks like in data dependency graph form:

![motivating](https://github.com/graydavid/aggra-guide/blob/gh-pages/motivation/motivating.png)

Here's what that looks like as java code:

```java
// Define skeletons for the main classes in the example
class TopLevelRequest {
}
class Service1 {
    static ServiceResponse1 callService(TopLevelRequest request) { ... }
}
class Service2 {
    static ServiceResponse2 callService(ServiceResponse1 response1) { ... }
}
class ServiceA {
    static ServiceResponseA callService(TopLevelRequest request) { ... }
}
class ServiceB {
    static ServiceResponseB callService(ServiceResponseA responseA, ServiceResponse1 response1) { ... }
}
class GetTopLevelResponse {
    static TopLevelResponse getResponse(ServiceResponse2 response2, ServiceResponseB responseB) { ... }
}

// Every Graph needs a Memory subclass
class ServiceOperationMemory extends Memory<TopLevelRequest>{ 
    public ServiceOperationMemory(MemoryScope scope, CompletionStage<TopLevelRequest> input){
        ...
    }
}

// Create the Graph and nodes (static is used for this example; Spring/Guice are just as valid)
static Node<ServiceOperationMemory, TopLevelResponse> getTopLevelResponse;
static GraphCall.Factory<TopLevelRequest, ServiceOperationMemory> graphCallFactory;
static {
    // Create an easy way to create asynchronous FunctionNodes
    Executor asynchronousExecutor = ...; // (e.g. Executors.newCachedThreadPool())
    CreationTimeExecutorAsynchronousStarter asychronousStarter = CreationTimeExecutorAsynchronousStarter
            .from(asynchronousExecutor);

    // Create the nodes in the graph
    Node<ServiceOperationMemory, TopLevelRequest> getTopLevelRequest = Node
            .inputBuilder(ServiceOperationMemory.class)
            .role(Role.of("GetTopLevelRequest"))
            .build();
    Node<ServiceOperationMemory, ServiceResponse1> callService1 = asychronousStarter
            .startNode(Role.of("CallService1"), ServiceOperationMemory.class)
            .apply(Service1::callService, getTopLevelRequest);
    Node<ServiceOperationMemory, ServiceResponse2> callService2 = asychronousStarter
            .startNode(Role.of("CallService2"), ServiceOperationMemory.class)
            .apply(Service2::callService, callService1);
    Node<ServiceOperationMemory, ServiceResponseA> callServiceA = asychronousStarter
            .startNode(Role.of("CallServiceA"), ServiceOperationMemory.class)
            .apply(ServiceA::callService, getTopLevelRequest);
    Node<ServiceOperationMemory, ServiceResponseB> callServiceB = asychronousStarter
            .startNode(Role.of("CallServiceB"), ServiceOperationMemory.class)
            .apply(ServiceB::callService, callServiceA, callService1);
    getTopLevelResponse = FunctionNodes
            .synchronous(Role.of("GetTopLevelResponse"), ServiceOperationMemory.class)
            .apply(GetTopLevelResponse::getResponse, callService2, callServiceB);
    
    // Create the Graph and a convenient GraphCall factory
    Graph<ServiceOperationMemory> graph = Graph
            .fromRoots(Role.of("ServiceOperationGraph"), Set.of(getTopLevelResponse));
    graphCallFactory = GraphCall.Factory.from(graph, ServiceOperationMemory::new);
}

// Per-request calling of nodes
TopLevelResponse getResponse(TopLevelRequest request) {
    GraphCall<ServiceOperationMemory> graphCall = graphCallFactory
            .openCancellableCall(CompletableFuture.completedFuture(request), Observer.doNothing());
    Reply<TopLevelResponse> response = graphCall.call(getTopLevelResponse);
    CompletableFuture<GraphCall.FinalState> finalCallState = graphCall.weaklyClose();
    finalCallState.join(); 
    // Do any logging of ignoredReplies or unhandledExceptions in the finalCallState
    return response.join();
}
```

## Contributionss

Contributions are welcome! See the [graydavid-parent](https://github.com/graydavid/graydavid-parent) project for details.