# Norconex Crawlers

[![Java CI with Maven](https://github.com/Norconex/crawler/actions/workflows/maven-ci-cd.yaml/badge.svg)](https://github.com/Norconex/crawler/actions/workflows/maven-ci-cd.yaml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)

Norconex Web and File System Crawlers collect content from websites, file
systems, cloud storage, and content management systems, process it, and commit
it to the repository of your choice — a search engine, a database, a vector
store, or your own pipeline.

They run from the command line with file-based configuration on any OS, or
embed into Java applications through documented APIs.

**Website and documentation: [crawler.norconex.com](https://crawler.norconex.com)**

## Which version do I want?

| | Status | Where |
|---|---|---|
| **Version 3** | **Current stable release** — recommended for production | [`3.x-branch`](https://github.com/Norconex/crawler/tree/3.x-branch) · [docs](https://opensource.norconex.com/crawlers/) |
| **Version 4** | **Beta** — a ground-up rewrite, open for early adopters | `main` (this branch) · [docs](https://crawler.norconex.com) |

Version 4 is a beta: interfaces, configuration, and packaging may still change
before the final release. Version 3 configurations are **not** compatible as-is
and need conversion — the [online configurator](https://configurator.norconex.com)
can import a V3 configuration and convert it for you.

Feedback on the beta is very welcome — please open an
[issue](https://github.com/Norconex/crawler/issues) or start a
[discussion](https://github.com/Norconex/crawler/discussions).

## Getting started with V4

### Docker

Images are published for each release. There is no `latest` tag yet — the
moving tags are reserved for the first stable 4.0.0.

| Image | Purpose |
|---|---|
| [`norconex/crawler-web`](https://hub.docker.com/r/norconex/crawler-web) | Web crawler (includes WebDriver support for JavaScript-rendered pages) |
| [`norconex/crawler-fs`](https://hub.docker.com/r/norconex/crawler-fs) | File system crawler |
| [`norconex/crawler-web-playwright`](https://hub.docker.com/r/norconex/crawler-web-playwright) | Web crawler with Playwright and Chromium bundled, as an alternative to WebDriver |

```console
docker run --rm \
  -v "$PWD/configs:/opt/norconex/crawler/configs" \
  -v "$PWD/logs:/opt/norconex/crawler/logs" \
  norconex/crawler-web:4.0.0-beta.1
```

Images are also mirrored to `ghcr.io/norconex/`.

### Distributions

Ready-to-run zips for the web and file system crawlers, plus each committer,
are attached to every [GitHub release](https://github.com/Norconex/crawler/releases).

### Maven

All modules share the group id `com.norconex.crawler` and one version number.

```xml
<dependency>
  <groupId>com.norconex.crawler</groupId>
  <artifactId>nx-crawler-web</artifactId>
  <version>4.0.0-beta.1</version>
</dependency>
```

### Visual configurator

[configurator.norconex.com](https://configurator.norconex.com) builds and
validates crawler configurations in your browser, and can convert an existing
V3 configuration to V4.

## Projects

This is a mono-repo: every Norconex crawler project that used to live in its
own repository is here, and they are all released together under one version.

| Folder | Artifact Id | Quality gate |
| --- | --- | --- |
| crawler/core/ | nx-crawler-core | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-core) |
| crawler/web/ | nx-crawler-web | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-web&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-web) |
| crawler/fs/ | nx-crawler-fs | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-fs&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-fs) |
| importer/ | nx-importer | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-importer&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-importer) |
| committer/core/ | nx-committer-core | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-core) |
| committer/apachekafka/ | nx-committer-apachekafka | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-apachekafka&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-apachekafka) |
| committer/azurecognitivesearch/ | nx-committer-azurecognitivesearch | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-azurecognitivesearch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-azurecognitivesearch) |
| committer/elasticsearch/ | nx-committer-elasticsearch | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-elasticsearch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-elasticsearch) |
| committer/googlecloudsearch/ | nx-committer-googlecloudsearch | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-googlecloudsearch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-googlecloudsearch) |
| committer/idol/ | nx-committer-idol | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-idol&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-idol) |
| committer/neo4j/ | nx-committer-neo4j | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-neo4j&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-neo4j) |
| committer/solr/ | nx-committer-solr | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-solr&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-solr) |
| committer/sql/ | nx-committer-sql | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-sql&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-sql) |
| 🪦 committer/amazoncloudsearch/ | nx-committer-amazoncloudsearch | Deprecated |

## Contributing

Contributions are welcome — a bug report, a fix, a new feature, or better
documentation. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first: all
contributions need a sign-off and signed commits.

## Sponsors

Norconex Crawler is free and Apache-2.0 licensed, built and maintained by
[Norconex Inc.](https://norconex.com) Sponsorship funds development beyond
what we already invest, and puts your name in front of the people using it.

👉 **[Become a sponsor](https://github.com/sponsors/Norconex)** — tiers and
what each includes are listed there. See [SPONSORS.md](SPONSORS.md) for our
sponsors, and for the many ways to support the project that cost nothing.

Sponsorship is recognition, not a support contract. If your team needs
guaranteed response times or committed engineering, see
[Support](https://crawler.norconex.com/support).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
