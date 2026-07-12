---
title: CMIS Compatibility Matrix
sidebar_label: CMIS Compatibility
---

# CMIS Compatibility Matrix

This page tracks practical compatibility for CMIS sources used with
File System crawler `CmisFetcher`.

Status labels:

- **Verified**: validated by reproducible tests or maintained fixtures.
- **Partial**: known to connect or crawl in limited scenarios, with caveats.
- **Untested**: no current validation in this release cycle.

## Current baseline

The current validated baseline in this repository is a local CMIS Atom test
server exercising Atom 1.0 and Atom 1.1 traversal behavior.

- Test coverage source:
  [CmisAtomFileSystemIT](../../../crawler/fs/src/test/java/com/norconex/crawler/fs/fetch/impl/cmis/CmisAtomFileSystemIT.java)
- Result:
  **Verified** for generic CMIS Atom-boundary crawling behavior in the local
  fixture.

## Product matrix (initial)

| Product / Platform             | CMIS Binding | Auth Mode            | Status   | Notes                                               |
| ------------------------------ | ------------ | -------------------- | -------- | --------------------------------------------------- |
| Generic CMIS fixture (local)   | Atom 1.0/1.1 | Basic (test fixture) | Verified | Backed by integration tests in this repository.     |
| Alfresco Content Services      | Atom         | Basic / SSO          | Untested | Candidate for first external validation run.        |
| OpenText Content Server (CMIS) | Atom         | Basic / SSO          | Untested | Validate endpoint path and ACL/property behavior.   |
| Documentum (CMIS layer)        | Atom         | Basic / SSO          | Untested | Verify object type/property naming variants.        |
| IBM FileNet (CMIS layer)       | Atom         | Basic / SSO          | Untested | Validate folder/document traversal and ACL mapping. |
| Nuxeo (CMIS)                   | Atom         | Basic / SSO          | Untested | Validate query/object-by-path template behavior.    |

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
- Additional product-level verification remains pending for the products listed
  as **Untested**.

## How to update this matrix

When a product is validated, update:

1. Product/version tested.
2. Endpoint pattern used.
3. Auth mode used.
4. Result status (Verified/Partial/Untested).
5. Key caveats and recommended config notes.
