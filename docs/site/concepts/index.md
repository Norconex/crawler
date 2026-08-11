---
title: Concepts
sidebar_label: Overview
slug: /concepts
---

# Concepts

This section explains how Norconex Crawler works conceptually.
Understanding these ideas will help you configure and extend the crawler effectively.

## Topics in this section

| Topic                                                       | What it covers                                           |
| ----------------------------------------------------------- | -------------------------------------------------------- |
| [Crawl Pipeline](./crawl-pipeline.md)                       | How documents move from source to destination            |
| [Crawler Flow](./crawl-flow.md)                             | The same journey stage by stage, in interactive diagrams |
| [Sessions](./sessions.md)                                   | Resumable crawl state, deduplication, and recrawl policy |
| [Configuration Semantics](./configuration-semantics.md)     | Defaults, null/empty behavior, variables, and fragments  |
| [Document Processing](./document-processing.md)             | The Import module: parsing, enrichment, metadata         |
| [Extending the Crawler](./extending.md)                     | Custom components, SPI, event listeners                  |

## The big picture

```mermaid
%% No subgraphs here on purpose. Mermaid discards a subgraph's `direction` as
%% soon as anything inside it connects to anything outside it, so wrapping the
%% sources and destinations in "Source" / "Destination" boxes forced their
%% contents side by side and made the diagram far wider than it needed to be.
%% Without the boxes, plain rank layout applies: in an LR flowchart, nodes
%% sharing a rank stack vertically, which is what the columns below rely on.
flowchart LR
  web["Websites<br/>HTTP and HTTPS"] --> crawl["Crawl"]
  files["Files<br/>disks, shares,<br/>cloud storage"] --> crawl

  crawl --> process["Process"] --> commit["Commit"]

  commit --> es["Elasticsearch"]
  commit --> solr["Apache Solr"]
  commit --> more["Kafka, SQL,<br/>Neo4j, ..."]

  %% Roles, coloured by src/theme/Mermaid/diagram-roles.css. Tagged with no
  %% matching classDef on purpose — see that file.
  class web,files source
  class es,solr,more destination
```

Raw content enters from the left (amber), moves through the crawler's own
stages (blue), and lands structured in your destinations (green).

Every document — whether a web page or a file — passes through the same
three-stage pipeline. The crawl type (Web vs. File System) only affects the
**Crawl** stage. Everything downstream is identical.

## Configuration model

All configuration lives in a single file (XML, YAML, or JSON).

- [Reference](/docs/reference/) — all built-in extension points with examples
- The [Visual Configurator](https://configurator.norconex.com) provides a visual way to build and validate configs.

## File System Fetcher Start References

For filesystem crawling, the most common first issue is choosing the right
start reference scheme for the fetcher you want to use.

Use [FS Fetchers Quickstart](../getting-started/fs-fetchers-quickstart.md)
for a compact table covering all supported filesystem fetchers, including
start reference examples and the first configuration step for each.
