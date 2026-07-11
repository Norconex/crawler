---
title: File System Fetchers Quickstart
---

# File System Fetchers Quickstart

This page is a fast chooser for File System crawler fetchers.

Use it to answer the first question that matters most:
which start reference format does this source require?

For full configuration options, authentication details, and advanced examples,
use each fetcher's reference page.

## Start references by fetcher

| Fetcher          | Accepted start reference format                     | Minimal start reference example                                      | First thing to configure next                                |
| ---------------- | --------------------------------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------ |
| LocalFetcher     | Absolute path, UNC path, or `file:` URL             | `C:/data/docs` or `file:///data/docs`                                | Usually nothing beyond `startReferences`                     |
| ArchiveFetcher   | Archive-prefixed reference wrapping an inner source | `zip:file:///C:/data/archive.zip!/`                                  | Keep the archive prefix and include `!/`                     |
| FtpFetcher       | `ftp://` or `ftps://`                               | `ftp://files.example.com/corpus/`                                    | Add credentials for protected servers                        |
| SftpFetcher      | `sftp://`                                           | `sftp://files.example.com/corpus/`                                   | Add credentials and host key settings                        |
| SmbFetcher       | `smb://`                                            | `smb://server/share/corpus/`                                         | Add credentials and `domain` when needed                     |
| WebDavFetcher    | `webdav://`, `webdavs://`, `http://`, or `https://` | `https://dav.example.com/remote.php/dav/files/user/`                 | Add credentials and any TLS/proxy settings                   |
| CmisFetcher      | `cmis:`                                             | `cmis:https://repo.example.com/browser`                              | Add credentials and optional `repositoryId`                  |
| HdfsFetcher      | `webhdfs://`                                        | `webhdfs://namenode.example.com:9870/user/data/`                     | Choose `authMethod` (`SIMPLE` or `KERBEROS`)                 |
| S3Fetcher        | `s3://`                                             | `s3://my-bucket/corpus/`                                             | Set `endpoint` and `forcePathStyle` for S3-compatible stores |
| GcsFetcher       | `gs://`                                             | `gs://my-bucket/corpus/`                                             | Optionally set `endpoint` for local/emulated environments    |
| M365GraphFetcher | `m365sp://` or `m365od://`                          | `m365sp://tenant/sites/{siteId}` or `m365od://tenant/users/{userId}` | Set `tenantId`, `clientId`, and `clientSecret`               |
| AzureBlobFetcher | `azblob://` or `azureblob://`                       | `azblob://myaccount/mycontainer/corpus/`                             | Use shared-key credentials or `sasToken`                     |
| AdlsGen2Fetcher  | `abfs://` or `abfss://`                             | `abfss://filesystem@account.dfs.core.windows.net/corpus/`            | Use shared-key credentials or `sasToken`                     |

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
5. Starting M365 delta mode from a site or user discovery reference when you need stored delta cursors. Use a drive boundary such as `m365sp://tenant/sites/{siteId}/drives/{driveId}` or `m365od://tenant/users/{userId}/drives/{driveId}` together with crawler-level `changeDiscovery: SOURCE_DELTA` and incremental crawl mode.

## Next step

After your first successful crawl with `LogCommitter`, switch to your target
committer and tune fetcher-specific options in the reference docs.
