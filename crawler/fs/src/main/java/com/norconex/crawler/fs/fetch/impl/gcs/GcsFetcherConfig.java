/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.gcs;

import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link GcsFetcher}.
 * </p>
 * <p>
 * Authentication is delegated to the official Google Cloud NIO provider,
 * which uses the standard Google Application Default Credentials chain.
 * When an endpoint is set, the fetcher assumes an emulator or local test
 * server and connects without authentication.
 * </p>
 */
@Data
@Accessors(chain = true)
public class GcsFetcherConfig extends BaseFetcherConfig {

    /**
     * Optional endpoint override, mainly intended for GCS-compatible test
     * servers such as fake-gcs-server.
     */
    private String endpoint;
}
