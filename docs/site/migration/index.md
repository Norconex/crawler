---
title: Migration Guide
sidebar_label: Overview
slug: /migration
---

# Migrating from v3 to v4

Norconex Crawler v4 is a significant evolution of the v3 series.
The core concepts are the same, but the configuration format, class names,
and Java API have changed substantially.

## The fastest migration path

The [Visual Configurator](https://crawlerconfig.norconex.com) is the
recommended way to migrate:

1. Open the Editor
2. Paste your existing v3 config (XML or YAML)
3. The editor identifies v3 patterns and guides you to their v4 equivalents
4. Export your new v4 config in XML, YAML, or JSON

This is significantly faster than manual migration for large configurations.

## What changed at a high level

| Area                   | v3                                      | v4                                    |
| ---------------------- | --------------------------------------- | ------------------------------------- |
| **Config format**      | XML-first                               | XML, YAML, and JSON equally supported |
| **Module naming**      | `nx-collector-*`                        | `nx-crawler-*`                        |
| **Config structure**   | One file, multiple crawlers + collector | One file = one crawler                |
| **Java API**           | Collector/Crawler split                 | Unified `WebCrawler` / `FsCrawler`    |
| **Config class names** | Fully qualified with legacy names       | Simplified, consistent naming         |
| **Event system**       | Custom listener interfaces              | Standard event bus                    |
| **Committers**         | Separate repository                     | Same mono-repo                        |

## Detailed migration steps

See [v3 to v4 Detailed Guide](./v3-to-v4.md) for a field-by-field migration reference.

For cross-format configuration behavior (null vs empty, omitted defaults,
variables, fragments), see [Configuration Semantics](../concepts/configuration-semantics.md),
including the V3 mapping callout.

## Need help?

- Open a [GitHub Discussion](https://github.com/Norconex/crawlers/discussions) with your v3 config
- Check [Issues](https://github.com/Norconex/crawlers/issues) for known migration edge cases
