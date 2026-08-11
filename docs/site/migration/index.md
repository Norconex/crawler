---
title: Migration Guide
sidebar_label: Overview
slug: /migration
---

# Migrating from v3 to v4

Norconex Crawler v4 is a significant evolution of the v3 series.
The core concepts are unchanged — you still define start references, filters,
fetchers, importer handlers, and committers — but package names, class names,
configuration element names, and the Java API have all changed substantially.

## The fastest migration path

The [Visual Configurator](https://configurator.norconex.com) can do most of the
work for you, and requires no coding. It is a web application Norconex hosts
and offers free of charge, so there is nothing to install. The Configurator
itself is not open source — the crawler is, and remains so.

The conversion runs entirely in your browser: your configuration is never
uploaded, which matters because v3 configs routinely contain credentials,
internal hostnames, and proxy settings.

1. Open the Configurator and choose **Import**.
2. Provide your v3 configuration — upload the `.xml` file, drag and drop it, or
   paste its content directly.
3. The Configurator detects the v3 format automatically (by its
   `<httpcollector>` root element) and offers to migrate it.
4. It converts the configuration, splitting a multi-crawler v3 file into one v4
   configuration per `<crawler>` entry, and applies known class and element
   renames.
5. A **migration report** lists what was converted, what was renamed, and what
   needs your attention. You can download it, download an individual crawler
   configuration, or download a ZIP containing every converted crawler plus the
   report.
6. Review and adjust the result visually in the Configurator, then **Export**
   as XML, YAML, or JSON — either copied to your clipboard or downloaded as a
   file.

:::tip[Non-coders welcome]
Because import and export are entirely UI-driven, this path requires no Java
and no hand-editing of configuration files. It is also the fastest way to
discover the v4 equivalent of a v3 class you cannot find in this guide.
:::

:::caution[Two things the converter cannot do for you]

- **Unresolved fragments.** If your v3 file uses Velocity `#include` or
  `#parse` directives, the Configurator only sees the file you gave it. Inline
  the fragments first, or migrate each fragment separately.
- **Custom classes.** Your own implementations of v3 interfaces are carried
  over by name but will not compile or load against v4. See
  [Java API changes](./v3-to-v4.md#java-api) and
  [Extending the Crawler](../concepts/extending.md).

:::

## What changed at a high level

| Area                    | v3                                                                     | v4                                                                     |
| ----------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| **Minimum Java**        | 17                                                                     | 21                                                                     |
| **Config format**       | XML only                                                               | XML, YAML, and JSON equally supported                                  |
| **Config structure**    | One file: a collector wrapping multiple crawlers                       | One file = one crawler                                                 |
| **Maven groupId**       | `com.norconex.collectors.v3`                                           | `com.norconex.crawler`                                                 |
| **Maven artifacts**     | `norconex-collector-*`, `norconex-importer`, `norconex-committer-*`    | `nx-crawler-*`, `nx-importer`, `nx-committer-*`                        |
| **Java packages**       | `com.norconex.collector.*`, `com.norconex.committer.core3`             | `com.norconex.crawler.*`, `com.norconex.committer.core`                |
| **Interfaces**          | `I`-prefixed (`IHttpFetcher`, `ICommitter`)                            | Prefix dropped (`Fetcher`, `Committer`)                                |
| **Java API entry**      | `new HttpCollector(config).start()`                                    | `WebCrawler.create(config).crawl()`                                    |
| **Crawl state storage** | `<dataStoreEngine>` (MVStore, JDBC, MongoDB)                           | `<cluster>` connectors (MVStore, Hazelcast, in-memory)                 |
| **Importer pipeline**   | `preParseHandlers` / `documentParserFactory` / `postParseHandlers`     | A single `handlers` list with conditional flow control                 |
| **Importer handlers**   | Taggers, transformers, filters, splitters                              | Transformers, conditions, splitters (taggers merged into transformers) |
| **Committers**          | Separate repository per committer, independent versions                | Same mono-repo, versioned with the crawler                             |
| **Launch scripts**      | `collector-http.sh`, `collector-fs.sh`                                 | `crawl-web.sh`, `crawl-fs.sh`                                          |

Things that did **not** change: CLI subcommands (`start`, `stop`, `clean`,
`configcheck`, `configrender`, `storeexport`, `storeimport`), the event system
model (v3 already used a unified event manager), short class-name resolution in
configuration files (v3 supported that too), and Velocity variables and
fragments.

## Detailed migration steps

See the [v3 to v4 Detailed Guide](./v3-to-v4.md) for element-by-element and
class-by-class mapping tables.

For cross-format configuration behavior (null vs empty, omitted defaults,
variables, fragments), see
[Configuration Semantics](../concepts/configuration-semantics.md), including
the v3 mapping callout.

## Need help?

- Open a [GitHub Discussion](https://github.com/Norconex/crawler/discussions) with your v3 config
- Check [Issues](https://github.com/Norconex/crawler/issues) for known migration edge cases
