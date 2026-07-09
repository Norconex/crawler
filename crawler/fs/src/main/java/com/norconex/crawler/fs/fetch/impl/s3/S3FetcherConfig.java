/* Copyright 2023-2026 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.crawler.fs.fetch.impl.s3;

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link S3Fetcher}.
 * </p>
 * <p>
 * Credentials are optional: when not set, the AWS SDK's default credential
 * provider chain is used instead (environment variables,
 * {@code ~/.aws/credentials}, an EC2/ECS/Lambda IAM role, etc.) - the
 * normal way AWS-hosted workloads authenticate without embedding keys in
 * configuration.
 * </p>
 */
@Data
@Accessors(chain = true)
public class S3FetcherConfig extends BaseAuthNioFetcherConfig {

    /**
     * The AWS region the bucket lives in (e.g. {@code us-east-1}). If not
     * set, the AWS SDK's default region provider chain is used.
     */
    private String region;

    /**
     * Custom endpoint URL, for S3-compatible services (e.g. MinIO, Ceph)
     * rather than real AWS S3.
     */
    private String endpoint;

    /**
     * Whether to use path-style bucket addressing
     * ({@code endpoint/bucket/key}) instead of the AWS-default
     * virtual-hosted style ({@code bucket.endpoint/key}). Most
     * S3-compatible services (e.g. MinIO) require this to be enabled.
     */
    private boolean forcePathStyle;
}
