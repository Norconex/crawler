/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core.session;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import lombok.Builder;
import lombok.Value;

/**
 * Details on the current crawl run.
 */
// Lombok's @Jacksonized cannot be used here: it emits Jackson 2
// annotations (com.fasterxml.jackson.databind.annotation), which Jackson 3
// does not read. The result was a class that serialized fine and then
// failed to deserialize with "no Creators, like default constructor,
// exist". The builder is wired up explicitly against tools.jackson instead.
@JsonDeserialize(builder = CrawlerRunInfo.CrawlerRunInfoBuilder.class)
@Value
@Builder
public class CrawlerRunInfo {

    @JsonPOJOBuilder(withPrefix = "")
    public static class CrawlerRunInfoBuilder {
        // Lombok fills this in.
    }

    /**
     * Stable identity of a crawler. Usually configuration-driven.
     */
    private final String crawlerId;
    /**
     * Full life-cycle identifier for a single crawl. Represents the crawl
     * session, spans multiple cluster restarts/resumes.
     */
    private final String crawlSessionId;
    /**
     * Unique identifier for each time the crawler starts (until termination).
     * When run on a cluster, it is also an ephemeral cluster instance
     * identifier.
     */
    private final String crawlRunId;
    /**
     * The mode used to crawl web sites. For any given crawler, a fresh crawl
     * is always a {@link CrawlerMode#FULL} crawl while subsequent crawls
     * are usually considered {@link CrawlerMode#INCREMENTAL} crawls.
     */
    private final CrawlerMode crawlMode;

    /**
     * Whether we are starting a new crawl session or resuming a previous one.
     */
    private final CrawlerResumeState crawlResumeState;
}
