/* Copyright 2014-2026 Norconex Inc.
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
package com.norconex.importer.doc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.ImporterRuntimeException;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * A document being imported.
 */
@Data
@Accessors(chain = true)
public class Doc implements Closeable {

    //NOTE no more DocContext: has been merged here
    //TODO standardize some metadata?

    @NonNull
    @Setter(value = AccessLevel.NONE)
    private String reference;
    private ContentType contentType;
    private Charset charset;

    /**
     * If this is an embedded document, holds the chain of parent
     * references up to the immediate parent (does not include this
     * signature reference).
     */
    @ToString.Exclude
    private List<String> parentReferences = new ArrayList<>();

    /**
     * If this document is a part a splitter made, the positions (see
     * {@link com.norconex.importer.handler.HandlerPositions}) of the
     * splitters it came out of, the first split first. Not metadata: it only
     * serves while the document is being imported.
     */
    @ToString.Exclude
    private List<String> splitTrail = new ArrayList<>();

    @ToString.Exclude
    private Properties metadata = new Properties();

    @ToString.Exclude
    @Setter(value = AccessLevel.NONE)
    @Getter(value = AccessLevel.NONE)
    @NonNull
    private CachedInputStream content;

    public Doc(String reference) {
        this.reference = reference;
        content = CachedInputStream.nullInputStream();
    }

    public Doc setMetadata(Properties metadata) {
        if (metadata == null) {
            this.metadata.clear();
        } else {
            this.metadata = metadata;
        }
        return this;
    }

    /**
     * Copy constructor.
     * @param doc document details to copy
     */
    public Doc(@NonNull Doc doc) { //NOSONAR
        charset = doc.charset;
        content = doc.content;
        contentType = doc.contentType;
        metadata = doc.metadata;
        parentReferences = doc.parentReferences;
        splitTrail = doc.splitTrail;
        reference = doc.reference;
    }

    public List<String> getParentReferences() {
        return Collections.unmodifiableList(parentReferences);
    }

    public Doc setParentReferences(List<String> parentReferences) {
        CollectionUtil.setAll(this.parentReferences, parentReferences);
        return this;
    }

    public List<String> getSplitTrail() {
        return Collections.unmodifiableList(splitTrail);
    }

    public Doc setSplitTrail(List<String> splitTrail) {
        CollectionUtil.setAll(this.splitTrail, splitTrail);
        return this;
    }

    public Doc addParentReference(String parentReference) {
        parentReferences.add(parentReference);
        return this;
    }

    public Doc withReference(String reference) {
        var doc = new Doc(this);
        doc.reference = reference;
        return doc;
    }

    /**
     * Disposes of any resources associated with this document (like
     * disk or memory cache).
     * @throws IOException could not dispose of document resources
     */
    @Override
    public void close() throws IOException {
        content.dispose();
    }

    public CachedInputStream getInputStream() {
        content.rewind();
        return content;
    }

    public Doc setInputStream(@NonNull InputStream inputStream) {
        if (content == inputStream) {
            return this;
        }
        try {
            content.dispose();
            if (inputStream instanceof CachedInputStream cis) {
                content = cis;
            } else {
                try (var os = content.getStreamFactory().newOuputStream()) {
                    IOUtils.copy(inputStream, os);
                    content = os.getInputStream();
                }
            }
        } catch (IOException e) {
            throw new ImporterRuntimeException(
                    "Could set content input stream.", e);
        }
        return this;
    }

    public CachedStreamFactory getStreamFactory() {
        return content.getStreamFactory();
    }
}
