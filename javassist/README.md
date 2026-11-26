# Instrumentation agent & metrics storage

A Java agent built on [Javassist](https://www.javassist.org/) that rewrites workload bytecode at class-load time to measure what each request actually costs, plus the DynamoDB layer that stores those measurements.

## Usage

```
java -cp <app classpath> \
     -javaagent:<agent.jar>=<ToolName>:<comma-separated packages>:<output dir> \
     <main class>
```

`JavassistAgent` loads `com.naturecloud.javassist.tools.<ToolName>` and registers it as a `ClassFileTransformer` for every class in the listed packages. Transformed class files are also written to `<output dir>`.

## Contents

| Class | Purpose |
|-------|---------|
| `JavassistAgent` | Agent entry point (`premain`); parses arguments and installs the transformer. |
| `tools.AbstractJavassistTool` | Base transformer: splits each method into basic blocks (with their memory-access count) and exposes hooks for subclasses. |
| `tools.ICount` | Counts executed instructions and memory accesses per basic block and tracks per-thread allocated bytes for each request, starting and stopping the measurement around each workload's `handleWorkload`. |
| `tools.Metrics` | Per-request metrics record and the mapping from request parameters to a *region key* (a coarse bucket of the parameter space). |
| `Parameters` | Request parameters for all three workloads. |
| `MetricsRange` | Running averages for one (workload, region) pair. |
| `mss.MSS` | Metrics Storage System: creates the DynamoDB tables, stores each request and updates its region's averages. |

The load balancer depends on this module to compute region keys and read region statistics, so predictions and measurements share the same model.
