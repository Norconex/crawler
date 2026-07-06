---
title: Crawl Sessions
---

# Crawl Sessions

A **crawl session** is a single named run of the crawler, backed by persistent
on-disk state. Sessions are what give Norconex Crawler its enterprise
reliability characteristics: you can stop and resume crawls,
and run them repeatedly over time,
without losing progress or recrawling everything.

## Session identity

Every crawler configuration has an `id` field.
This ID is used as the name of the session state directory under the
configured `workDir`.

```yaml
id: acme-website # ← session identity
workDir: /var/crawler/state
startReferences:
  - https://www.example.com
```

Two configs with the same `id` and `workDir` share the same session state.
Changing the `id` or deleting the `workDir` starts a fresh crawl.

## Start, stop, and resume

| CLI command | Effect                                                       |
| ----------- | ------------------------------------------------------------ |
| `start`     | Begin or resume a session                                    |
| `stop`      | Gracefully stop — in-flight documents finish, state is saved |
| `clean`     | Delete session state — next `start` is a full recrawl        |

When you `stop` and then `start` again, the crawler picks up from where it left off:
unvisited references remain in the queue, already-committed documents are not re-committed.

## Deduplication

The crawler tracks every document it has processed in the session store.
On subsequent crawls, it detects whether a document has changed using
techniques such as:

- **Checksum-based** (default): compare a hash of the document's content or metadata
- **Modified date**: compare the `Last-Modified` HTTP header or file system timestamp
- **ETag**: use HTTP ETags for web resources

Unchanged documents are skipped. Only new or modified documents are committed.
Deleted documents (no longer reachable) can optionally trigger a delete event
on the committer.

## Crawl run scheduling (external)

A session runs once and exits. There is no built-in scheduler. Use an
external scheduler — cron, a systemd timer, a Kubernetes CronJob, or any
similar tool — to invoke `crawl-web.sh start` (or `crawl-fs.sh start`) on a
schedule. The persistent session state ensures only new or changed documents
are processed on each run.

### Controlling re-crawl eligibility (Web Crawler only)

On repeat crawl runs, the web crawler can skip documents that are not yet
ready to be re-crawled. This is controlled by the `recrawlableResolver`
setting. Documents the resolver marks as not ready are skipped entirely —
no HTTP request is made and they are not committed.

This is a **recrawl eligibility/timing policy**, not a job scheduler. It only
decides whether a given document should be fetched again during a run that has
already started.

The default resolver, `GenericRecrawlableResolver`, supports two mechanisms:

- **Sitemap directives** — reads `changefreq` and `lastmod` from `sitemap.xml`
  to decide recrawl eligibility (enabled by default, checked first).
- **Minimum frequencies** — define per-URL-pattern or per-content-type
  minimums using values like `daily`, `weekly`, `monthly`, or a millisecond count.

```yaml
recrawlableResolver:
  class: GenericRecrawlableResolver
  sitemapSupport: FIRST # FIRST (default), LAST, or NEVER
  minFrequencies:
    - applyTo: REFERENCE
      matcher:
        pattern: ".*\\.pdf$"
      value: weekly
    - applyTo: REFERENCE
      matcher:
        pattern: ".*"
      value: daily
```

The File System Crawler has no equivalent — all reachable documents are
evaluated on every run (deduplication still skips unchanged ones).

## State storage

By default, session state is stored in an embedded key-value store in the
`workDir`. For clustered deployments, the state backend can be replaced with
a distributed store (e.g., Hazelcast, JDBC).
See the [Configuration Reference](/docs/reference/) for storage backends.
