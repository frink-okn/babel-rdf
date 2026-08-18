# Repository Guidelines

## Project Structure & Module Organization

Production code lives in `src/main/scala/org/renci/babelrdf/`. `Main.scala` defines the CLI, `CompendiumConverter.scala` streams JSONL records to N-Triples, and `Io.scala` and `PrefixExpander.scala` handle files and CURIE expansion. Tests mirror the package under `src/test/scala/org/renci/babelrdf/`. Scala CLI dependencies and toolchain versions are declared in `project.scala`. The `Makefile`, `scripts/`, and `compendia-files.txt` drive large-data downloads and conversions; generated artifacts belong under `target/` or the configured `BABEL_ROOT`, not in source control.

## Build, Test, and Development Commands

- `make test` runs all MUnit suites with Scala CLI.
- `make build` creates the assembly JAR at `target/babel-rdf.jar`.
- `scala-cli run . --server=false -- --prefix-map map.json input.txt` runs the CLI directly and writes N-Triples to stdout.
- `make convert FILE=Cell.txt BABEL_ROOT=/path/to/Babel` downloads, compresses, and converts one shard. Prefer a small shard before `make convert-all`.
- `make clean-build` removes Scala CLI and build output only.

Use JDK 21 and the Scala 3.7.4 version pinned in `project.scala`.

## Coding Style & Naming Conventions

Follow the existing Scala 3 significant-indentation style: two-space block indentation, trailing commas in multiline argument lists, and braces only where they improve clarity. Use `UpperCamelCase` for classes, objects, and case classes; `lowerCamelCase` for methods and values; and descriptive exception messages that include the input name and record number. Keep conversion streaming—do not collect a compendium in memory. There is no configured formatter, so match nearby code and keep imports grouped by library and Java/Scala standard library.

## Testing Guidelines

Tests use MUnit `FunSuite`. Name files `<Subject>Suite.scala` and write behavior-focused test names such as `test("preserves an existing output when conversion fails")`. Add focused fixtures inline or create temporary directories with `Files.createTempDirectory`; never depend on the external Babel corpus. Cover successful triples and statistics as well as malformed JSON, unknown prefixes, I/O failures, and atomic-output behavior. Run `make test` before every pull request. No coverage threshold is enforced.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, sentence-case subjects, for example `Add labels for compendium identifiers`. Keep each commit focused. Pull requests should explain the behavioral change, identify affected CLI or data-workflow paths, link relevant issues, and report `make test` results. Include a compact JSONL/N-Triples example when output semantics change; screenshots are generally unnecessary.
