---
title: v3 to v4 Detailed Guide
---

# v3 → v4 Detailed Migration Guide

This guide maps v3 configuration and API constructs to their v4 equivalents.
Element and class names below were verified against the v3 and v4 source trees.

:::tip[Let the Configurator do the first pass]
The [Visual Configurator](https://configurator.norconex.com) imports a v3 XML
file and converts it automatically, producing a migration report and a v4
config you can export as XML, YAML, or JSON. Use this guide to review its
output and to handle whatever it flags. See the
[Migration Overview](./index.md#the-fastest-migration-path).
:::

## Before you start

- v4 requires **Java 21** (v3 required Java 17).
- v4 configuration files can be **XML, YAML, or JSON**. This guide shows XML for
  side-by-side comparison with v3, but every example has a YAML and JSON
  equivalent. For cross-format behavior see
  [Configuration Semantics](../concepts/configuration-semantics.md#scope-and-formats).
- The launch scripts were renamed: `collector-http.sh` → `crawl-web.sh`, and
  `collector-fs.sh` → `crawl-fs.sh`.

## Naming conventions that apply everywhere

Four rules explain a large share of the renames. Apply them first, then consult
the tables below for the remainder.

| Rule                                  | v3                                                     | v4                                                    |
| ------------------------------------- | ------------------------------------------------------ | ----------------------------------------------------- |
| Package rename                        | `com.norconex.collector.core`, `.collector.http`        | `com.norconex.crawler.core`, `.crawler.web`           |
| Committer package rename              | `com.norconex.committer.core3`                          | `com.norconex.committer.core`                         |
| `I` prefix dropped from interfaces    | `IHttpFetcher`, `ICommitter`, `IReferenceFilter`        | `Fetcher`, `Committer`, `ReferenceFilter`             |
| Acronyms are no longer all-caps       | `GenericURLNormalizer`, `DOMTagger`, `MD5...`, `UUID...`| `GenericUrlNormalizer`, `DomTransformer`, `Md5...`, `Uuid...` |

One more that is easy to miss: any option formerly named **`caseSensitive`** is
now **`ignoreCase`**, with inverted meaning. `caseSensitive="false"` becomes
`ignoreCase="true"` (and `ignoreCase` defaults to `false`).

## Maven coordinates

Both the `groupId` and every `artifactId` changed.

| v3 (`com.norconex.collectors.v3`) | v4 (`com.norconex.crawler`) |
| --------------------------------- | --------------------------- |
| `norconex-collector-core`         | `nx-crawler-core`           |
| `norconex-collector-http`         | `nx-crawler-web`            |
| `norconex-collector-filesystem`   | `nx-crawler-fs`             |
| `norconex-importer`               | `nx-importer`               |
| `norconex-committer-core`         | `nx-committer-core`         |
| `norconex-committer-elasticsearch`| `nx-committer-elasticsearch`|
| `norconex-committer-solr`         | `nx-committer-solr`         |
| `norconex-committer-sql`          | `nx-committer-sql`          |

In v3, committers lived in their own repositories and carried their own version
numbers. In v4 they are part of the crawler mono-repo and share the crawler's
version, so a single `${norconex.version}` property covers everything.

```xml
<dependency>
  <groupId>com.norconex.crawler</groupId>
  <artifactId>nx-crawler-web</artifactId>
  <version>4.x.x</version>
</dependency>
```

## Configuration structure

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

```xml
<crawler id="site-a">
  <numThreads>10</numThreads>
  <startReferences>
    <ref>https://site-a.example.com</ref>
  </startReferences>
</crawler>
```

```yaml
id: site-a
numThreads: 10
startReferences:
  - https://site-a.example.com
```

### Why this changed

The multi-crawler-per-file model in v3 existed for two practical reasons that
no longer apply in v4:

**Config sharing was hard.** Grouping crawlers under one collector with
`crawlerDefaults` was the primary way to share settings across crawlers
targeting different sites. V4 addresses this in two better ways: config file
fragments (`#parse` / `#include`, reusable blocks you can reference from any
config file), and per-fetcher scoping — each entry under `<fetchers>` accepts
its own `referenceFilters`, so credentials, proxy settings, and timeouts can be
scoped to specific references within a single crawler. A single v4 crawler can
target as many sites as before, with site-specific settings where needed,
without separate crawler entries.

**JVM startup cost.** When v3 was designed, launching multiple JVM processes
was expensive enough that combining crawlers into one process was worthwhile.
That trade-off no longer applies — running multiple crawler processes
concurrently is cheap and gives you better isolation and resource control.

### Migrating a multi-crawler v3 config

Each v3 `<crawler>` entry becomes its own v4 config file. If multiple crawlers
shared settings via `crawlerDefaults`, either:

- Duplicate the shared settings into each file, or
- Extract them into a shared fragment and `#parse` it from each file.

The Configurator's import does this split for you, producing one file per v3
`<crawler>` entry.

Collector-level options have no v4 equivalent and are simply dropped:
`<maxConcurrentCrawlers>` is gone (run one process per crawler instead), and
`<crawlerDefaults>` is gone. `<workDir>` and `<eventListeners>`, which were
collector-level in v3, are now crawler-level.

## Crawler configuration element map

Renamed or restructured elements, in roughly the order they appear in the v3
reference configuration:

| v3 element                              | v4 element                                                                            |
| --------------------------------------- | ------------------------------------------------------------------------------------- |
| `<httpcollector>` → `<crawlers>` → `<crawler>` | `<crawler>` (root)                                                             |
| `<crawlerDefaults>`                     | *removed* — use `#parse` fragments                                                     |
| `<maxConcurrentCrawlers>`               | *removed*                                                                              |
| `<startURLs>`                           | split into several options — see [Start references](#start-references)                 |
| `<urlNormalizer>`                       | `<urlNormalizers>` (now a **list**, applied in order)                                  |
| `<delay>`                               | `<delayResolver>`                                                                      |
| `<dataStoreEngine>`                     | `<cluster>` — see [Crawl state storage](#crawl-state-storage)                           |
| `<httpFetchers>` → `<fetcher>`          | `<fetchers>` → `<fetcher>`                                                             |
| `<referenceFilters>` → `<filter>`       | `<referenceFilters>` → `<referenceFilter>`                                             |
| `<metadataFilters>` → `<filter>`        | `<metadataFilters>` → `<metadataFilter>`                                               |
| `<documentFilters>` → `<filter>`        | `<documentFilters>` → `<documentFilter>`                                               |
| `<robotsTxt>`                           | `<robotsTxtProvider>`                                                                  |
| `<robotsMeta>`                          | `<robotsMetaProvider>`                                                                 |
| `<sitemapResolver>` (with `<path>`)     | `<sitemapResolver>` + `<sitemapLocator>` (paths moved to the locator)                  |
| `<redirectURLProvider>` (crawler level) | `<redirectUrlProvider>` (**moved inside** `<fetcher>`)                                 |
| `<linkExtractors>` → `<extractor>`      | `<linkExtractors>` → `<linkExtractor>`                                                 |
| `<preImportProcessors>` → `<processor>` | `<preImportConsumers>` → `<preImportConsumer>`                                         |
| `<postImportProcessors>` → `<processor>`| `<postImportConsumers>` → `<consumer>`                                                 |
| `<postImportLinks keep="…">` + `<fieldMatcher>` | `<postImportLinksKeep>` + `<postImportLinks method="…" pattern="…"/>`          |
| `<eventListeners>` → `<listener>`       | `<eventListeners>` → `<eventListener>`                                                 |
| `<fetchHttpHead>`                       | `<metadataFetchSupport>` / `<documentFetchSupport>` (`DISABLED`, `OPTIONAL`, `REQUIRED`) |
| `<keepDownloads>`                       | *removed* — use the importer's `SaveDocumentTransformer`                               |

Unchanged element names: `<workDir>`, `<numThreads>`, `<maxDepth>`,
`<maxDocuments>`, `<orphansStrategy>`, `<stopOnExceptions>`,
`<keepReferencedLinks>`, `<recrawlableResolver>`, `<canonicalLinkDetector>`,
`<metadataChecksummer>`, `<documentChecksummer>`,
`<spoiledReferenceStrategizer>`, `<importer>`, `<committers>`.

:::caution[`maxDocuments` semantics changed]
In v3, `maxDocuments` capped the number of documents processed overall. In v4 it
caps the number processed **within a crawl session** — if the crawler did not
reach completion, the next session resumes where the last one ended.
:::

Several elements kept their name but changed shape. Two common patterns:

**Comma-separated lists became real lists.**

```xml
<!-- v3 -->
<canonicalLinkDetector class="GenericCanonicalLinkDetector">
  <contentTypes>text/html, application/xhtml+xml</contentTypes>
</canonicalLinkDetector>

<!-- v4 -->
<canonicalLinkDetector class="GenericCanonicalLinkDetector">
  <contentTypes>
    <contentType>text/html</contentType>
    <contentType>application/xhtml+xml</contentType>
  </contentTypes>
</canonicalLinkDetector>
```

**Repeated mapping elements became keyed elements.**

```xml
<!-- v3 -->
<spoiledReferenceStrategizer class="GenericSpoiledReferenceStrategizer"
    fallbackStrategy="DELETE">
  <mapping state="NOT_FOUND"  strategy="DELETE" />
  <mapping state="BAD_STATUS" strategy="GRACE_ONCE" />
</spoiledReferenceStrategizer>

<!-- v4 -->
<spoiledReferenceStrategizer class="GenericSpoiledReferenceStrategizer"
    fallbackStrategy="DELETE">
  <mappings>
    <NOT_FOUND>DELETE</NOT_FOUND>
    <BAD_STATUS>GRACE_ONCE</BAD_STATUS>
  </mappings>
</spoiledReferenceStrategizer>
```

## Start references

The v3 `<startURLs>` element carried five different concerns at once. In v4
each one is a separate top-level option, and the scope attributes moved to a
dedicated resolver.

**v3:**

```xml
<startURLs stayOnDomain="true" includeSubdomains="true"
           stayOnPort="true" stayOnProtocol="true" async="true">
  <url>http://www.example.com</url>
  <urlsFile>/path/to/urls.txt</urlsFile>
  <sitemap>http://www.example.com/sitemap.xml</sitemap>
  <provider class="MyStartUrlsProvider"/>
</startURLs>
```

**v4:**

```xml
<startReferences>
  <ref>http://www.example.com</ref>
</startReferences>
<startReferencesFiles>
  <file>/path/to/urls.txt</file>
</startReferencesFiles>
<startReferencesSitemaps>
  <sitemap>http://www.example.com/sitemap.xml</sitemap>
</startReferencesSitemaps>
<startReferencesProviders>
  <provider class="MyStartReferencesProvider"/>
</startReferencesProviders>
<startReferencesAsync>true</startReferencesAsync>

<urlScopeResolver class="GenericUrlScopeResolver">
  <stayOnDomain>true</stayOnDomain>
  <includeSubdomains>true</includeSubdomains>
  <stayOnPort>true</stayOnPort>
  <stayOnProtocol>true</stayOnProtocol>
</urlScopeResolver>
```

| v3                          | v4                          |
| --------------------------- | --------------------------- |
| `startURLs/url`             | `startReferences/ref`       |
| `startURLs/urlsFile`        | `startReferencesFiles/file` |
| `startURLs/sitemap`         | `startReferencesSitemaps/sitemap` |
| `startURLs/provider`        | `startReferencesProviders/provider` |
| `startURLs/@async`          | `startReferencesAsync`      |
| `startURLs/@stayOn*`, `@includeSubdomains` | `urlScopeResolver` (`GenericUrlScopeResolver`) |

`IStartURLsProvider` became `ReferencesProvider`.

## Fetchers

Fetchers moved from the Web Crawler to Crawler Core, so the element is no
longer HTTP-specific. Retry settings were promoted to crawler-level options.

**v3:**

```xml
<httpFetchers>
  <fetcher class="GenericHttpFetcher" maxRetries="3" retryDelay="5000"/>
</httpFetchers>
```

**v4:**

```xml
<fetchersMaxRetries>3</fetchersMaxRetries>
<fetchersRetryDelay>5 seconds</fetchersRetryDelay>
<fetchers>
  <fetcher class="HttpClientFetcher">
    <userAgent>Here we crawl!</userAgent>
    <redirectUrlProvider class="GenericRedirectUrlProvider"/>
  </fetcher>
</fetchers>
```

| v3 class                    | v4 class                                              |
| --------------------------- | ----------------------------------------------------- |
| `GenericHttpFetcher`        | `HttpClientFetcher` (now HTTP/2 capable, Apache HttpClient 5) |
| `WebDriverHttpFetcher`      | `WebDriverFetcher`                                    |
| `PhantomJSDocumentFetcher`  | *removed* — use `WebDriverFetcher` or `PlaywrightFetcher` |
| `GenericRedirectURLProvider`| `GenericRedirectUrlProvider` (configured **inside** the fetcher) |
| `IHttpFetcher`              | `Fetcher`                                             |

`PlaywrightFetcher` is new in v4 and is generally the easier option for
JavaScript-rendered sites.

Each `<fetcher>` accepts its own `<referenceFilters>`, which is how you scope
credentials, proxies, or timeouts to particular sites within a single crawler —
the replacement for using separate v3 crawlers just to vary fetch settings.

Durations throughout v4 accept human-readable or ISO-8601 text (`5 seconds`,
`3 min 30s`, `1s`) in addition to raw milliseconds.

## Filters

Class names first. Note that `ExtensionURLFilter`, `RegexURLFilter`, and
`DomainURLFilter` are **v2** names — if you are coming from v3 you will have the
names in the left column below.

| v3 class                                   | v4 class                    |
| ------------------------------------------ | --------------------------- |
| `ExtensionReferenceFilter`                 | `ExtensionReferenceFilter` (unchanged) |
| `RegexReferenceFilter`                     | `GenericReferenceFilter`    |
| `ReferenceFilter`                          | `GenericReferenceFilter`    |
| `RegexMetadataFilter`                      | `GenericMetadataFilter`     |
| `MetadataFilter`                           | `GenericMetadataFilter`     |
| `SegmentCountURLFilter`                    | `SegmentCountUrlFilter`     |

Their configuration shape also changed: filter values moved out of element text
and into structured matchers.

**v3:**

```xml
<referenceFilters>
  <filter class="ExtensionReferenceFilter" onMatch="exclude">
    jpg,gif,png,ico,css,js</filter>
  <filter class="RegexReferenceFilter">https://www.example.com/.*</filter>
</referenceFilters>

<metadataFilters>
  <filter class="RegexMetadataFilter" onMatch="exclude"
          caseSensitive="false" field="Content-Type">.*css.*</filter>
</metadataFilters>
```

**v4:**

```xml
<referenceFilters>
  <referenceFilter class="ExtensionReferenceFilter" onMatch="exclude">
    <extensions>
      <extension>jpg</extension>
      <extension>gif</extension>
      <extension>png</extension>
    </extensions>
  </referenceFilter>
  <referenceFilter class="GenericReferenceFilter">
    <valueMatcher method="regex" pattern="https://www\.example\.com/.*"/>
  </referenceFilter>
</referenceFilters>

<metadataFilters>
  <metadataFilter class="GenericMetadataFilter" onMatch="exclude">
    <fieldMatcher>Content-Type</fieldMatcher>
    <valueMatcher method="regex" ignoreCase="true" pattern=".*css.*"/>
  </metadataFilter>
</metadataFilters>
```

Key points:

- `onMatch` keeps the same `include` / `exclude` values.
- The old `field` attribute is now a `<fieldMatcher>`, and the element's text
  value is now a `<valueMatcher>` with an explicit `method`
  (`basic`, `wildcard`, `regex`, `csv`).
- `caseSensitive="false"` becomes `ignoreCase="true"` on the matcher.

## Link extractors

**v3:**

```xml
<linkExtractors>
  <extractor class="HtmlLinkExtractor" maxURLLength="2048">
    <contentTypes>text/html, application/xhtml+xml</contentTypes>
    <tags>
      <tag name="a" attribute="href" />
      <tag name="img" attribute="src" />
    </tags>
  </extractor>
</linkExtractors>
```

**v4:**

```xml
<linkExtractors>
  <linkExtractor class="HtmlLinkExtractor" maxURLLength="2048">
    <contentTypeMatcher pattern="text/html"/>
    <tagAttribs>
      <a>href</a>
      <img>src</img>
    </tagAttribs>
  </linkExtractor>
</linkExtractors>
```

```yaml
linkExtractors:
  - class: HtmlLinkExtractor
    maxURLLength: 2048
    contentTypeMatcher:
      pattern: text/html
    tagAttribs:
      a: href
      img: src
```

| v3                            | v4                                         |
| ----------------------------- | ------------------------------------------ |
| `<extractor>`                 | `<linkExtractor>`                          |
| `<tags><tag name= attribute=>`| `<tagAttribs>` map (`<tagName>attribute</tagName>`) |
| `<contentTypes>` (CSV text)   | `<contentTypeMatcher>`                     |
| `GenericLinkExtractor`        | *removed* — use `HtmlLinkExtractor`        |
| `DOMLinkExtractor`            | `DomLinkExtractor`                         |
| `XMLFeedLinkExtractor`        | `XmlFeedLinkExtractor`                     |
| `ILinkExtractor`              | `LinkExtractor`                            |

## URL normalizers

`<urlNormalizer>` became `<urlNormalizers>` — a list, applied in order. The
class was renamed, normalization constants are now **uppercase enum values as
individual elements**, and the replacement element changed shape.

**v3:**

```xml
<urlNormalizer class="GenericURLNormalizer">
  <normalizations>
    lowerCaseSchemeHost, removeDefaultPort, upperCaseEscapeSequence
  </normalizations>
  <replacements>
    <replace>
      <match>&amp;view=print</match>
      <replacement>&amp;view=html</replacement>
    </replace>
  </replacements>
</urlNormalizer>
```

**v4:**

```xml
<urlNormalizers>
  <urlNormalizer class="GenericUrlNormalizer">
    <normalizations>
      <normalization>LOWERCASE_SCHEME_HOST</normalization>
      <normalization>REMOVE_DEFAULT_PORT</normalization>
      <normalization>UPPERCASE_ESCAPESEQUENCE</normalization>
    </normalizations>
    <replacements>
      <replacement>
        <match>&amp;view=print</match>
        <value>&amp;view=html</value>
      </replacement>
    </replacements>
  </urlNormalizer>
</urlNormalizers>
```

Note `<replace>`/`<replacement>` became `<replacement>`/`<value>`.
`IURLNormalizer` became `WebUrlNormalizer` (renamed to avoid clashing with
`com.norconex.commons.lang.url.UrlNormalizer`).

## Delay resolver

`<delay>` became `<delayResolver>`, and its attributes became child elements.
Schedules, which used free-text ranges in v3, are now structured.

**v3:**

```xml
<delay default="1000" ignoreRobotsCrawlDelay="true" class="GenericDelayResolver">
  <schedule dayOfWeek="from Monday to Friday"
      time="from 8:00 to 16:30">10000</schedule>
</delay>
```

**v4:**

```xml
<delayResolver class="GenericDelayResolver">
  <defaultDelay>1s</defaultDelay>
  <ignoreRobotsCrawlDelay>true</ignoreRobotsCrawlDelay>
  <scope>crawler</scope>
  <schedules>
    <schedule>
      <dayOfWeekRange><start>MON</start><end>FRI</end></dayOfWeekRange>
      <timeRange><start>8:00</start><end>16:30</end></timeRange>
      <delay>10s</delay>
    </schedule>
  </schedules>
</delayResolver>
```

`IDelayResolver` became `DelayResolver`; `GenericDelayResolver` and
`ReferenceDelayResolver` both still exist.

## Sitemaps

Sitemap resolution split into two components: the **locator** decides where
sitemaps are, and the **resolver** parses them.

**v3:**

```xml
<sitemapResolver ignore="false" lenient="true" class="GenericSitemapResolver">
  <path>/blogs/sitemap.xml</path>
</sitemapResolver>
```

**v4:**

```xml
<sitemapResolver lenient="true" class="GenericSitemapResolver"/>
<sitemapLocator class="GenericSitemapLocator" robotsTxtSitemapDisabled="false">
  <paths>
    <path>/blogs/sitemap.xml</path>
  </paths>
</sitemapLocator>
```

The `ignore="true"` attribute is gone: to disable sitemap support, set the
component to null (a self-closing element in XML, `null` in YAML/JSON). The
same applies to the former `ignore` attributes on `<robotsTxt>` and
`<robotsMeta>`. See
[Configuration Semantics](../concepts/configuration-semantics.md#null-empty-and-absent-values).

`GenericRecrawlableResolver` minimum frequencies now take a `<matcher>`
(a `TextMatcher`) rather than a raw regular expression attribute.

## Crawl state storage

This is the change most likely to require a decision rather than a rename. The
v3 `<dataStoreEngine>` abstraction is gone. v4 stores crawl state through a
**cluster connector**, which also determines whether the crawler runs
standalone or distributed.

**v3:**

```xml
<dataStoreEngine class="MVStoreDataStoreEngine" />
```

**v4:**

```xml
<!-- Default: file-backed MVStore, no external infrastructure.
     Can be omitted entirely. -->
<cluster>
  <connector class="MVStoreClusterConnector"/>
</cluster>
```

| v3 `dataStoreEngine`   | v4 equivalent                                             |
| ---------------------- | --------------------------------------------------------- |
| `MVStoreDataStoreEngine` | `MVStoreClusterConnector` (the default — omit `<cluster>`) |
| `JdbcDataStoreEngine`  | no direct equivalent — use `HazelcastClusterConnector` for distributed crawls |
| `MongoDataStoreEngine` | no direct equivalent — as above                            |
| *(in-memory testing)*  | `MemoryClusterConnector`                                   |

`IDataStoreEngine` and `IDataStore` no longer exist. If you wrote a custom data
store engine in v3, you now implement a `ClusterConnector` instead.

The `storeexport` and `storeimport` CLI commands still work.

## Committers

The `<committers>` element itself is unchanged — v3 already accepted a list.
What changed is the package, the artifact coordinates, and some class names.

**v3:**

```xml
<committers>
  <committer class="com.norconex.committer.elasticsearch.ElasticsearchCommitter">
    <nodes>http://localhost:9200</nodes>
    <indexName>my-index</indexName>
  </committer>
</committers>
```

**v4:**

```xml
<committers>
  <committer class="com.norconex.committer.elasticsearch.ElasticsearchCommitter">
    <nodes>
      <node>http://localhost:9200</node>
    </nodes>
    <indexName>my-index</indexName>
  </committer>
</committers>
```

```yaml
committers:
  - class: com.norconex.committer.elasticsearch.ElasticsearchCommitter
    nodes:
      - http://localhost:9200
    indexName: my-index
```

| v3 class                     | v4 class              |
| ---------------------------- | --------------------- |
| `ICommitter`                 | `Committer`           |
| `XMLFileCommitter`           | `XmlFileCommitter`    |
| `JSONFileCommitter`          | `JsonFileCommitter`   |
| `CSVFileCommitter`           | `CsvFileCommitter`    |
| `SQLCommitter`               | `SqlCommitter`        |
| `MemoryCommitter`, `LogCommitter` | unchanged        |

Also worth knowing:

- Unless you set one explicitly, each committer now gets a working directory
  named after its simple class name. Duplicates of the same class are suffixed
  with a number (`XmlFileCommitter_2`).
- `MemoryCommitter#clean` now clears cached requests.

## Importer

The Importer changed more than any other module.

### The handler pipeline is now a single flat list

v3 had three separate sections and a parser factory wedged between them. v4 has
one ordered `handlers` list, with parsing performed by a handler in that list
and branching expressed through flow control.

**v3:**

```xml
<importer>
  <preParseHandlers>
    <handler class="..."/>
  </preParseHandlers>
  <documentParserFactory class="..." />
  <postParseHandlers>
    <handler class="..."/>
  </postParseHandlers>
  <responseProcessors>
    <responseProcessor class="..."/>
  </responseProcessors>
</importer>
```

**v4:**

```xml
<importer>
  <handlers>
    <handler class="com.norconex.importer.handler.transformer.impl.ReplaceTransformer">
      <operations>
        <operation><valueMatcher pattern="A"/><toValue>B</toValue></operation>
      </operations>
    </handler>
  </handlers>
</importer>
```

Conditional logic that v3 expressed with per-handler `onMatch` filters is now
explicit `if` / `then` / `else` branching:

```yaml
handlers:
  - if:
      condition:
        class: DateCondition
        fieldMatcher:
          pattern: date_created
        valueMatcherStart:
          operator: ">="
          date: NOW-10Y
      then:
        - handler:
            class: CsvSplitter
      else:
        - handler:
            class: Reject
```

See [Importer Flow Control](/docs/reference/importer-flow-control) for `If`,
`IfNot`, `AllOf`, `AnyOf`, `NoneOf`, and `Reject`.

`responseProcessors` still exists and is unchanged.

### Taggers were merged into transformers

Every `*Tagger` became a `*Transformer`. Most handlers can now target either
content or fields, which is why the two categories collapsed into one.

| v3 tagger                | v4 transformer               |
| ------------------------ | ---------------------------- |
| `CharacterCaseTagger`    | `CharacterCaseTransformer`   |
| `CharsetTagger`          | `CharsetTransformer`         |
| `ConstantTagger`         | `ConstantTransformer`        |
| `CopyTagger`             | `CopyTransformer`            |
| `CountMatchesTagger`     | `CountMatchesTransformer`    |
| `CurrentDateTagger`      | `CurrentDateTransformer`     |
| `DateFormatTagger`       | `DateFormatTransformer`      |
| `DebugTagger`            | `DebugTransformer`           |
| `DeleteTagger`           | `DeleteTransformer`          |
| `DocumentLengthTagger`   | `DocumentLengthTransformer`  |
| `DOMTagger`              | `DomTransformer`             |
| `ExternalTagger`         | `ExternalTransformer`        |
| `FieldReportTagger`      | `FieldReportTransformer`     |
| `ForceSingleValueTagger` | `ForceSingleValueTransformer`|
| `HierarchyTagger`        | `HierarchyTransformer`       |
| `KeepOnlyTagger`         | `KeepOnlyTransformer`        |
| `LanguageTagger`         | `LanguageTransformer`        |
| `MergeTagger`            | `MergeTransformer`           |
| `RegexTagger`            | `RegexTransformer`           |
| `RenameTagger`           | `RenameTransformer`          |
| `ReplaceTagger`          | `ReplaceTransformer`         |
| `ScriptTagger`           | `ScriptTransformer`          |
| `SplitTagger`            | `SplitTransformer`           |
| `TextBetweenTagger`      | `TextBetweenTransformer`     |
| `TextStatisticsTagger`   | `TextStatisticsTransformer`  |
| `TitleGeneratorTagger`   | `TitleGeneratorTransformer`  |
| `TruncateTagger`         | `TruncateTransformer`        |
| `URLExtractorTagger`     | `UrlExtractorTransformer`    |
| `UUIDTagger`             | `UuidTransformer`            |

Existing transformers were renamed too:

| v3 transformer                | v4 transformer                  |
| ----------------------------- | ------------------------------- |
| `DOMDeleteTransformer`, `DOMPreserveTransformer` | `DomTransformer` (one class, with an operation) |
| `ReduceConsecutivesTransformer` | `CollapseRepeatingTransformer` |
| `StripAfter/Before/BetweenTransformer`, `SubstringTransformer`, `ImageTransformer`, `ScriptTransformer`, `ReplaceTransformer`, `CharsetTransformer`, `ExternalTransformer` | unchanged |

`SaveDocumentTransformer` is new — it is the replacement for the crawler's
removed `keepDownloads` option.

Handlers with repeated child configuration blocks now use an `<operations>`
wrapper (for example `ReplaceTransformer`, `CopyTransformer`,
`RenameTransformer`, `TextBetweenTransformer`).

### Filters became conditions

The `handler/filter` package is gone. Its members are now conditions used
inside flow control.

| v3 filter                                | v4 condition          |
| ---------------------------------------- | --------------------- |
| `DOMFilter`, `DOMContentFilter`          | `DomCondition`        |
| `DateMetadataFilter`                     | `DateCondition`       |
| `NumericMetadataFilter`                  | `NumericCondition`    |
| `EmptyFilter`, `EmptyMetadataFilter`     | `BlankCondition`      |
| `ReferenceFilter`, `RegexReferenceFilter`| `ReferenceCondition`  |
| `RegexContentFilter`, `TextFilter`, `RegexMetadataFilter` | `TextCondition` |
| `ScriptFilter`                           | `ScriptCondition`     |
| `RejectFilter`                           | `Reject`              |

The `Operator` inner classes on `DateMetadataFilter` and
`NumericMetadataFilter` were replaced by
`com.norconex.commons.lang.Operator`.

### Parsing is now a handler

There is no parser section in v4. Parsing is performed by `DefaultParser`,
which is an ordinary handler sitting in the `handlers` list — and which is
present by default, so you only declare it explicitly when you need to
configure it or to control where in the chain it runs.

That also means the v3 pre-parse/post-parse distinction now expresses itself as
position: handlers before `DefaultParser` see the raw document, handlers after
it see the parsed text.

```xml
<importer>
  <handlers>
    <!-- runs against the raw document -->
    <handler class="com.norconex.importer.handler.transformer.impl.CharsetTransformer"/>

    <handler class="com.norconex.importer.handler.parser.impl.DefaultParser">
      <errorsSaveDir>/path/to/parse-errors</errorsSaveDir>
      <ocrConfig>
        <!-- former <ocr> options -->
      </ocrConfig>
      <embeddedConfig>
        <splitContentTypes>
          <matcher method="regex" pattern="application/zip"/>
        </splitContentTypes>
      </embeddedConfig>
    </handler>

    <!-- runs against the parsed text -->
    <handler class="com.norconex.importer.handler.transformer.impl.KeepOnlyTransformer"/>
  </handlers>
</importer>
```

| v3                                          | v4                                                   |
| ------------------------------------------- | ---------------------------------------------------- |
| `<preParseHandlers>` / `<postParseHandlers>`| Position within `<handlers>`, relative to `DefaultParser` |
| `<documentParserFactory>`                   | A `DefaultParser` entry in `<handlers>`              |
| `<fallbackParser>`                          | `DefaultParser`                                      |
| `<parseErrorsSaveDir>`                      | `DefaultParser` → `errorsSaveDir`                    |
| `<ocr>`                                     | `DefaultParser` → `ocrConfig`                        |
| `<embedded>`                                | `DefaultParser` → `embeddedConfig`                   |
| `noExtractContainerContentTypes` (CSV text) | `skipEmbeddedOfContentTypes` (list of `TextMatcher`) |
| `noExtractEmbeddedContentTypes` (CSV text)  | `skipEmbeddedContentTypes` (list of `TextMatcher`)   |
| `splitContentTypes` (CSV text)              | `splitContentTypes` (list of `TextMatcher`)          |
| `GenericDocumentParserFactory`              | *removed* — merged into core parser classes          |

`DefaultParser` also gained `grobidConfig` and `maxEmbeddedDepth`, which have
no v3 equivalent.

### Other importer changes

- Splitters gained a `discardOriginal` flag.
- `DocInfo` was renamed `DocRecord`; handlers are now passed a
  `DocHandlerContext`.
- `CommonMatchers` pattern constants are `Collection`s instead of arrays.
- Classes dealing with time zones default to UTC when no zone is declared.
- Script engines: v3 supported JavaScript (Nashorn) and Lua. v4 supports
  JavaScript (GraalVM), Lua, and adds Apache Velocity. **JavaScript scripts
  need review** — the GraalVM engine is stricter than Nashorn.

## Web crawler specifics

| v3 class / option              | v4 class / option                 |
| ------------------------------ | --------------------------------- |
| `com.norconex.collector.http.*`| `com.norconex.crawler.web.*`      |
| `Http*` class prefix           | `Web*`                            |
| `URLStatusCrawlerEventListener`| `UrlStatusCrawlerEventListener`   |
| `FeaturedImageProcessor`       | `FeaturedImageResolver` (now a `preImportConsumer`) |
| `MD5DocumentChecksummer`       | `Md5DocumentChecksummer`          |
| `IHttpDocumentProcessor`       | `DocumentConsumer`                |
| `SitemapChangeFrequency#getSitemapChangeFrequency` | `SitemapChangeFrequency#of` |
| `RobotsTxt` constructor        | builder factory method            |

`DeleteRejectedEventListener` kept its name but moved to
`com.norconex.crawler.core.event.listeners`.

New in the Web Crawler: `stayOnSitemapWhenPresent`, HTTP/2 support, and the
Playwright fetcher.

## File System crawler

The File System Crawler was rewritten for v4 rather than ported. Treat a v3
File System Collector configuration as a starting point for a fresh v4 config
rather than something to translate element by element. See
[File System Quick Start](../getting-started/quick-start-fs.md) and
[File System Fetchers](../getting-started/fs-fetchers-quickstart.md).

Notably, v4 adds fetchers for cloud and enterprise sources (S3, Azure Blob,
ADLS Gen2, Google Cloud Storage, Google Drive, Box, Egnyte, CMIS, SMB, FTP,
and more) that had no v3 equivalent.

## Java API

The v3 `Collector`/`Crawler` split is replaced by a static facade per crawl
type that produces a single `Crawler` instance.

**v3:**

```java
var config = new HttpCollectorConfig();
config.setId("my-collection");
// ... build HttpCrawlerConfig instances and attach them ...

var collector = new HttpCollector(config);
collector.start();
```

**v4:**

```java
var config = new WebCrawlerConfig();
config.setId("my-crawl");
config.setStartReferences(List.of("https://example.com"));

var crawler = WebCrawler.create(config);
crawler.crawl();
```

| v3                             | v4                                                    |
| ------------------------------ | ----------------------------------------------------- |
| `new HttpCollector(config)`    | `WebCrawler.create(config)` → `com.norconex.crawler.core.Crawler` |
| `collector.start()`            | `crawler.crawl()` (or `crawler.crawl(startClean)`)    |
| `collector.clean()`            | `crawler.clean()`                                     |
| `collector.stop()`             | `crawler.stop()`                                      |
| `HttpCollectorConfig`          | *removed* — no collector layer                        |
| `HttpCrawlerConfig`            | `WebCrawlerConfig`                                    |
| `FilesystemCollectorConfig` / `FilesystemCrawlerConfig` | `CrawlerConfig` (the File System Crawler has no subclass of its own) |
| `CollectorException`           | `CrawlerException`                                    |
| `CollectorEvent.COLLECTOR_RUN_BEGIN` / `_END` | `CrawlerEvent.CRAWLER_CRAWL_BEGIN` / `_END` |
| `CollectorEvent.COLLECTOR_*` (other)          | `CrawlerEvent.CRAWLER_*`                   |
| `CollectorCommandLauncher`     | `CliCrawlerLauncher`                                  |

`WebCrawler` and `FsCrawler` are final classes with only static members — there
is no `new WebCrawler()`. Use `WebCrawler.create(config)` to build a crawler, or
`WebCrawler.launch(args)` to run it exactly as the CLI would.

### Component classes are now split from their configuration

Almost every configurable v4 component is a pair: the component itself, and an
immutable-ish `*Config` object reached through `getConfiguration()`.

```java
// v3
var extractor = new HtmlLinkExtractor();
extractor.setMaxURLLength(2048);

// v4
var extractor = new HtmlLinkExtractor();
extractor.getConfiguration().setMaxURLLength(2048);
```

This affects Java code only — configuration files stay flat, with the config
properties appearing directly under the component element.

### Other API changes

- Collection setters no longer accept varargs; pass a `Collection`.
- Methods deprecated in v3 were removed.
- `CrawlerLifeCycleListener` is now abstract.
- `CrawlDocInfo` → `CrawlDocRecord`; `CrawlState` → `CrawlDocState`.
- The `.cmdline` package is now `.cli`.
- `CrawlerConfigLoader` was removed.
- `CrawlerCommitterService` moved from Crawler Core to Committer Core; there
  are new `CommitterService` and `CommitterServiceEvent` classes.

## Command line

The CLI is the part that changed least. Subcommands and their options are the
same; only the script names differ.

```bash
# v3
./collector-http.sh start -config=my-config.xml

# v4
./crawl-web.sh start -config=my-crawl.yaml
```

`start`, `stop`, `clean`, `configcheck`, `configrender`, `storeexport`, and
`storeimport` all still exist, as does `start -clean`.

## Removed in v4

| v3 feature                          | Status in v4                                            |
| ----------------------------------- | ------------------------------------------------------- |
| `<crawlerDefaults>`                 | Removed — use `#parse` fragments                        |
| `<maxConcurrentCrawlers>`           | Removed — run one process per crawler                   |
| `<keepDownloads>` and the `DOCUMENT_SAVED` event | Removed — use `SaveDocumentTransformer`    |
| `<dataStoreEngine>` (JDBC, MongoDB) | Removed — see [Crawl state storage](#crawl-state-storage) |
| `PhantomJSDocumentFetcher`          | Removed — use `WebDriverFetcher` or `PlaywrightFetcher` |
| `GenericLinkExtractor`              | Removed — use `HtmlLinkExtractor`                       |
| `GenericDocumentParserFactory`      | Removed — merged into core parser classes               |
| `CrawlerConfigLoader`               | Removed                                                 |
| Crawler-level `tempDir`             | Removed — derived from `workDir` (the Importer keeps its own `tempDir`) |
| `ignore="true"` attributes          | Removed — set the component to `null` instead           |
| `caseSensitive` options             | Replaced by `ignoreCase`                                |
| Varargs collection setters          | Removed — pass a `Collection`                           |

## New in v4 worth knowing about

Options that have no v3 counterpart and are often useful right after a
migration:

- `idleTimeout` — stop a crawl that has stalled.
- `minProgressLoggingInterval` — throttle progress logging.
- `maxCrawlDuration` — cap total crawl wall time.
- `deferredShutdownDuration` — grace period on shutdown.
- `changeDiscovery` — how the crawler detects changes between sessions.
- `metadataDeduplicate` / `documentDeduplicate` — deduplication by checksum.
- `cluster` — run the crawler across multiple nodes.
- `fetchersMaxRetries` / `fetchersRetryDelay` — crawler-wide fetch retries.
- New `CRAWLER_ERROR` event.
- JMX metrics via `-DenableJMX=true` — see
  [Java Integration](../getting-started/java-integration.md).

## Something not covered here?

If you hit a v3 construct this guide does not mention, try importing the config
into the [Visual Configurator](https://configurator.norconex.com) — it will
name the v4 equivalent or tell you the feature is gone. Failing that, open a
[GitHub Discussion](https://github.com/Norconex/crawler/discussions) with your
v3 config.
