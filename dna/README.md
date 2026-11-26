# DNA workload

Finds shared substrings of at least `minLength` bases between two DNA sequences and returns an HTML report highlighting the matches. Cost grows with the number of candidate pairs, roughly `(len₁ − minLength + 1) × (len₂ − minLength + 1)`.

Sequences are passed as `[name:]<FASTA content>`. Sample FASTA files for the HBB gene (human, chimpanzee, Norway rat) are in [`src/main/resources`](src/main/resources).

`DnaHandler` exposes the same logic as an HTTP handler (`/dna`), an AWS Lambda handler and a CLI entry point.

## Run from the CLI

```bash
mvn clean package
java -cp target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.naturecloud.dna.DnaHandler [name1:]<fasta1> [name2:]<fasta2> [minLength] [stopOnFirst]
```

The result is written to `dna-match-result.html`. HTTP parameters are documented in the [root README](../README.md#workloads--api).
