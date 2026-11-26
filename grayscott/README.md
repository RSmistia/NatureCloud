# Gray–Scott workload

Simulates the Gray–Scott reaction-diffusion model on a square grid and renders the final state as a PNG. Cost grows with `size² × iterations`, and can drop sharply when `stopOnExtinction` ends the run early.

`GrayScottHandler` exposes the same logic as an HTTP handler (`/grayscott`), an AWS Lambda handler and a CLI entry point.

## Run from the CLI

```bash
mvn clean package
java -cp target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.naturecloud.grayscott.GrayScottHandler <size> <maxIterations> <feedF> <killK> \
     <stopOnExtinction:true|false> <seedMode:center|ring|stripe> [output.png]
```

HTTP parameters are documented in the [root README](../README.md#workloads--api).
