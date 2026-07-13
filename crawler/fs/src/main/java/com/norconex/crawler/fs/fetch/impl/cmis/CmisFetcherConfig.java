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
package com.norconex.crawler.fs.fetch.impl.cmis;

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link CmisFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class CmisFetcherConfig extends BaseAuthNioFetcherConfig {

    /**
     * The CMIS repository ID. Defaults to first one found.
     */
    private String repositoryId;
    /**
     * The name of the field where the raw XML obtained from
     * the CMIS REST API will be stored. Defaults to {@code null}
     * (does not store the raw XML in a field).
     */
    private String xmlTargetField;

    private boolean aclDisabled;

}
