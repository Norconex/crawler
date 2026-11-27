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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.doc.operations.checksum.FsMetadataChecksummer;
import com.norconex.crawler.fs.fetch.impl.local.LocalFetcher;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

/**
 * <p>
 * File System Crawler configuration. Behaves like {@link CrawlerConfig},
 * with three additions.
 * </p>
 *
 * <h2>Default metadata checksummer</h2>
 * <p>
 * An {@link FsMetadataChecksummer} is set by default, so a file that has not
 * changed (same last modified date and size) since the previous crawl is
 * recognized before it is read and imported again, as pages are on the web.
 * Set the metadata checksummer to {@code null} to turn this off and have
 * every file processed on every crawl.
 * </p>
 *
 * <h2>Default fetcher</h2>
 * <p>
 * A {@link LocalFetcher} is registered by default, so a configuration that
 * declares no {@code fetchers} still crawls the local file system.
 * Declaring one or more fetchers replaces this default. Declaring an
 * explicitly empty {@code fetchers} list leaves the crawler with no fetcher,
 * same as for any other crawler.
 * </p>
 *
 * <h2>Files and folders</h2>
 * <p>
 * Folders are only there to be browsed: they are never sent to a committer.
 * Filters are therefore split by what they decide on:
 * </p>
 * <ul>
 *   <li>
 *     The reference, metadata and document filters (see
 *     {@link CrawlerConfig}) judge <b>files</b> only. A folder is never
 *     tested by them, so a rule such as "only keep PDF files" does not
 *     reject the folders leading to them.
 *   </li>
 *   <li>
 *     {@link #getFolderReferenceFilters() folderReferenceFilters} and
 *     {@link #getFolderMetadataFilters() folderMetadataFilters} decide
 *     which <b>folders</b> to open. A folder that is rejected is not listed,
 *     and neither is anything under it.
 *   </li>
 * </ul>
 * <p>
 * Start references are never filtered, whether they are a file or a folder.
 * </p>
 *
 * @see LocalFetcher
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
@FieldNameConstants
public class FsCrawlerConfig extends CrawlerConfig {

    private final List<ReferenceFilter> folderReferenceFilters =
            new ArrayList<>();
    private final List<MetadataFilter> folderMetadataFilters =
            new ArrayList<>();

    /**
     * Whether a file whose permissions were asked for (see the fetchers'
     * {@code acl} setting) but could not be read in full is rejected right
     * after it is fetched, firing {@code REJECTED_ACL} with what could not be
     * read, and deleting any version sent before. It then goes no further:
     * it is not parsed or imported, and no committer is reached.
     * Default is {@code false}: such a file goes on, and committers hold it
     * back (or send it, as they are set to).
     */
    private boolean rejectIncompleteAcl;

    public FsCrawlerConfig() {
        setFetchers(List.<Fetcher>of(new LocalFetcher()));
        setMetadataChecksummer(new FsMetadataChecksummer());
    }

    /**
     * Gets the filters deciding on a folder reference (its path), before the
     * folder is opened.
     * @return folder reference filters
     */
    public List<ReferenceFilter> getFolderReferenceFilters() {
        return Collections.unmodifiableList(folderReferenceFilters);
    }

    /**
     * Sets the filters deciding on a folder reference (its path), before the
     * folder is opened. Unlike the reference filters of files, an
     * "include" filter here means "only open folders matching", so it has to
     * match the folders leading to the ones wanted, too.
     * @param folderReferenceFilters folder reference filters
     * @return this
     */
    public FsCrawlerConfig setFolderReferenceFilters(
            List<ReferenceFilter> folderReferenceFilters) {
        CollectionUtil.setAll(
                this.folderReferenceFilters, folderReferenceFilters);
        return this;
    }

    /**
     * Gets the filters deciding on a folder metadata (such as its dates),
     * before the folder is opened.
     * @return folder metadata filters
     */
    public List<MetadataFilter> getFolderMetadataFilters() {
        return Collections.unmodifiableList(folderMetadataFilters);
    }

    /**
     * Sets the filters deciding on a folder metadata (such as its dates),
     * before the folder is opened. Which metadata a folder has depends on
     * the fetcher.
     * @param folderMetadataFilters folder metadata filters
     * @return this
     */
    public FsCrawlerConfig setFolderMetadataFilters(
            List<MetadataFilter> folderMetadataFilters) {
        CollectionUtil.setAll(
                this.folderMetadataFilters, folderMetadataFilters);
        return this;
    }
}
