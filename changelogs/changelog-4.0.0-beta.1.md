# Version 4.0.0-beta.1

Release Date: 2026-08-29

## Overview

This is the first public beta of Norconex Crawler V4 — a ground-up rewrite of
the open-source web and file-system crawlers. It is offered for early
adopters to try out and provide feedback. Interfaces, configuration formats,
and packaging may still change before the stable 4.0.0 release.

## Highlights

- Official Docker images for the web and file-system crawlers, published to
  Docker Hub and GHCR (`norconex/crawler-web`, `norconex/crawler-fs`, and a
  `norconex/crawler-web-playwright` variant with browser-based rendering
  support).
- A unified importer pipeline: parsers, taggers, transformers, and splitters
  are now configured as a single ordered list of handlers, replacing V3's
  separate pre-parse/parse/post-parse stages.
- Modular committers, each independently packaged and versioned.
- A new online configurator (beta) to build and export crawler
  configurations from a browser, including a V3-to-V4 configuration
  converter for existing users.
- JDK 21 baseline.

## Migrating from V3

Crawler V4 configuration files are not compatible as-is with V3 — they
require conversion. See the migration documentation, or use the online
configurator's import tool to convert an existing V3 configuration.

## Known Limitations

- This is a beta release: expect rough edges, incomplete documentation in
  places, and the possibility of breaking changes before 4.0.0 is final.
- Feedback is very welcome — please open an issue on GitHub.
