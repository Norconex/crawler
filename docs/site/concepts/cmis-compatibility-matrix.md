---
title: CMIS Compatibility Matrix
sidebar_label: CMIS Compatibility
---

# CMIS Compatibility Matrix

This page tracks practical compatibility for CMIS sources used with
File System crawler `CmisFetcher`.

Status labels:

- **Verified**: validated by reproducible tests or maintained fixtures.
- **Partial**: API/endpoints are documented and testable, but not yet validated in
  this repository against that product runtime.
- **Untested**: no product-specific validation signal captured in this release
  cycle.

## Current baseline

The current validated baseline in this repository is a local CMIS Atom test
server exercising Atom 1.0 and Atom 1.1 traversal behavior.

- Test coverage source:
  [CmisAtomFileSystemIT](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisAtomFileSystemIT.java)
- Result:
  **Verified** for generic CMIS Atom-boundary crawling behavior in the local
  fixture.
- Focused test run command:
  `mvn test -pl crawler/fs "-Dtest=CmisAtomFileSystemIT,CmisAtomSessionTest,CmisFetcherTest"`
- Focused test run result:
  **BUILD SUCCESS** with `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Product matrix (initial)

| Product / Platform             | CMIS Binding   | Auth Mode            | Status   | Validation route now                                                                                      |
| ------------------------------ | -------------- | -------------------- | -------- | --------------------------------------------------------------------------------------------------------- |
| Generic CMIS fixture (local)   | Atom 1.0/1.1   | Basic (test fixture) | Verified | Verified via in-repo runtime tests and local mock server.                                                 |
| Nuxeo (CMIS)                   | Atom / Browser | Basic / SSO          | Partial  | Public docs provide concrete CMIS endpoints and query examples; execute external runtime validation next. |
| Alfresco Content Services      | Atom           | Basic / SSO          | Verified | Verified from client v3 CMIS production usage; run a short v4 confirmation pass for release evidence.     |
| OpenText Content Server (CMIS) | Atom           | Basic / SSO          | Untested | Validate endpoint path conventions, ACL payloads, and property naming at runtime.                         |
| Documentum (CMIS layer)        | Atom           | Basic / SSO          | Untested | Validate object type/property mapping and repository path behavior.                                       |
| IBM FileNet (CMIS layer)       | Atom           | Basic / SSO          | Untested | Validate folder/document traversal, ACL translation, and auth gateway behavior.                           |

## What is mockable today

The following are already mockable in this repository without external product
instances:

1. CMIS Atom service document and repository metadata.
2. Object-by-path and children traversal feeds.
3. Content stream fetches.
4. Property extraction edge cases (multi-value, missing property IDs).
5. ACL extraction edge cases (principal fallback and dedupe behavior).

Key in-repo assets:

- [CmisTestServer](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisTestServer.java)
- [CmisAtomFileSystemIT](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisAtomFileSystemIT.java)
- [CmisAtomSessionTest](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisAtomSessionTest.java)
- [CmisFetcherTest](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisFetcherTest.java)

## Validating without local product installs

You do not need every DMS installed on your workstation. Use a layered approach:

1. Contract validation in this repository (already available)

- Keep using the in-repo CMIS test server and focused tests to validate parser,
  traversal, ACL, and metadata behavior.
- This catches most fetcher logic regressions before any vendor runtime is
  involved.

2. Remote environment validation (preferred for commercial products)

- Run validation against a hosted vendor sandbox, customer dev environment, or
  shared QA stack.
- Only collect non-secret evidence: endpoint pattern, auth mode type,
  representative payload shape, and observed behavior.
- Record outcomes in this matrix as Verified or Partial with caveats.

3. Mock-response replay when runtime access is limited

- Capture sanitized CMIS Atom responses from a real environment (service doc,
  object-by-path, children feed, content metadata, ACL fragments).
- Replay those payloads via lightweight HTTP stubs to exercise
  CmisFetcher/CmisAtomSession parsing and mapping paths.
- Mark this as Partial unless traversal/content behavior is also confirmed on a
  live runtime.

4. CI-driven connectivity checks (no desktop installs)

- Execute small validation jobs in CI runners that can reach target endpoints.
- Use read-only technical accounts scoped to a small repository boundary.
- Publish pass/fail plus caveat notes as matrix updates.

### Minimum evidence to promote a product row

Promote Untested to Partial when all are true:

1. Endpoint reachable with expected auth mode.
2. Service document and repository selection succeed.
3. One folder traversal and one document metadata fetch succeed.

Promote Partial to Verified when all are true:

1. Repeatable crawl run against representative structure succeeds.
2. ACL and metadata mapping quality is reviewed for expected principals/fields.
3. Failure handling is checked for at least one auth/path edge case.

### Data handling rules for external validation

1. Do not store credentials in repo docs or tests.
2. Sanitize hostnames, usernames, IDs, and document titles in shared artifacts.
3. Keep captured payloads minimal and free of sensitive business content.

Use this runbook for each product validation cycle:

- [CMIS Validation Template](./cmis-validation-template.md)

## External documentation signals used

- Nuxeo CMIS docs expose concrete Browser and Atom endpoints plus query examples:
  `http://localhost:8080/nuxeo/json/cmis` and
  `http://localhost:8080/nuxeo/atom/cmis`.
- OpenCMIS project docs confirm CMIS 1.0/1.1 client/server tooling and test
  tooling availability for Java ecosystems.

## Known compatibility focus areas

1. Property payload variation

- Multi-value properties and property elements missing `propertyDefinitionId`.

2. ACL payload variation

- Principal location/name differences and duplicate permission entries.

3. Endpoint template quirks

- Product-specific `objectByPath` and `query` URI template behavior.

4. Auth and gateway behavior

- 401/403 handling behind SSO/reverse proxies.

## Current hardening status

- Parser robustness improvements for property and ACL extraction were added in
  this cycle.
- Additional product-level verification remains pending for product rows still
  marked as **Untested**; Alfresco is currently treated as legacy-verified from
  v3 usage, with a recommended v4 confirmation run.

## How to update this matrix

When a product is validated, update:

1. Product/version tested.
2. Endpoint pattern used.
3. Auth mode used.
4. Result status (Verified/Partial/Untested).
5. Key caveats and recommended config notes.
