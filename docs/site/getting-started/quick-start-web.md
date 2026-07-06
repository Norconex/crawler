---
title: Web Crawler Quick Start
---

# Web Crawler Quick Start

This guide gets you from zero to a running web crawl in under 5 minutes.

:::note[Windows users]
Replace `crawl-web.sh` with `crawl-web.bat` and `./` with `.\` in all commands below.
:::

## Step 1 — Create a config file

Create `my-web-crawl.yaml` with the following minimal configuration:

```yaml
id: my-first-crawl
numThreads: 5
maxDepth: 3
startReferences:
  - https://example.com
urlScopeResolver:
  includeSubdomains: false
  stayOnDomain: true
committers:
  - class: LogCommitter
    ignoreContent: true
    logLevel: INFO
```

This crawl will:

- Start at `https://example.com`
- Follow links up to 3 levels deep
- Log each document it processes (`LogCommitter` is built-in and ideal for testing)

## Step 2 — Start the crawl

```bash
./crawl-web.sh start -config=my-web-crawl.yaml
```

You'll see log output as pages are fetched, filtered, and committed.

### Docker alternative

If you prefer running with Docker, mount your config and logs, then run:

```bash
docker run --rm \
  -v "${PWD}:/opt/norconex/crawler/configs" \
  -v "${PWD}/logs:/opt/norconex/crawler/logs" \
  -e COLLECTOR_CONFIG_FILE=my-web-crawl.yaml \
  norconex/crawler-web:latest
```

For Docker Compose examples and release-tag guidance, see [Docker](./docker.md).

## Step 3 — Stop and resume

Stop the crawl at any time:

```bash
./crawl-web.sh stop -config=my-web-crawl.yaml
```

Norconex saves its state to disk automatically. Resume exactly where you left off by running the same start command again:

```bash
./crawl-web.sh start -config=my-web-crawl.yaml
```

To clear the crawler state before your next run, you can issue this command:

```bash
./crawl-web.sh clean -config=my-web-crawl.yaml
```

Alternatively, you can combine "clean" with the start command:

```bash
./crawl-web.sh start -clean -config=my-web-crawl.yaml
```

## Step 4 — Send to a real target

Replace the `LogCommitter` with your actual destination.

**Elasticsearch:**

```yaml
committers:
  - class: ElasticsearchCommitter
    nodes: http://localhost:9200
    indexName: my-website
```

**Apache Solr:**

```yaml
committers:
  - class: SolrCommitter
    solrURL: http://localhost:8983/solr/my-core
```

See the [Integrations](/integrations) page for all available committers and their configuration.

When using ZIP distributions, external committers are downloaded separately as
`nx-committer-<name>-<version>.zip` and their `lib/*.jar` files must be copied into
the crawler `lib/` directory. Built-in committers such as `LogCommitter` do not
require this extra step.

## CLI reference

| Command                | Description                                |
| ---------------------- | ------------------------------------------ |
| `start -config=<file>` | Start or resume a crawl                    |
| `stop -config=<file>`  | Gracefully stop a running crawl            |
| `clean -config=<file>` | Delete crawl state (forces a full recrawl) |

## Next steps

- Use the [Visual Configurator](https://crawlerconfig.norconex.com) to build your config visually
- Read [Concepts: Crawl Pipeline](../concepts/crawl-pipeline) to understand how documents are processed
- Read [Concepts: Sessions](../concepts/sessions) to understand resume, deduplication, recrawl policy, and external run scheduling
