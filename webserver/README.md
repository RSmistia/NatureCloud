# Web server

Worker HTTP server built on the JDK's `com.sun.net.httpserver`. It listens on port **8000** and exposes:

| Path | Handler |
|------|---------|
| `/fractals` | `FractalsHandler` |
| `/grayscott` | `GrayScottHandler` |
| `/dna` | `DnaHandler` |
| `/test` | Liveness check (echoes the query string) |

On startup it makes sure the DynamoDB metrics tables exist, so it needs AWS credentials.

## Run

```bash
java -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.naturecloud.webserver.WebServer
```

To run it with the instrumentation agent (as workers do), see [SETUP.md](../SETUP.md#32-worker-web-server).
