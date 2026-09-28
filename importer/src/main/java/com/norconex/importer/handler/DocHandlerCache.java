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
package com.norconex.importer.handler;

import java.io.IOException;

/**
 * <p>
 * A key/value cache made available to document handlers for results that are
 * expensive or costly to obtain more than once for the same input (e.g., a
 * call to a paid external API). The importer itself does not implement one:
 * whatever runs it (a crawler, the command-line launcher, a test) may supply
 * one via {@link DocHandlerContext}, so it can back it with whatever storage
 * makes sense there — a crawler backs it with its own persistent, cluster-
 * aware store, at no extra cost to a handler that uses it.
 * </p>
 * <p>
 * A handler is never left checking for {@code null}: when nothing is
 * supplied, {@link DocHandlerContext} falls back to {@link #NOOP}, which
 * always calls the {@link Loader} and never remembers the result.
 * </p>
 */
@FunctionalInterface
public interface DocHandlerCache {

    /**
     * A cache that holds nothing: every lookup is a miss, so the
     * {@link Loader} always runs. Used whenever no real cache is supplied.
     */
    DocHandlerCache NOOP = (key, loader) -> loader.load(key);

    /**
     * Returns the value previously cached for {@code key}, or computes it
     * with {@code loader}, caches it, then returns it.
     * @param key cache key
     * @param loader supplies the value when not already cached
     * @return the cached or freshly computed value
     * @throws IOException if the loader fails to compute a value
     */
    String computeIfAbsent(String key, Loader loader) throws IOException;

    /**
     * Computes a value to be cached, for whichever key was not already in
     * the cache.
     */
    @FunctionalInterface
    interface Loader {
        /**
         * Computes the value for the given key.
         * @param key cache key
         * @return the value to cache
         * @throws IOException if the value could not be computed
         */
        String load(String key) throws IOException;
    }
}
