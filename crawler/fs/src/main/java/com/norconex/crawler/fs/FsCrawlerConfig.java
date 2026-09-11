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
package com.norconex.crawler.fs;

import java.util.List;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.fetch.impl.local.LocalFetcher;

/**
 * <p>
 * File System Crawler configuration. Behaves exactly like
 * {@link CrawlerConfig}, with one addition: a {@link LocalFetcher} is
 * registered by default, so a configuration that declares no
 * {@code fetchers} still crawls the local file system.
 * </p>
 * <p>
 * Declaring one or more fetchers replaces this default. Declaring an
 * explicitly empty {@code fetchers} list leaves the crawler with no fetcher,
 * same as for any other crawler.
 * </p>
 *
 * @see LocalFetcher
 */
public class FsCrawlerConfig extends CrawlerConfig {

    public FsCrawlerConfig() {
        setFetchers(List.<Fetcher>of(new LocalFetcher()));
    }
}
