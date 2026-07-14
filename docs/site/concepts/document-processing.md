---
title: Document Processing
---

# Document Processing

After a document is fetched and passes the crawl-stage filters, it enters the
**Importer** — a configurable sub-pipeline responsible for parsing content,
enriching metadata, and reshaping documents before they are committed.

The Importer is a standalone library that can also be used independently of
the crawler to process files directly.

A document entering the Importer has:

- A **reference** (URL or file path)
- **Raw content** (bytes)
- Initial **metadata** (HTTP headers, file system attributes, etc.)

## Handlers

Everything in the Importer is a **handler** — a unit of work applied to a
document. Handlers are configured as an ordered list and executed sequentially.

There are four kinds:

| Kind            | What it does                                                                       |
| --------------- | ---------------------------------------------------------------------------------- |
| **Parser**      | Extracts text and metadata from raw content (PDF, DOCX, HTML, images via OCR, ...) |
| **Transformer** | Modifies, enriches, or removes metadata fields and document content                |
| **Splitter**    | Decomposes one document into multiple logical sub-documents                        |
| **Condition**   | Conditionally executes a nested list of handlers, including `Reject`               |

### Pre- vs post-parse handlers

Parsers convert raw binary content into text. Handlers that operate on
**text or parsed metadata** must run after the parser. Handlers that operate
on **raw bytes or initial metadata** (e.g., filtering by content type before
parsing) can run before it. Check each handler's documentation for when it
can be used.

## Parsers

The default parser is **Apache Tika**, which handles hundreds of document
formats out of the box:

| Format                  | Extracted content                         |
| ----------------------- | ----------------------------------------- |
| HTML, XML               | Text, links, title, meta tags             |
| PDF                     | Text, author, creation date, page count   |
| DOCX, XLSX, PPTX        | Text, author, sheet names                 |
| Images (JPEG, PNG, ...) | EXIF metadata; text via OCR if configured |
| Emails (MSG, EML)       | Subject, sender, body, attachments        |

Custom parsers can be registered for formats Tika does not handle or when
specialized extraction is needed.

## Transformers

Transformers modify a document's metadata or content. They run in the order
they are configured.

Common examples:

| Transformer           | What it does                                           |
| --------------------- | ------------------------------------------------------ |
| `ReplaceTransformer`  | Find/replace in metadata field values                  |
| `CopyTransformer`     | Copy a metadata field to a new name                    |
| `DeleteTransformer`   | Remove unwanted metadata fields                        |
| `ExternalTransformer` | Pipe the document through an external command          |
| `ScriptTransformer`   | Run a JavaScript or Groovy script against the document |

## Splitters

A splitter decomposes a single document into multiple logical sub-documents
before committing.

Useful for:

- Splitting a large HTML page with multiple sections into individual documents
- Extracting each worksheet of an Excel file as a separate document
- Processing email attachments independently from the email body

## Conditions and document rejection

Conditions wrap a nested list of handlers and execute them only when a
specified criterion is met. This is how the Importer controls branching and
document rejection.

To **discard a document** inside the Importer, place a `Reject` handler inside
a condition body. `Reject` is a no-op handler whose only effect is to stop
processing and drop the document from the crawl.

```yaml
handlers:
  - condition:
      class: TextCondition
      fieldMatcher:
        pattern: title
      valueMatcher:
        pattern: A Page To Exlude
    handlers:
      - class: Reject
```

Conditions can also be used without `Reject` to apply a handler only to a
subset of documents — for example, running a specialized parser only on PDFs,
or enriching metadata only for documents from a specific domain.

## Configuration

All Importer options are described in the [Reference](/docs/reference/) section.
The [Visual Configurator](https://configurator.norconex.com.com) lets you explore
and configure handlers visually with inline documentation and live examples.
