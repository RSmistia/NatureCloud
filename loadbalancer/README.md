# Load balancer & auto-scaler

A single Java process (`com.naturecloud.loadbalancer.Main`) that fronts the worker pool on port **8080**. It:

- predicts each request's instructions and memory from fitted models, blended with observed region averages from DynamoDB;
- schedules requests in *spreading* or *packing* mode with admission control on load, memory and concurrency;
- offloads cheap requests to AWS Lambda when no worker has capacity, and retries on another worker if a forward fails;
- runs an auto-scaler thread that launches, drains and terminates EC2 workers based on CloudWatch CPU and predicted load.

All tuning constants (thresholds, cooldowns, capacities, Lambda limits) are defined at the top of `Main.java` and listed in [SETUP.md](../SETUP.md#load-balancer-tuning). How the pieces fit together is described in the [root README](../README.md#how-it-works).

## Run

Requires AWS credentials with EC2, CloudWatch, Lambda and DynamoDB access, and a worker AMI named `NatureCloud-Worker-Image` in `us-east-1`. Set `KEY_NAME` and `SECURITY_GROUP` in `Main.java` to your own values first (see [SETUP.md](../SETUP.md#42-set-your-own-values)).

```bash
java -cp target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.naturecloud.loadbalancer.Main
```
