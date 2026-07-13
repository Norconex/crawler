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
package com.norconex.crawler.fs.fetch.impl.egnyte;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hc.core5.http.HttpStatus;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Configuration for {@link EgnyteFetcher}.
 */
@Data
@Accessors(chain = true)
public class EgnyteFetcherConfig extends BaseFetcherConfig {

    public static final List<Integer> DEFAULT_VALID_STATUS_CODES =
            CollectionUtil.unmodifiableList(
                    HttpStatus.SC_OK,
                    HttpStatus.SC_CREATED,
                    HttpStatus.SC_ACCEPTED,
                    HttpStatus.SC_NO_CONTENT);

    public static final List<Integer> DEFAULT_NOT_FOUND_STATUS_CODES =
            CollectionUtil.unmodifiableList(HttpStatus.SC_NOT_FOUND);

    /**
     * OAuth access token used for Egnyte API requests.
     */
    @ToString.Exclude
    private String accessToken;

    /**
     * Egnyte API base URL. Supports {domain} placeholder.
     */
    private String apiBaseUrl = "https://{domain}.egnyte.com/pubapi/v1";

    /**
     * HTTP status codes considered successful for Egnyte API calls.
     */
    private final List<Integer> validStatusCodes =
            new ArrayList<>(DEFAULT_VALID_STATUS_CODES);

    /**
     * HTTP status codes considered "not found".
     */
    private final List<Integer> notFoundStatusCodes =
            new ArrayList<>(DEFAULT_NOT_FOUND_STATUS_CODES);

    public List<Integer> getValidStatusCodes() {
        return Collections.unmodifiableList(validStatusCodes);
    }

    public EgnyteFetcherConfig setValidStatusCodes(
            List<Integer> validStatusCodes) {
        CollectionUtil.setAll(this.validStatusCodes, validStatusCodes);
        return this;
    }

    public List<Integer> getNotFoundStatusCodes() {
        return Collections.unmodifiableList(notFoundStatusCodes);
    }

    public EgnyteFetcherConfig setNotFoundStatusCodes(
            List<Integer> notFoundStatusCodes) {
        CollectionUtil.setAll(this.notFoundStatusCodes, notFoundStatusCodes);
        return this;
    }
}
