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
package com.norconex.crawler.core.test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.Bag;
import org.apache.commons.collections4.bag.TreeBag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import com.norconex.crawler.core.cluster.CacheNames;
import com.norconex.crawler.core.session.CrawlerRunInfo;
import com.norconex.crawler.core.session.CrawlerRunInfoResolver;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NonNull;

@JsonDeserialize(builder = CrawlerTestNodeOutput.NodeTestResultBuilder.class)
@Data
@Builder(builderClassName = "NodeTestResultBuilder")
public class CrawlerTestNodeOutput implements Serializable {

    private static final long serialVersionUID = -9001010026702054867L;

    @JsonPOJOBuilder(withPrefix = "")
    public static class NodeTestResultBuilder {
        // Lombok will fill this in
    }

    @Data
    public static class StatusCounts {
        private final long queued;
        private final long untracked;
        private final long processing;
        private final long processed;
    }

    @Default
    @NonNull
    private final List<String> eventNames = new ArrayList<>();

    @Default
    @NonNull
    private List<String> logLines = new ArrayList<>();

    @Default
    @NonNull
    private final Map<String, Map<String, String>> caches = new HashMap<>();

    @JsonIgnore
    public Bag<String> getEventNameBag() {
        var bag = new TreeBag<String>();
        getEventNames().forEach(bag::add);
        return bag;
    }

    @JsonIgnore
    public String getLog() {
        return String.join("\n", logLines);
    }

    @JsonIgnore
    public Map<String, String> getCache(String name) {
        return caches.get(name);
    }

    @JsonIgnore
    public StatusCounts getLedgerStatusCounts() {
        var sessionMap = caches.get(CacheNames.CRAWL_SESSION);
        return new StatusCounts(
                Long.parseLong(sessionMap.get("status-counter-QUEUED")),
                Long.parseLong(sessionMap.get("status-counter-UNTRACKED")),
                Long.parseLong(sessionMap.get("status-counter-PROCESSING")),
                Long.parseLong(sessionMap.get("status-counter-PROCESSED")));
    }

    @JsonIgnore
    public CrawlerRunInfo getCrawlRunInfo() {
        var key = CrawlerRunInfoResolver.CRAWL_RUN_INFO_KEY;

        var runMap = caches.get(CacheNames.CRAWL_RUN);
        if (runMap != null) {
            var rec = runMap.get(key);
            if (rec != null) {
                return SerialUtil.fromJson(rec, CrawlerRunInfo.class);
            }
        }

        // Fallback: multi-node test snapshots sometimes omit CRAWL_RUN, but the
        // same crawlRunInfo is also present in the crawl session cache.
        var sessionMap = caches.get(CacheNames.CRAWL_SESSION);
        if (sessionMap != null) {
            var rec = sessionMap.get(key);
            if (rec != null) {
                return SerialUtil.fromJson(rec, CrawlerRunInfo.class);
            }
        }

        throw new IllegalStateException(
                "No '" + key + "' found in caches. Available caches: "
                        + caches.keySet());
    }
}
