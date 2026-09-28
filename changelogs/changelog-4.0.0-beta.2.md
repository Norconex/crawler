# Version 4.0.0-beta.2

Release Date: 2026-09-29

## Overview

Second beta of Norconex Crawler V4. This release hardens what beta.1
shipped based on the first few weeks of real use, and adds the first
building blocks for feeding AI, RAG, and vector search pipelines directly
from the crawl. No configuration changes are required to upgrade from
beta.1.

## Security

- **Fixed:** the cluster administrative server — which starts with every
  crawl, not only clustered ones — bound to every network interface by
  default. Its stop endpoint is guarded only by a crawler ID, which is a
  configuration value, not a secret, so anyone able to reach the port
  could halt a crawl. It now binds to loopback only by default. Clustered
  deployments that need to reach it from another host can set
  `cluster.adminBindAddress: any` (or a specific address) explicitly; the
  crawler logs a warning whenever it binds beyond loopback so this can't
  pass unnoticed.

## Added

- Two new Importer handlers for preparing content for AI: `TextChunkSplitter`
  cuts long documents into pieces sized for embedding models, breaking at
  paragraph or sentence boundaries rather than mid-word. `TextEmbeddingTransformer`
  calls any OpenAI-compatible embeddings endpoint (OpenAI, a local Ollama,
  a LiteLLM proxy, vLLM, and others sharing that request shape) and stores
  the resulting vector as a document field, ready for a vector-capable
  index such as Elasticsearch, OpenSearch, or Solr. Results are cached by
  a hash of the text, model, and endpoint, so the same content is never
  billed twice — whether it recurs on a later crawl or as boilerplate
  shared by many pages in the same crawl.
- File System Crawler: added runnable examples, and it now defaults to
  `LocalFetcher` so a minimal configuration works out of the box.
- Crawlers can write a machine-readable run summary on request, for
  whoever launched the JVM rather than assuming a fixed location.

## Improved

- Event-count reporting now accounts for every event type across a whole
  crawl session, not a fixed subset, and resets correctly when a new
  session starts instead of carrying over totals from the last one.
- The Elasticsearch/OpenSearch committer's `jsonFieldsPattern` now matches
  correctly against field names. This matters for embeddings: a vector
  field listed in the pattern is sent as JSON numbers instead of a quoted
  string, which is what a dense-vector field expects.

## Fixed

- A null pointer fetching a site's `robots.txt`/sitemap could crash a
  crawl.
- Orphan handling: references that were never actually committed are no
  longer deleted as orphans, and orphans are no longer deleted at all when
  a crawl ends early — both could previously cause documents to be
  dropped from your index that were never confirmed as gone.
- Fixed a `CrawlerRunInfo` deserialization issue.
- Fixed the File System Crawler's launcher classpath.

## Upgrading from beta.1

No configuration changes are required. If you run a clustered deployment
where the admin server must be reachable from another host, set
`cluster.adminBindAddress` explicitly — see the `ClusterConfig` reference.

## Known Limitations

This is still a beta: expect rough edges, and the possibility of breaking
changes before 4.0.0 is final. Feedback is very welcome — please use the
[Discussions](https://github.com/Norconex/crawler/discussions) "Start
here" post to report bugs, ask questions, or share ideas.
