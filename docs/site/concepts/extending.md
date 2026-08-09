---
title: Extending the Crawler
---

# Extending the Crawler

Norconex Crawler is designed for extension. Every major component — parsers,
filters, transformers, committers, fetchers — is a plugin point you can
replace or augment without forking or modifying the core.

## The simplest approach: drop in a class

The minimal path to a custom extension is three steps:

1. Implement the relevant interface
2. Package your class in a JAR and drop it in the `lib/` folder of the distribution
3. Reference it by **fully-qualified class name** in your configuration

No annotation, no registration file, no code generation required. The
configuration loader resolves any fully-qualified class name directly at runtime.

```yaml
documentFilters:
  - class: com.example.PricingPageFilter
```

That is all that is needed for the crawler to instantiate and use your class.

## Common extension points

### Crawl-stage document filter

Implement `DocumentFilter` to accept or reject a fetched document before it
enters the Importer pipeline.

```java
package com.example;

import com.norconex.crawler.core.doc.operations.filter.DocumentFilter;
import com.norconex.importer.doc.Doc;

public class PricingPageFilter implements DocumentFilter {

    @Override
    public boolean acceptDocument(Doc document) {
        return document.getReference().contains("pricing");
    }
}
```

```yaml
documentFilters:
  - class: com.example.PricingPageFilter
```

### Importer handler

Implement `DocHandler` to add custom behavior inside the Importer pipeline —
enriching metadata, transforming content, or rejecting a document. Return
`false` to stop processing and discard the document.

```java
package com.example;

import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

public class UppercaseTitleHandler implements DocHandler {

    @Override
    public boolean handle(DocHandlerContext ctx) throws java.io.IOException {
        var title = ctx.metadata().getString("title");
        if (title != null) {
            ctx.metadata().set("title", title.toUpperCase());
        }
        return true;
    }
}
```

```yaml
handlers:
  - class: com.example.UppercaseTitleHandler
```

### Custom committer

Extend `AbstractCommitter` to send documents to any destination.

```java
package com.example;

import com.norconex.committer.core.AbstractCommitter;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;

public class MyApiCommitter extends AbstractCommitter<MyApiCommitterConfig> {

    @Override
    protected void doUpsert(UpsertRequest request) {
        myApiClient.index(
            request.getReference(),
            request.getContent(),
            request.getMetadata());
    }

    @Override
    protected void doDelete(DeleteRequest request) {
        myApiClient.delete(request.getReference());
    }
}
```

```yaml
committers:
  - class: com.example.MyApiCommitter
```

## Short class names via SPI (optional)

By default, you must use the fully-qualified class name in configuration.
If you want to reference your class by its **simple name** (without the
package prefix), register it through the
**Service Provider Interface (SPI)**.

Create a class that extends `BasePolymorphicTypeProvider` and registers your
types:

```java
package com.example.spi;

import com.norconex.commons.lang.bean.spi.BasePolymorphicTypeProvider;
import com.example.PricingPageFilter;
import com.norconex.crawler.core.doc.operations.filter.DocumentFilter;

public class MyExtensionsPtProvider extends BasePolymorphicTypeProvider {

    @Override
    protected void register(Registry registry) {
        registry.add(DocumentFilter.class, PricingPageFilter.class);
    }
}
```

Then declare it as a service in your JAR:

```
META-INF/services/com.norconex.commons.lang.bean.spi.PolymorphicTypeProvider
```

with the content:

```
com.example.spi.MyExtensionsPtProvider
```

After this, the short name works in configuration:

```yaml
documentFilters:
  - class: PricingPageFilter
```

## Event listeners

All crawler events are instances of `CrawlerEvent`. There are no separate
classes per event type — the event type is identified by a string name checked
with `event.is(String)`.

To create a reusable listener, implement `EventListener<Event>` and register
it in your config. Its single method is `accept(Event event)`.

```java
package com.example;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.event.CrawlerEvent;

public class MyAuditListener implements EventListener<Event> {

    @Override
    public void accept(Event event) {
        if (!(event instanceof CrawlerEvent e)) {
            return;
        }
        if (e.is(CrawlerEvent.DOCUMENT_FETCHED)) {
            log.info("Fetched: {}", e.getCrawlEntry().getReference());
        }
        if (e.is(CrawlerEvent.REJECTED_FILTER)) {
            log.warn("Filtered out: {}", e.getCrawlEntry().getReference());
        }
    }
}
```

Register it in your configuration file:

```yaml
eventListeners:
  - class: com.example.MyAuditListener
```

If you only care about lifecycle events (start, stop, clean, error), extend the
`CrawlerLifeCycleListener` adapter and override only the methods you need:

```java
public class MyCrawlLifecycle extends CrawlerLifeCycleListener {

    @Override
    protected void onCrawlerCrawlBegin(CrawlerEvent event) {
        log.info("Crawl started: {}", event.getCrawlSession().getCrawlerId());
    }

    @Override
    protected void onCrawlerCrawlEnd(CrawlerEvent event) {
        log.info("Crawl finished.");
    }
}
```

Selected event name constants on `CrawlerEvent`:

| Constant | When it fires |
|----------|--------------|
| `CRAWLER_CRAWL_BEGIN` | Crawl is about to begin |
| `CRAWLER_CRAWL_END` | Crawl completed normally |
| `CRAWLER_ERROR` | An error occurred in the crawler |
| `DOCUMENT_FETCHED` | A document was successfully fetched |
| `DOCUMENT_IMPORTED` | The Importer pipeline completed for a document |
| `DOCUMENT_PROCESSED` | A document finished processing (success or not) |
| `REJECTED_FILTER` | Discarded by a reference or document filter |
| `REJECTED_NOTFOUND` | Discarded because it no longer exists |
| `REJECTED_ERROR` | Discarded due to a processing error |

For attaching listeners programmatically when embedding the crawler in Java,
see [Java Integration](../getting-started/java-integration).

## Packaging

Place your JAR in the `lib/` directory of the crawler distribution. No changes
to the core are required. For Maven projects, declare a dependency on the
crawler module and the `importer` module — your JAR will be picked up
automatically at runtime.

## Resources

- [Reference](/docs/reference/) — all built-in extension points with examples
- [GitHub: Norconex/crawler](https://github.com/Norconex/crawler) — source code and
  existing implementations to use as a reference
