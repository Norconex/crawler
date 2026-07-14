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

## Stopping a running crawl

```java
// Starts asynchronously
crawler.crawl();

// Stop gracefully (waits for in-flight documents to finish)
crawler.stop();
```

## Next steps

- [Concepts: Extending the Crawler](../concepts/extending) — custom components and SPI
- [Configuration Reference ↗](https://configurator.norconex.com.com/docs) — full config options
