# Results, models and pseudocode

Data and models behind the load balancer's cost estimates and capacity constants. Spreadsheets (`.xlsx`) contain the charts and fits; CSV files contain the raw measurements.

| Path | Contents |
|------|----------|
| [`fractals/`](fractals), [`grayscott/`](grayscott), [`dna/`](dna) | `results/`: instrumented runs (instructions, memory accesses, allocated memory) across the parameter space. `heuristics/`: the fitted estimation functions (`*-heuristic.txt`) and the spreadsheets used to derive them. |
| [`memory_used/`](memory_used) | Mapping from *allocated* bytes (what the agent measures) to *memory actually used* on a worker, including the polynomial regression script for DNA. |
| [`calibration/`](calibration) | Single-worker calibration and saturation runs used to derive each workload's capacity and the cost-to-load conversion (`workload-cost-and-capacity.txt`). |
| [`worker-stress/`](worker-stress) | Client latency and server metrics under concurrent load, with per-workload summaries. |
| [`load-balancer-pseudo.txt`](load-balancer-pseudo.txt) | Pseudocode of the scheduling, Lambda offloading and auto-scaling logic. |
