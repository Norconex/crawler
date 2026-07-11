---
title: File System Fetchers Quickstart
---

# File System Fetchers Quickstart

This page is a fast chooser for File System crawler fetchers.

Use it to answer the first question that matters most:
which start reference format does this source require?

For full configuration options, authentication details, and advanced examples,
use each fetcher's reference page.

:::tip[Built-in baseline, not a hard limit]
The fetchers and source schemes listed here represent built-in support in
Norconex Crawler v4. They are practical defaults, not a fixed ceiling.
Teams can extend crawler behavior with custom connectors and pipeline
components for customer-specific source requirements.
:::

## Start references by fetcher

| Fetcher            | Accepted start reference format                     | Minimal start reference example                                                      | First thing to configure next                                   |
| ------------------ | --------------------------------------------------- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| LocalFetcher       | Absolute path, UNC path, or `file:` URL             | `C:/data/docs` or `file:///data/docs`                                                | Usually nothing beyond `startReferences`                        |
| ArchiveFetcher     | Archive-prefixed reference wrapping an inner source | `zip:file:///C:/data/archive.zip!/`                                                  | Keep the archive prefix and include `!/`                        |
| FtpFetcher         | `ftp://` or `ftps://`                               | `ftp://files.example.com/corpus/`                                                    | Add credentials for protected servers                           |
| SftpFetcher        | `sftp://`                                           | `sftp://files.example.com/corpus/`                                                   | Add credentials and host key settings                           |
| SmbFetcher         | `smb://`                                            | `smb://server/share/corpus/`                                                         | Add credentials and `domain` when needed                        |
| WebDavFetcher      | `webdav://`, `webdavs://`, `http://`, or `https://` | `https://dav.example.com/remote.php/dav/files/user/`                                 | Add credentials and any TLS/proxy settings                      |
| CmisFetcher        | `cmis:`                                             | `cmis:https://repo.example.com/browser`                                              | Add credentials and optional `repositoryId`                     |
| HdfsFetcher        | `webhdfs://`                                        | `webhdfs://namenode.example.com:9870/user/data/`                                     | Choose `authMethod` (`SIMPLE` or `KERBEROS`)                    |
| S3Fetcher          | `s3://`                                             | `s3://my-bucket/corpus/`                                                             | Set `endpoint` and `forcePathStyle` for S3-compatible stores    |
| GcsFetcher         | `gs://`                                             | `gs://my-bucket/corpus/`                                                             | Optionally set `endpoint` for local/emulated environments       |
| GoogleDriveFetcher | `gdrive://`                                         | `gdrive://workspace/users/alex@example.com` or `gdrive://workspace/drives/{driveId}` | Set `clientEmail`, `privateKey`, and optionally `delegatedUser` |
| M365GraphFetcher   | `m365sp://` or `m365od://`                          | `m365sp://tenant/sites/{siteId}` or `m365od://tenant/users/{userId}`                 | Set `tenantId`, `clientId`, and `clientSecret`                  |
| AzureBlobFetcher   | `azblob://` or `azureblob://`                       | `azblob://myaccount/mycontainer/corpus/`                                             | Use shared-key credentials or `sasToken`                        |
| AdlsGen2Fetcher    | `abfs://` or `abfss://`                             | `abfss://filesystem@account.dfs.core.windows.net/corpus/`                            | Use shared-key credentials or `sasToken`                        |

## Minimal template

```yaml
id: my-fs-crawl
startReferences:
  - <put-your-start-reference-here>
committers:
  - class: LogCommitter
    ignoreContent: true
```

## Common mistakes to avoid

1. Using the right host but the wrong scheme (for example `hdfs://` instead of `webhdfs://`).
2. Omitting trailing slash on folder-like start references when the server expects one.
3. Forgetting provider-specific endpoint settings for S3-compatible stores.
4. Mixing account names in URI and credentials for Azure fetchers.
5. Starting M365 delta mode without matching the fetcher expansion policy to your start references. Use a drive boundary such as `m365sp://tenant/sites/{siteId}/drives/{driveId}` or `m365od://tenant/users/{userId}/drives/{driveId}` with `changeDiscovery: SOURCE_DELTA`, or keep site/user start references and set `sourceDeltaExpansion: INCLUDE_CHILD_DRIVES`.
6. Expecting an entire missing drive to require source-side per-item tombstones. In `SOURCE_DELTA` mode, if a drive boundary itself becomes `NOT_FOUND`, the crawler will re-queue previously known descendants from baseline so committers can receive delete requests for those known items.
7. Expecting Google-native Docs, Sheets, or Slides to download like regular binary files. Google Drive fetches those through export formats; use `nativeDocumentFormatPolicy` and `exportMimeTypeMap` when the default export type is not what you want.
8. Starting Google Drive delta mode from an item reference. In `SOURCE_DELTA` mode, use a user boundary such as `gdrive://workspace/users/alex@example.com` or a Shared Drive boundary such as `gdrive://workspace/drives/{driveId}`.

## Next step

After your first successful crawl with `LogCommitter`, switch to your target
committer and tune fetcher-specific options in the reference docs.
