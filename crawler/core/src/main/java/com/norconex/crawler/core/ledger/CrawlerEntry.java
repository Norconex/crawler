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
package com.norconex.crawler.core.ledger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.file.ContentType;

import lombok.Data;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Holds minimal meta information and state necessary to the proper
 * (re)processing of a document in the context of a crawl.
 * Also persisted in the {@link CrawlerEntryLedger}.
 */
@Data
@FieldNameConstants
public class CrawlerEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Typically, the number of "hops" or directories to get to a file. */
    private int depth;

    @NonNull
    private ProcessingStatus processingStatus = ProcessingStatus.UNTRACKED;
    private ProcessingOutcome processingOutcome;

    /** Original references before normalization, redirect, etc. */
    private final List<String> referenceTrail = new ArrayList<>();

    @ToString.Exclude
    private String metaChecksum;
    @ToString.Exclude
    private String contentChecksum;
    @ToString.Exclude
    private ZonedDateTime queuedAt;
    @ToString.Exclude
    private ZonedDateTime processingAt;
    /**
     * The name of the node that last took this entry for processing, set
     * when the entry turned {@link ProcessingStatus#PROCESSING}. If that node
     * leaves the crawl before finishing it, another node puts the entry back
     * in the queue.
     */
    @ToString.Exclude
    private String processingNode;
    /**
     * Identifies the taking of this entry for processing by
     * {@link #getProcessingNode()}, new each time it is taken. Lets the node
     * processing it tell whether it still holds it before sending it to
     * committers or recording its outcome.
     */
    @ToString.Exclude
    private String processingClaim;
    /**
     * Whether the node holding this entry for processing started sending it
     * (or documents found in it) to committers. If that node stops working
     * on the crawl after that, the entry is processed again and may reach
     * committers twice.
     */
    @ToString.Exclude
    private boolean sending;
    @ToString.Exclude
    private ZonedDateTime processedAt;
    @ToString.Exclude
    private ZonedDateTime lastModified;
    @ToString.Exclude
    private ContentType contentType;
    @ToString.Exclude
    private transient Charset charset;
    private boolean orphan;
    private boolean deleted;
    private String reference;

    /**
     * The reference of the document this one was split off from or found
     * embedded in (a chunk of a text, a zip entry, an attachment), or
     * {@code null} for a document that stands on its own.
     */
    private String parentReference;

    /**
     * How many documents were split off from or found embedded in this one
     * the last time it was processed. Zero for most documents. Only a count,
     * never the references: a parent can have hundreds of thousands of
     * children, and an entry is read and written many times in a crawl. When
     * it is above zero, the children are found through their
     * {@link #getParentReference()}.
     */
    private int childCount;

    public CrawlerEntry() {
    }

    public CrawlerEntry(String reference) {
        this.reference = reference;
    }

    public CrawlerEntry withReference(String reference) {
        var docInfo = BeanUtil.clone(this);
        docInfo.setReference(reference);
        return docInfo;
    }

    /**
     * Gets original references before normalization, redirect, etc.
     * @return reference trail up to, but excluding, the current one.
     */
    public List<String> getReferenceTrail() {
        return Collections.unmodifiableList(referenceTrail);
    }

    /**
     * Sets original references before normalization, redirect, etc.
     * @param referenceTrail reference trail up to, but excluding, the current
     *     one.
     */
    public void setReferenceTrail(List<String> referenceTrail) {
        CollectionUtil.setAll(this.referenceTrail, referenceTrail);
    }

    /**
     * Adds original references before normalization, redirect, etc.
     * @param reference reference to append to the trail
     */
    public void addToReferenceTrail(String reference) {
        referenceTrail.add(reference);
    }

    public String getMetaChecksum() {
        return metaChecksum;
    }

    public void setMetaChecksum(String metaChecksum) {
        this.metaChecksum = metaChecksum;
    }

    /**
     * Gets the content checksum.
     * @return the content checksum
     */
    public String getContentChecksum() {
        return contentChecksum;
    }

    /**
     * Sets the content checksum.
     * @param contentChecksum content checksum
     */
    public void setContentChecksum(String contentChecksum) {
        this.contentChecksum = contentChecksum;
    }

    /**
     * Gets the queued date.
     * @return the queued date
         */
    public ZonedDateTime getQueuedAt() {
        return queuedAt;
    }

    /**
     * Sets the queued date.
     * @param queuedAt the queued date
     */
    public void setQueuedAt(ZonedDateTime queuedAt) {
        this.queuedAt = queuedAt;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeObject(charset != null ? charset.name() : null);
    }

    private void readObject(ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        var charsetName = (String) in.readObject();
        charset = charsetName != null ? Charset.forName(charsetName) : null;
    }
}
