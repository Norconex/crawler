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
package com.norconex.crawler.core.doc.operations.filter.impl.condition;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.Condition;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * <p>
 * A test on a reference's metadata, used by
 * {@link com.norconex.crawler.core.doc.operations.filter.impl.ConditionMetadataFilter}.
 * Only the kinds of tests offered by the classes of this package are
 * available (text, number, date, blank, and groups of those). They only look
 * at metadata field values: none of them ever read a document content,
 * which makes them safe to use before a document is downloaded.
 * </p>
 * <p>
 * This class cannot be extended outside of its package.
 * </p>
 */
public abstract class MetadataCondition {

    private static final EventManager NO_EVENTS = new EventManager();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient volatile Condition compiled;

    MetadataCondition() {
    }

    /**
     * Tests whether the supplied metadata satisfies this condition.
     * @param reference the document reference
     * @param metadata the metadata to test (not modified)
     * @return {@code true} if the condition is met
     */
    public final boolean test(String reference, Properties metadata) {
        var doc = new Doc(reference);
        doc.setMetadata(metadata == null ? new Properties() : metadata);
        var ctx = DocHandlerContext.builder()
                .doc(doc)
                .eventManager(NO_EVENTS)
                .build();
        try {
            return compiled().test(ctx);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not test condition on: " + reference, e);
        }
    }

    /**
     * Builds the equivalent condition this one relies on. Only called once,
     * when first needed.
     * @return a condition that only looks at metadata
     */
    abstract Condition toCondition();

    private Condition compiled() {
        var c = compiled;
        if (c == null) {
            c = toCondition();
            compiled = c;
        }
        return c;
    }

    static TextMatcher requireField(TextMatcher fieldMatcher, String what) {
        if (fieldMatcher == null || fieldMatcher.getPattern() == null
                || fieldMatcher.getPattern().isBlank()) {
            throw new IllegalArgumentException(
                    "A \"fieldMatcher\" pattern is required for a "
                            + what + " condition: it only tests metadata "
                            + "fields, never the document content.");
        }
        return fieldMatcher;
    }
}
