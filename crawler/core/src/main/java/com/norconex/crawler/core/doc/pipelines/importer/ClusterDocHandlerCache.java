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
package com.norconex.crawler.core.doc.pipelines.importer;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.importer.handler.DocHandlerCache;

import lombok.RequiredArgsConstructor;

/**
 * Backs the generic {@link DocHandlerCache} document handlers are given with
 * the crawler's own {@link CacheManager}: an MVStore-backed file when the
 * crawler runs standalone, or the crawl's distributed cache when clustered.
 * A handler that wants a cache (e.g., one calling a paid external API)
 * reuses that store instead of needing one of its own, at no extra
 * dependency.
 * <p>
 * The cache used is the crawler-scoped one ({@link
 * CacheManager#getCrawlerCache()}): it persists across crawl sessions, which
 * is what lets a handler's cache still pay off on a later crawl, not just
 * within the one that populated it.
 * </p>
 */
@RequiredArgsConstructor
public class ClusterDocHandlerCache implements DocHandlerCache {

    private final CacheManager cacheManager;

    @Override
    public String computeIfAbsent(String key, Loader loader)
            throws IOException {
        try {
            return cacheManager.getCrawlerCache().computeIfAbsent(key, k -> {
                try {
                    return loader.load(k);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
