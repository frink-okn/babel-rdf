# babel-rdf

`babel-rdf` converts Babel compendium JSONL to streaming N-Triples. It reads one
JSON object at a time with Jackson and sends each triple directly to Jena's
streaming N-Triples writer, so memory use does not grow with the corpus.

Each compendium row is one identifier clique. The first `identifiers` item is
the clique leader:

```json
{"type":"biolink:Disease","identifiers":[{"i":"MONDO:0033486","l":"leukodystrophy"},{"i":"DOID:0080296","l":"hypomyelinating leukodystrophy 14"}]}
```

The converter writes one `skos:exactMatch` from every identifier to that leader,
including the leader's reflexive triple, one `rdfs:label` for every identifier
whose `l` value is a string, and one category assertion for the leader only:

```ntriples
<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .
<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2000/01/rdf-schema#label> "leukodystrophy" .
<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .
<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2000/01/rdf-schema#label> "hypomyelinating leukodystrophy 14" .
<http://purl.obolibrary.org/obo/MONDO_0033486> <https://w3id.org/biolink/vocab/category> <https://w3id.org/biolink/vocab/Disease> .
```

All fields other than `type`, `identifiers[*].i`, and `identifiers[*].l` are
ignored. A singleton clique with a label produces a reflexive exact-match
triple, a label, and a category triple. Empty, missing, or non-string `l` values
are skipped. Expanded identifiers are validated as absolute IRIs before any
triples for the row are written. An invalid leader drops the entire clique; an
invalid secondary identifier is skipped without changing the leader. Warnings
and final counts report all filtering. Unknown prefixes, invalid categories,
and malformed or empty cliques are fatal errors. Pass `--strict-invalid-iris`
to make any invalid identifier fatal instead of filtering it. DOI references
receive prefix-specific handling: unsafe resolver-path characters are
percent-encoded while existing `%HH` escapes are preserved. Raw or
percent-encoded whitespace and control characters still make a DOI invalid.

## Recommended workflow

The Make workflow defaults to the external-drive root
`/Volumes/Samsung_T5/Babel` and creates this layout:

```text
Babel/
  input/2025dec11-umls-level-0/compendia/*.txt.gz
  metadata/biolink-model-prefix-map.json
  output/2025dec11-umls-level-0/compendia-ntriples/*.nt.gz
```

The server's compendia are uncompressed and total roughly 145 GB. Downloads are
resumed into a temporary raw `*.download` file, then atomically compressed to
`.txt.gz`; the raw staging copy is removed after successful compression. Peak
space therefore includes the uncompressed form of each shard currently being
compressed. The largest, `Protein.txt`, is about 61.6 GB. Use a lower Make job
count if parallel staging causes unwanted disk or CPU pressure.

Build and test locally:

```bash
make test
make build
```

Download, compress, and convert one small shard first:

```bash
make convert FILE=Cell.txt
```

Then process all compendia:

```bash
make convert-all
```

Finished compressed inputs and outputs are atomic Make targets, so rerunning
Make skips completed shards. An interrupted network transfer resumes its raw
staging file; an interrupted compression restarts compression without another
download. Conversion is restartable at shard granularity. Override `GZIP` with
a compatible parallel compressor such as `pigz -p 4` if installed.

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
  Compendium.txt.gz
```

Input compression is detected from the gzip magic bytes. Output is compressed
when its name ends in `.gz`. Multiple inputs may be streamed into one output;
use `-` or omit inputs to read stdin, and use `--output -` for stdout.
File output is written to a temporary sibling and atomically installed only
after successful conversion, so a failed run preserves any prior output. The
CLI refuses an output path that aliases an input or prefix-map file. By default,
invalid secondary identifier IRIs are filtered and cliques with invalid leaders
are dropped; use `--strict-invalid-iris` when validation should fail fast.
