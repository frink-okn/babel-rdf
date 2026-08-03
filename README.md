# babel-rdf

`babel-rdf` converts Babel KGX node JSONL to streaming N-Triples. It reads one
JSON object at a time with Jackson and sends each triple directly to Jena's
streaming N-Triples writer, so memory use does not grow with the corpus.

For a node such as:

```json
{"id":"MONDO:0033486","name":"leukodystrophy, hypomyelinating, 14","category":"biolink:Disease","equivalent_identifiers":["MONDO:0033486","DOID:0080296"]}
```

the converter writes:

```ntriples
<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .
<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .
<http://purl.obolibrary.org/obo/MONDO_0033486> <https://w3id.org/biolink/vocab/category> <https://w3id.org/biolink/vocab/Disease> .
```

The `name` field and all fields other than `id`, `category`, and
`equivalent_identifiers` are ignored. Every equivalent identifier is emitted,
including a self-match when the main ID occurs in the array. `category` may be
a string or an array. Unknown prefixes and edge records are fatal errors rather
than being silently converted incorrectly.

## Recommended workflow

The Make workflow defaults to the external-drive root
`/Volumes/Samsung_T5/Babel` and creates this layout:

```text
Babel/
  input/2025dec11-umls-level-0/kgx/*.jsonl.gz
  metadata/biolink-model-prefix-map.json
  output/2025dec11-umls-level-0/ntriples/*.nt.gz
```

Build and test locally:

```bash
make test
make build
```

Download and convert one shard first:

```bash
make convert FILE=AnatomicalEntity_nodes.jsonl.gz
```

Then process all node shards:

```bash
make convert-all
```

Downloads go to `*.part` and use `curl --continue-at -`; rerunning Make resumes
an interrupted download. Finished inputs and outputs are atomically renamed, so
Make skips completed shards. Conversion is restartable at shard granularity.
The source JSONL remains gzip-compressed and each output shard is `.nt.gz`.

Change the storage root without editing the Makefile:

```bash
make convert-all BABEL_ROOT=/some/other/path
```

The prefix map is pinned to Biolink Model commit
`bec6cc5b30519c65d9d35d76cc31e631390628ea`, verified with SHA-256, and retained
under `metadata`. If `PREFIX_MAP_URL` is overridden, `PREFIX_MAP_SHA256` must be
overridden with its expected checksum too. Additional prefix maps can be
overlaid by invoking the CLI with repeated `--prefix-map` options.

## Direct CLI use

Scala CLI can run the source directly:

```bash
scala-cli run . --server=false -- \
  --prefix-map /path/to/biolink-model-prefix-map.json \
  --output output.nt.gz \
  input_nodes.jsonl.gz
```

Input compression is detected from the gzip magic bytes. Output is compressed
when its name ends in `.gz`. Multiple inputs may be streamed into one output;
use `-` or omit inputs to read stdin, and use `--output -` for stdout.
File output is written to a temporary sibling and atomically installed only
after successful conversion, so a failed run preserves any prior output. The
CLI refuses an output path that aliases an input or prefix-map file.
