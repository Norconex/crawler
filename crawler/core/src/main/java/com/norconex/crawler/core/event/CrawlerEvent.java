/* Copyright 2018-2026 Norconex Inc.
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
package com.norconex.crawler.core.event;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.event.Event;
import com.norconex.crawler.core.cmd.Command;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A crawler event.
 */
@Data
@Setter(value = AccessLevel.NONE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class CrawlerEvent extends Event {

    private static final long serialVersionUID = 1L;

    /**
     * The crawler is about to begin crawling.
     */
    public static final String CRAWLER_CRAWL_BEGIN = "CRAWLER_CRAWL_BEGIN";
    /**
     * The crawler completed crawling execution normally
     * (without being stopped). This event is triggered before the crawler
     * resources are released.
     */
    public static final String CRAWLER_CRAWL_END = "CRAWLER_CRAWL_END";

    /**
     * Issued when a request to stop the crawler has been sent.
     */
    public static final String CRAWLER_STOP_REQUEST_BEGIN =
            "CRAWLER_STOP_REQUEST_BEGIN";
    /**
     * Issued when a request to stop the crawler has been received.
     */
    public static final String CRAWLER_STOP_REQUEST_END =
            "CRAWLER_STOP_REQUEST_END";

    public static final String CRAWLER_CLEAN_BEGIN = "CRAWLER_CLEAN_BEGIN";
    public static final String CRAWLER_CLEAN_END = "CRAWLER_CLEAN_END";

    public static final String CRAWLER_STORE_EXPORT_BEGIN =
            "CRAWLER_STORE_EXPORT_BEGIN";
    public static final String CRAWLER_STORE_EXPORT_END =
            "CRAWLER_STORE_EXPORT_END";
    public static final String CRAWLER_STORE_IMPORT_BEGIN =
            "CRAWLER_STORE_IMPORT_BEGIN";
    public static final String CRAWLER_STORE_IMPORT_END =
            "CRAWLER_STORE_IMPORT_END";

    public static final String CRAWLER_ERROR = "CRAWLER_ERROR";

    /**
     * Fired just before a crawl session is initialized. The event source
     * is the {@link com.norconex.crawler.core.CrawlerConfig}.
     */
    public static final String CRAWLER_SESSION_BEGIN = "CRAWLER_SESSION_BEGIN";
    /**
     * Fired just after a crawl session has been closed. The event source
     * is the {@link com.norconex.crawler.core.CrawlerConfig}.
     */
    public static final String CRAWLER_SESSION_END = "CRAWLER_SESSION_END";

    /**
     * Fired just before a command starts executing.
     * The {@link #getCommandClass()} field carries the command type.
     */
    public static final String CRAWLER_COMMAND_BEGIN = "CRAWLER_COMMAND_BEGIN";
    /**
     * Fired just after a command finishes executing.
     * The {@link #getCommandClass()} field carries the command type.
     */
    public static final String CRAWLER_COMMAND_END = "CRAWLER_COMMAND_END";

    /**
     * A document was rejected by a filters.
     */
    public static final String REJECTED_FILTER = "REJECTED_FILTER";
    /**
     * A document was rejected as it was not modified since
     * last time it was crawled.
     */
    public static final String REJECTED_UNMODIFIED = "REJECTED_UNMODIFIED";
    /**
     * A document was rejected since another document with a different
     * reference was already processed with the same digital signature (
     * checksum).
         */
    public static final String REJECTED_DUPLICATE = "REJECTED_DUPLICATE";
    /**
     * A document could not be re-crawled because it is not yet ready to be
     * re-crawled.
     */
    public static final String REJECTED_PREMATURE = "REJECTED_PREMATURE";
    /**
     * A document was rejected because it could not be found (e.g., no longer
     * exists at a given location).
     */
    public static final String REJECTED_NOTFOUND = "REJECTED_NOTFOUND";
    /**
     * A document was rejected because the status obtained when trying
     * to obtain it was not accepted (e.g., 500 HTTP error code).
     */
    public static final String REJECTED_BAD_STATUS = "REJECTED_BAD_STATUS";
    /**
     * A document was rejected because it is located too deep.
     */
    public static final String REJECTED_TOO_DEEP = "REJECTED_TOO_DEEP";
    /**
     * A document was rejected by the Importer module.
     */
    public static final String REJECTED_IMPORT = "REJECTED_IMPORT";
    /**
     * A document was rejected because an error occurred when processing it.
     */
    public static final String REJECTED_ERROR = "REJECTED_ERROR";
    /**
     * A document was rejected because its permissions (access control list)
     * were asked for but could not be read in full. The event message says
     * what could not be read.
     */
    public static final String REJECTED_ACL = "REJECTED_ACL";
    /**
     * A document pre-import processor was executed properly.
     */
    public static final String DOCUMENT_PREIMPORTED = "DOCUMENT_PREIMPORTED";
    /**
     * A document was imported.
     */
    public static final String DOCUMENT_IMPORTED = "DOCUMENT_IMPORTED";
    /**
     * A document post-import processor was executed properly.
     */
    public static final String DOCUMENT_POSTIMPORTED = "DOCUMENT_POSTIMPORTED";

    /**
     * A document metadata fields were successfully retrieved.
     */
    public static final String DOCUMENT_METADATA_FETCHED =
            "DOCUMENT_METADATA_FETCHED";
    /**
     * A document was successfully retrieved for processing.
     */
    public static final String DOCUMENT_FETCHED = "DOCUMENT_FETCHED";

    /**
     * A document reference was queued in the data store for processing.
     */
    public static final String DOCUMENT_QUEUED = "DOCUMENT_QUEUED";

    /**
     * A document was processed (successfully or not).
     */
    public static final String DOCUMENT_PROCESSED = "DOCUMENT_PROCESSED";

    /**
     * Fired just before document processing begins.
     * The event source is the {@link com.norconex.importer.doc.Doc}.
     */
    public static final String DOCUMENT_PROCESSING_BEGIN =
            "DOCUMENT_PROCESSING_BEGIN";
    /**
     * Fired just after document processing ends.
     * The event source is the {@link com.norconex.importer.doc.Doc}.
     */
    public static final String DOCUMENT_PROCESSING_END =
            "DOCUMENT_PROCESSING_END";

    /**
     * Fired just before document finalization begins.
     * The event source is the {@link com.norconex.importer.doc.Doc}.
     */
    public static final String DOCUMENT_FINALIZING_BEGIN =
            "DOCUMENT_FINALIZING_BEGIN";
    /**
     * Fired just after document finalization ends.
     * The event source is the {@link com.norconex.importer.doc.Doc}.
     */
    public static final String DOCUMENT_FINALIZING_END =
            "DOCUMENT_FINALIZING_END";

    //    /**
    //     * A document was saved.
    //     */
    //    public static final String DOCUMENT_SAVED = "DOCUMENT_SAVED";

    /**
     * Gets the document being processed associated with this event.
     * CRAWLER_* events will return a {@code null} doc.
     */
    private final transient CrawlerEntry crawlEntry;
    private final transient CrawlerSession crawlSession;
    /**
     * The command class firing a {@link #CRAWLER_COMMAND_BEGIN} or
     * {@link #CRAWLER_COMMAND_END} event, or {@code null} for all other events.
     */
    private final transient Class<? extends Command> commandClass;
    //    private final transient Object subject;
    //TODO keep a reference to actual document?

    public CrawlerSession getCrawlSession() {
        return crawlSession;
    }

    //    /**
    //     * Gets the subject. That is often the entity being the target
    //     * of the event (as opposed to the source).
    //     * @return the subject
    //     */
    //    public Object getSubject() {
    //        return subject;
    //    }
    //
    //    /**
    //     * The {@link CrawlerSession}.
    //     */
    //    @Override
    //    public CrawlerSession getSource() {
    //        return (CrawlerSession) super.getSource();
    //    }

    @Override
    public String toString() {
        var b = new StringBuilder();
        // always print the reference if we have it
        if (crawlEntry != null) {
            b.append(crawlEntry.getReference()).append(" - ");
        }

        // print the first value set in that order: message, source, session
        if (StringUtils.isNotBlank(getMessage())) {
            b.append(getMessage());
        } else if (source != null) {
            b.append(source.toString());
        } else {
            b.append(Objects.toString(crawlSession));
        }

        return b.toString();
    }
}
