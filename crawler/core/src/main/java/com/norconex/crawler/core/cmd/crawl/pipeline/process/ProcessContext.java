/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.response.ImporterResponse;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
class ProcessContext {
    private CrawlerSession crawlSession;
    private CrawlerDocContext docContext;
    //private CrawlerEntry crawlEntry;
    private ImporterResponse importerResponse;
    //    private boolean orphan;
    private boolean finalized;
    /**
     * Whether what becomes of the document's children was already settled,
     * because the document went through the importer and its children, if
     * any, were processed with it.
     */
    private boolean childrenHandled;
}
