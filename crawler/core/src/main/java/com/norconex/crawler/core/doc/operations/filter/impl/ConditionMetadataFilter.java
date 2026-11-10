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
package com.norconex.crawler.core.doc.operations.filter.impl;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.doc.operations.filter.DocumentFilter;
import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.OnMatchFilter;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Accepts or rejects a reference based on a condition on its metadata:
 * text, number (e.g., a size), date (e.g., a modification date) or blank
 * tests on metadata fields, which can be combined and nested with
 * "allOf", "anyOf" and "noneOf" groups.
 * </p>
 * <p>
 * Only metadata is ever tested, never a document content. When used as a
 * metadata filter on a crawler that obtains metadata before downloading
 * a document (see <code>metadataFetchSupport</code>), it can discard
 * documents before they are downloaded.
 * </p>
 */
@EqualsAndHashCode
@ToString
public class ConditionMetadataFilter implements
        OnMatchFilter,
        MetadataFilter,
        DocumentFilter,
        Configurable<ConditionMetadataFilterConfig> {

    @Getter
    private final ConditionMetadataFilterConfig configuration =
            new ConditionMetadataFilterConfig();

    @Override
    public OnMatch getOnMatch() {
        return OnMatch.includeIfNull(configuration.getOnMatch());
    }

    @Override
    public boolean acceptMetadata(String reference, Properties metadata) {
        var condition = configuration.getCondition();
        if (condition == null) {
            return true;
        }
        return condition.test(reference,
                metadata) == (getOnMatch() == OnMatch.INCLUDE);
    }

    @Override
    public boolean acceptDocument(Doc document) {
        if (document == null) {
            return true;
        }
        return acceptMetadata(document.getReference(), document.getMetadata());
    }
}
