# Norconex File System Crawler

Open-source crawler that collects content from local and remote file systems,
processes it, and commits it to the target repository of your choice — Solr,
Elasticsearch, Azure AI Search, Amazon CloudSearch, Google Cloud Search, Neo4j,
SQL, Apache Kafka, and more.

Supports local paths plus remote sources such as SMB/CIFS, FTP, SFTP, WebDAV,
and cloud storage, depending on how you configure it.

Part of the [Norconex Crawler](https://github.com/Norconex/crawler) project.

## Tags

- `{{VERSION}}` — the release this page was last updated for.
- `latest`, `4`, `4.0` — published for **stable** releases only. A pre-release
  (`-beta`, `-rc`) publishes its exact version tag and nothing else, so the
  moving tags are never pointed at pre-release code.

Always pull an explicit version if you need a reproducible build.

## Quick start

Put your `crawler-config.xml` in a local `configs` directory, then:

    docker run --rm \
      -v "$PWD/configs:/opt/norconex/crawler/configs" \
      -v "$PWD/logs:/opt/norconex/crawler/logs" \
      -v "/path/to/content:/data:ro" \
      norconex/crawler-fs:{{VERSION}}

Mount whatever content you intend to crawl and point your configuration at the
path inside the container. Any extra arguments after the image name are passed
through to the crawler.

## Configuration

| | |
|---|---|
| Config directory | `/opt/norconex/crawler/configs` (volume) |
| Log directory | `/opt/norconex/crawler/logs` (volume) |
| Config file | `crawler-config.xml`, override with `-e COLLECTOR_CONFIG_FILE=...` |

## Notes

- Based on `eclipse-temurin:21-jre-alpine`.
- All Norconex committers are bundled.

## Links

- Source and issues: https://github.com/Norconex/crawler
- Releases and release notes: https://github.com/Norconex/crawler/releases
- License: Apache-2.0
