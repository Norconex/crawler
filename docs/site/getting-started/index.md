---
title: Introduction
sidebar_label: Introduction
slug: /getting-started
---

# Welcome to Norconex Crawler

Norconex Crawler is an open-source, enterprise-grade crawling framework that collects content
from **websites** and **file systems** and delivers it to search engines, databases, and data
pipelines — without vendor lock-in.

## Two crawlers, one framework

|                      | Web Crawler                                      | File system Crawler                                   |
| -------------------- | ------------------------------------------------ | ----------------------------------------------------- |
| **Sources**          | HTTP/S websites and web applications             | Local and remote file storage across many protocols   |
| **How it navigates** | Follows hyperlinks; can render JavaScript        | Traverses directory trees; resolves paths by protocol |
| **Typical use case** | Web indexing, site monitoring, content archiving | Document management, network storage indexing         |

Both crawlers share the same configuration model, pipeline, and committer system.
Learn one, know both.

## Core concepts at a glance

- **Crawl session** — a named, resumable run with persistent state
- **Pipeline** — every document flows through: Crawl → Process → Commit
- **Committers** — pluggable output targets (Elasticsearch, Solr, Kafka, SQL, Neo4j, ...)

A hosted companion tool, the [Visual Configurator](https://configurator.norconex.com),
lets you build and validate crawler configs visually — it is not part of the open-source
distribution.

## Where to go next

- **New to Norconex Crawler?** → [Installation](./installation.md) then [Docker](./docker.md), [Web Quick Start](./quick-start-web.md), or [File System Quick Start](./quick-start-fs.md)
- **Embedding in Java?** → [Java Integration](./java-integration.md)
- **Need config rules and defaults?** → [Configuration Semantics](../concepts/configuration-semantics.md)
- **How does it all work?** → [Concepts](../concepts/index.md)
- **Upgrading from v3?** → [Migration Guide](../migration/index.md)
