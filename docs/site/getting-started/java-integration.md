---
title: Java Integration
---

# Java Integration

The Norconex Crawler is designed to be embedded directly in Java applications.
This page covers three common integration patterns.

## Maven dependencies

Add the crawler(s) you need to your `pom.xml`:

```xml
<!-- Web Crawler -->
<dependency>
  <groupId>com.norconex.crawler</groupId>
  <artifactId>nx-crawler-web</artifactId>
  <version>4.x.x</version>
</dependency>

<!-- File System Crawler -->
<dependency>
  <groupId>com.norconex.crawler</groupId>
  <artifactId>nx-crawler-fs</artifactId>
  <version>4.x.x</version>
</dependency>
```

For committer dependencies, see the [Integrations](/integrations) page.

:::info[File System Crawler]
All the following examples use the Web Crawler. For the File System Crawler,
replace `com.norconex.crawler.web.WebCrawler` with
`com.norconex.crawler.fs.FsCrawler` (and `WebCrawlerConfig` with `FsCrawlerConfig`).
:::

## Pattern 1 — Load config from file and run

The simplest integration: point the crawler at a configuration file and run it
programmatically, simulating launching it from the command-line.

```java
import com.norconex.crawler.web.WebCrawler;

public class MyCrawlerApp {
    public static void main(String[] args) throws Exception {
        WebCrawler.launch("start", "-config=/path/to/my-crawl.yaml") ;
    }
}
```

## Pattern 2 — Configure programmatically

Build the entire configuration in code without a config file:

```java
import com.norconex.crawler.web.WebCrawler;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.committer.elasticsearch.ElasticsearchCommitter;

public class MyCrawlerApp {
    public static void main(String[] args) throws Exception {
        var esCommitter = new ElasticsearchCommitter();
        esCommitter.setNodes("http://localhost:9200");
        esCommitter.setIndexName("my-content");

        var config = new WebCrawlerConfig();
        config.setId("my-crawl");
        config.setStartReferences(List.of("https://example.com"));
        config.setNumThreads(10);
        config.setCommitters(List.of(esCommitter));
        // ...

        var crawler = WebCrawler.create(config);
        crawler.crawl();
    }
}
```

## Pattern 3 — Event-driven integration

React to crawl lifecycle events to integrate with your application's monitoring
or workflow:

```java
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.web.WebCrawler;
import com.norconex.crawler.web.WebCrawlerConfig;

public class MyCrawlerApp {
    public static void main(String[] args) throws Exception {
        var config = new WebCrawlerConfig();
        // ...

        config.addEventListener(event -> {
            if (event instanceof CrawlerEvent e) {
                System.out.println("Crawler event name: " + event.getName());
                if (e.is(CrawlerEvent.CRAWLER_CRAWL_BEGIN)) {
                    System.out.println("Crawl started: "
                            + e.getCrawlSession().getCrawlerId());
                }
                if (e.is(CrawlerEvent.CRAWLER_CRAWL_END)) {
                    System.out.println("Crawl ended: "
                            + e.getCrawlSession().getCrawlerId());
                }
            }
        });

        var crawler = WebCrawler.create(config);
        crawler.crawl();
    }
}
```

:::info[JMX Events]
The crawler can also expose live data via JMX to facilitate integration
with monitoring tools such as [Prometheus](https://prometheus.io/). To enable
it, pass the JVM argument `-DenableJMX=true`.
:::

## Pattern 4 — Run summary file

When something else launches the crawler — a scheduler, a CI job, a
supervising process — it usually needs to know what a run did without reading
the log or embedding Java. Name a file with the `runSummaryFile` JVM argument
and the crawler writes a summary there when the run ends:

```bash
-DrunSummaryFile=./run-summary.json
```

Add it to the `java` command in `crawl-web.sh` / `crawl-web.bat`, the same
place `-DenableJMX=true` and `-Xmx` go. Nothing is written when the argument is
absent.

```json
{
  "crawlerId" : "my-crawler",
  "state" : "COMPLETED",
  "counts" : {
    "NEW" : 3,
    "MODIFIED" : 1,
    "UNMODIFIED" : 41
  }
}
```

`counts` is keyed by processing outcome — `NEW`, `MODIFIED`, `UNMODIFIED`,
`DELETED`, `ERROR`, `REJECTED`, `BAD_STATUS`, `NOT_FOUND` — so a second run
over the same source tells you exactly what changed. `state` is the state the
crawl ended in: `COMPLETED`, `STOPPED` or `FAILED`.

A few properties worth relying on:

- The file is written even when a crawl fails, so what it managed to process
  before failing is still reported.
- It is renamed into place, so a process waiting on it sees either no file or
  a complete one — never a half-written one.
- A crawl that processed nothing writes empty `counts` rather than no file,
  which is what distinguishes "nothing to do" from "the crawler died".
- Producing it costs one pass over the run's processed entries, which is why
  it is opt-in rather than always on.

## Stopping a running crawl

```java
// Starts asynchronously
crawler.crawl();

// Stop gracefully (waits for in-flight documents to finish)
crawler.stop();
```

## Next steps

- [Concepts: Extending the Crawler](../concepts/extending) — custom components and SPI
- [Configuration Reference ↗](https://configurator.norconex.com/docs) — full config options
