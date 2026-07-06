---
title: Configuration Semantics
---

# Configuration Semantics

This page explains how configuration values are interpreted by Norconex Crawler.
It complements the [Reference](/docs/reference/) pages, which document
individual components and options.

Use this page for rules that apply across many settings, such as defaults,
null handling, variables, and fragments.

## Scope and formats

Norconex supports XML, YAML, and JSON configuration files.
Most semantics are shared, but a few are format-specific.

| Topic                                              | XML                 | YAML         | JSON         |
| -------------------------------------------------- | ------------------- | ------------ | ------------ |
| Variable resolution                                | Yes                 | Yes          | Yes          |
| Velocity directives (`#set`, `#parse`, `#include`) | Yes                 | Yes          | Yes          |
| Class short-name resolution                        | Yes                 | Yes          | Yes          |
| Default values when option is omitted              | Yes                 | Yes          | Yes          |
| Self-closing element means `null`                  | Yes                 | N/A          | N/A          |
| Explicit `null` value                              | Indirect (`<tag/>`) | Yes (`null`) | Yes (`null`) |

:::tip[V3 XML to V4 format mapping]
If you are migrating from V3 XML-centric docs:

- V3 `<value/>` (null) maps to YAML/JSON `value: null`.
- V3 `<value></value>` (empty string) maps to YAML/JSON `value: ""`.
- V3 omitted element (keep default) maps to omitted key in YAML/JSON.
- V3 empty list sections (for example `<linkExtractors/>`) map to `[]` in YAML/JSON.

The behavior is the same; only the syntax differs by format.
:::

## Classes and default implementations

Many extension points map to Java classes (for example filters, handlers,
fetchers, committers).

- You can typically reference a class by fully-qualified name.
- Some built-ins can also be referenced by short name when registered in the
  polymorphic type provider registry.
- Some options have a documented default implementation. If that option is
  omitted, the default applies.

When an option has a default class, setting the option to `null` disables that
specific default behavior.

## Defaults and omission rules

Unless documentation says otherwise:

- Omitting an option means "use its default value".
- Boolean flags are usually `false` by default.
- Omitting a section is not always the same as providing an empty section.

Always check the option documentation in [Reference](/docs/reference/) for
explicit defaults.

## Null, empty, and absent values

The distinction between null, empty, and absent values matters.

### XML

- `<value>text</value>`: value is `"text"`
- `<value></value>`: value is empty string
- `<value/>`: value is `null`
- no `<value>` element: value is not set (default remains)

### YAML / JSON

- `value: ""` or `"value": ""`: empty string
- `value: null` or `"value": null`: null
- missing key: not set (default remains)

## Empty list vs omitted list

For list-valued settings, these often differ:

- Omitted list: keep defaults (if defaults exist)
- Explicit empty list: clear list (use no entries)

Examples:

```yaml
# Omitted list: defaults apply
# linkExtractors: <not present>

# Explicit empty list: no extractors
linkExtractors: []
```

```xml
<!-- Omitted list: defaults apply -->
<!-- <linkExtractors> not present -->

<!-- Explicit empty list: no extractors -->
<linkExtractors/>
```

## Variables and resolution order

Configuration files are processed as templates before final binding. You can
parameterize values for environment portability and reuse.

Common sources:

- JVM system properties (`-D...`)
- Environment variables
- `.properties` variables file
- `.variables` variables file

When the same variable exists in multiple sources, precedence is:

1. System properties
2. Environment variables
3. `.properties` file
4. `.variables` file

## Fragments and reuse

For large configurations, split reusable parts into fragments and include them
with template directives.

- `#parse(...)` includes and parses a fragment as a template.
- `#include(...)` includes raw content without parsing.

Use fragments for shared handler chains, common filters, and environment
profiles.

## Practical guidance

- Prefer omission when you want defaults.
- Use explicit null/empty only when you intend to override defaults.
- Keep reusable blocks in parsed fragments.
- Keep environment-specific values in variables, not inline literals.

## Cross-format examples

These examples show the same intent expressed in XML, YAML, and JSON.

### Example 1: value set vs empty vs null vs omitted

Assume an option `apiKey` with a default value.

Value set:

```xml
<apiKey>abc123</apiKey>
```

```yaml
apiKey: "abc123"
```

```json
{ "apiKey": "abc123" }
```

Empty string:

```xml
<apiKey></apiKey>
```

```yaml
apiKey: ""
```

```json
{ "apiKey": "" }
```

Null:

```xml
<apiKey/>
```

```yaml
apiKey: null
```

```json
{ "apiKey": null }
```

Omitted (keep default):

```xml
<!-- no <apiKey> element -->
```

```yaml
# apiKey not present
```

```json
{}
```

### Example 2: list omitted vs explicitly empty

Assume a list option with defaults, such as `linkExtractors`.

Omitted list (defaults remain):

```xml
<!-- no <linkExtractors> element -->
```

```yaml
# linkExtractors not present
```

```json
{}
```

Explicit empty list (clear defaults):

```xml
<linkExtractors/>
```

```yaml
linkExtractors: []
```

```json
{ "linkExtractors": [] }
```

### Example 3: variables and parsed fragments

This pattern keeps environment values out of the main config and reuses shared
sections.

```xml
<!-- main.xml -->
<crawler id="${crawlerId|'my-crawler'}">
  #parse("fragments/common-committers.xml")
</crawler>
```

```yaml
# main.yaml
crawler:
  id: ${crawlerId|'my-crawler'}
  #parse("fragments/common-committers.yaml")
```

```json
{
  "crawler": {
    "id": "${crawlerId|'my-crawler'}"
    #parse("fragments/common-committers.json")
  }
}
```

In all three formats, the variable is resolved before binding, and `#parse(...)`
inlines the referenced fragment as a parsed template.

For concrete option-level behavior, always cross-check the component docs in
[Reference](/docs/reference/).
