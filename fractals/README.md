# Fractals workload

Renders a [Julia set](https://en.wikipedia.org/wiki/Julia_set) (c = −0.4 + 0.6i) as a PNG image. Cost grows with `width × height × iterations`.

`FractalsHandler` exposes the same logic three ways:

| Entry point | Interface |
|-------------|-----------|
| HTTP (`/fractals`) | `com.sun.net.httpserver.HttpHandler` |
| AWS Lambda | `RequestHandler<Map<String,String>, String>` |
| CLI | `main` |

## Run from the CLI

```bash
mvn clean package
java -cp target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.naturecloud.fractals.FractalsHandler <width> <height> <iterations> <output.png>
```

HTTP parameters are documented in the [root README](../README.md#workloads--api).
