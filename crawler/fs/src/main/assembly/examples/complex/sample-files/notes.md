# Field notes

The crawler treats this Markdown file as plain text: the parser keeps the
words and drops nothing of substance.

## What the complex example demonstrates

- **Variables** — `crawlerId`, `workdir`, `crawlDepth`, `numThreads`, and an
  optional `sourceDir`, resolved from `complex-config.variables` or a file
  passed with `-variables`.
- **File inclusion** — `shared/importer-config.xml` is pulled in with a
  parse directive and brings its own `importer-config.properties`.
- **Conditional sections** — the production profile adds a second committer.
