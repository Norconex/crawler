---
title: File System Crawler Quick Start
---

# File System Crawler Quick Start

This guide gets you from zero to a running file system crawl in under 5 minutes.

:::tip[Built-in baseline, not a hard limit]
The source protocols and examples in this guide represent built-in support in
Norconex Crawler v4. They are practical defaults, not a fixed ceiling.
Teams can extend crawler behavior with custom connectors, parser logic, and
pipeline components for customer-specific requirements.
:::

:::note[Windows users]
Replace `crawl-fs.sh` with `crawl-fs.bat` and `./` with `.\` in all commands below.
:::

## Step 1 — Create a config file

Create `my-fs-crawl.yaml` with the following minimal configuration:

```yaml
id: my-first-crawl
numThreads: 5
startReferences:
  - /path/to/your/documents
committers:
  - class: LogCommitter
    ignoreContent: true
    logLevel: INFO
```

This crawl will process every file under the given path and log each document
(`LogCommitter` is built-in and ideal for testing before connecting a real backend).

## Step 2 — Filter by file type (optional)

To crawl only specific file extensions:

```yaml
id: my-first-crawl
startReferences:
  - /path/to/your/documents
referenceFilters:
  - class: ExtensionReferenceFilter
    onMatch: INCLUDE
    extensions:
      - pdf
      - docx
      - xlsx
      - pptx
      - txt
      - html
```

## Step 3 — Start the crawl

```bash
./crawl-fs.sh start -config=my-fs-crawl.yaml
```

You'll see log output as files are fetched, filtered, and committed.

### Docker alternative

If you prefer running with Docker, mount your config and logs, then run:

```bash
docker run --rm \
  -v "${PWD}:/opt/norconex/crawler/configs" \
  -v "${PWD}/logs:/opt/norconex/crawler/logs" \
  -e COLLECTOR_CONFIG_FILE=my-fs-crawl.yaml \
  norconex/crawler-fs:latest
```

For Docker Compose examples and release-tag guidance, see [Docker](./docker.md).

## Step 4 — Stop and resume

Stop the crawl at any time:

```bash
./crawl-fs.sh stop -config=my-fs-crawl.yaml
```

Norconex saves its state automatically. Resume exactly where you left off by running the same start command again:

```bash
./crawl-fs.sh start -config=my-fs-crawl.yaml
```

To clear the crawler state before your next run, you can issue this command:

```bash
./crawl-fs.sh clean -config=my-fs-crawl.yaml
```

Alternatively, you can combine "clean" with the start command:

```bash
./crawl-fs.sh start -clean -config=my-fs-crawl.yaml
```

## Step 5 — Remote file systems

The file system crawler supports remote protocols out of the box. A few
examples:

If you are unsure which scheme or start reference format to use for a given
fetcher, see [FS Fetchers Quickstart](./fs-fetchers-quickstart.md).

**SFTP:**

```yaml
id: my-first-crawl
startReferences:
  - sftp://fileserver.example.com/data/documents
credentials:
  username: user
  password: myPassword
```

**WebDAV (Nextcloud, older versions of SharePoint):**

```yaml
id: my-first-crawl
startReferences:
  - https://sharepoint.example.com/sites/mysite/Shared Documents/
credentials:
  username: user
  password: myPassword
```

**Apache HDFS:**

```yaml
id: my-first-crawl
startReferences:
  - webhdfs://namenode:9870/user/data/corpus
```

For Box, Google Drive, Egnyte, and M365-specific examples, use the dedicated fetcher
reference pages so configuration examples stay canonical in one place:

- [BoxFetcher](../reference-source/crawler/BoxFetcher)
- [GoogleDriveFetcher](../reference-source/crawler/GoogleDriveFetcher)
- [EgnyteFetcher](../reference-source/crawler/EgnyteFetcher)
- [M365GraphFetcher](../reference-source/crawler/M365GraphFetcher)

## Step 6 — Send to a real target

Replace the `LogCommitter` with your actual destination. See the
[Integrations](/integrations) page for all available committers and their configuration.

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
