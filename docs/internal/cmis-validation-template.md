---
title: CMIS Validation Template
sidebar_label: CMIS Validation Template
---

# CMIS Validation Template

Use this template to run and record product-specific CMIS validation without
requiring local desktop installs of vendor software.

## Scope

- Product:
- Product version:
- Environment type: sandbox / customer-dev / customer-qa / other
- Validation date:
- Validator:

## Access and security

- Endpoint base URL (sanitized if needed):
- Binding used: Atom / Browser
- Auth mode: Basic / SSO / Token / Other
- Read-only technical account used: Yes / No
- Sensitive data redaction completed: Yes / No

## Pre-checks

1. Endpoint reachability

- Command or probe used:
- Result: Pass / Fail
- Notes:

2. Service document/repository discovery

- Repository ID selected:
- Result: Pass / Fail
- Notes:

## Functional checks

1. Root or boundary traversal

- Start reference used:
- Result: Pass / Fail
- Caveats:

2. Document metadata extraction

- Sample object reference:
- Result: Pass / Fail
- Metadata caveats:

3. ACL extraction behavior

- Sample secured object reference:
- Result: Pass / Fail
- ACL caveats:

4. Content stream fetch behavior

- Sample document reference:
- Result: Pass / Fail
- Caveats:

## Error-path checks

1. Auth failure behavior (401/403)

- Trigger method:
- Result: Pass / Fail
- Notes:

2. Missing path/object behavior (404)

- Trigger method:
- Result: Pass / Fail
- Notes:

## Evidence captured

- Sanitized request/response samples saved: Yes / No
- Log excerpts saved: Yes / No
- Matrix row updated: Yes / No
- Reference docs updated (if needed): Yes / No

## Classification outcome

- Proposed matrix status: Untested / Partial / Verified
- Why this status:

Promotion guidance:

- Promote to Partial when endpoint/auth/repository discovery and at least one
  traversal plus metadata fetch are successful.
- Promote to Verified when repeatable runs succeed and ACL/metadata/error-path
  behavior has been reviewed.

## Follow-up actions

1. Next fix or hardening item:
2. Additional scenario to validate:
3. Owner:
4. Target date:
