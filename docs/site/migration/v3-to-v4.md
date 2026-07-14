---
title: v3 to v4 Detailed Guide
---

# v3 → v4 Detailed Migration Guide

## Configuration structure

Before diving into field-level changes, review
[Configuration Semantics](../concepts/configuration-semantics.md#v3-xml-to-v4-format-mapping)
for cross-format behavior mapping from V3 XML to V4 XML/YAML/JSON.

### One file, one crawler

The most significant structural change in v4 is that **each configuration
file defines exactly one crawler**. The v3 model — a collector wrapping
multiple crawlers under a single config, with a `crawlerDefaults` block to
share settings — is gone.

**v3** grouped multiple crawlers under a collector:

```xml
<httpcollector id="my-collection">
  <crawlerDefaults>
    <numThreads>10</numThreads>
  </crawlerDefaults>
  <crawlers>
    <crawler id="site-a">
      <startURLs><url>https://site-a.example.com</url></startURLs>
    </crawler>
    <crawler id="site-b">
      <startURLs><url>https://site-b.example.com</url></startURLs>
    </crawler>
  </crawlers>
</httpcollector>
```

**v4** is a flat, single-crawler config file:

```yaml
id: my-crawl
numThreads: 10
startReferences:
  - https://example.com
```

### Why this changed

The multi-crawler-per-file model in v3 existed for two practical reasons that
no longer apply in v4:

**Config sharing was hard.** Grouping crawlers under one collector with
`crawlerDefaults` was the primary way to share settings across crawlers
targeting different sites. V4 addresses this in two better ways: config file
includes (reusable config fragments you can reference from any config file),
and per-site fetch configuration (credentials, delays, proxy, and other
fetch settings can now be scoped to specific URL patterns or paths within a
single crawler). A single v4 crawler can target as many sites as before, with
site-specific settings where needed, without needing separate crawler entries.

**JVM startup cost.** When v3 was designed, launching multiple JVM processes
was expensive enough that combining crawlers into one process was worthwhile.
That trade-off no longer applies — running multiple crawler processes
concurrently is cheap and gives you better isolation and resource control.

### Migrating a multi-crawler v3 config

Each v3 `<crawler>` entry becomes its own v4 config file. If multiple crawlers
shared settings via `crawlerDefaults`, either:

- Duplicate the shared settings into each file, or
- Extract them into a shared config fragment and include it from each file.

Use the [Visual Configurator](https://configurator.norconex.com.com) to convert
each crawler block individually.

Key field renames:

- `startURLs/url` → `startReferences` (flat list)
- `numThreads` is now a top-level field (was in `crawlerDefaults` or per-crawler)

## Module / artifact renaming

| v3 artifact                  | v4 artifact                              |
| ---------------------------- | ---------------------------------------- |
| `nx-collector-core`          | `nx-crawler-core`                        |
| `nx-collector-http`          | `nx-crawler-web`                         |
| `nx-collector-filesystem`    | `nx-crawler-fs`                          |
| `nx-committer-core`          | `nx-committer-core` (unchanged)          |
| `nx-committer-elasticsearch` | `nx-committer-elasticsearch` (unchanged) |

## Committer configuration

**v3:**

```xml
<committer class="com.norconex.committer.elasticsearch.ElasticsearchCommitter">
  <nodes>http://localhost:9200</nodes>
  <indexName>my-index</indexName>
</committer>
```

**v4:**

```yaml
committers:
  - class: com.norconex.committer.elasticsearch.ElasticsearchCommitter
    nodes: http://localhost:9200
    indexName: my-index
```

## Filter migration

| v3 class                | v4 class                      |
| ----------------------- | ----------------------------- |
| `ExtensionURLFilter`    | `ExtensionReferenceFilter`    |
| `RegexURLFilter`        | `RegexReferenceFilter`        |
| `SegmentCountURLFilter` | `SegmentCountReferenceFilter` |
| `DomainURLFilter`       | `DomainReferenceFilter`       |

The v3 `onMatch="exclude"` attribute maps directly to `onMatch: exclude` in v4 YAML.

## Link extraction

**v3:**

```xml
<linkExtractors>
  <extractor class="com.norconex.collector.http.link.impl.HtmlLinkExtractor">
    <tags><tag name="a" attribute="href"/></tags>
  </extractor>
</linkExtractors>
```

**v4:**

```yaml
linkExtractors:
  - class: HtmlLinkExtractor
    tags:
      - tagName: a
        attribute: href
```

## Java API

The v3 `Collector`/`Crawler` split is replaced by a single class per crawl type:

| v3                          | v4                             |
| --------------------------- | ------------------------------ |
| `new HttpCollector(config)` | `new WebCrawler()`             |
| `collector.start(false)`    | `crawler.start(config)`        |
| `HttpCollectorConfig`       | `WebCrawlerConfig`             |
| `HttpCrawlerConfig`         | folded into `WebCrawlerConfig` |

## Importer / transformer migration

The Import module class names are largely the same.
The main change is how transformers are configured: v3 XML attributes become YAML fields.

Use the [Visual Configurator](https://configurator.norconex.com.com) to verify each class name —
it shows both the current v4 name and any renamed v3 equivalents.

## Unsupported v3 features

A small number of v3 features were removed or replaced in v4:

| v3 feature                               | v4 status                               |
| ---------------------------------------- | --------------------------------------- |
| `DelayResolver` via XML sleep            | Use `crawlDelay` with ISO-8601 duration |
| Groovy-scripted filters (pre-Import)     | Use `ScriptFilter` with updated syntax  |
| Custom `ICrawlDataStore` implementations | Use the new `IDataStoreEngine` SPI      |

If you encounter a v3 feature not covered here, open a [GitHub Discussion](https://github.com/Norconex/crawlers/discussions).
