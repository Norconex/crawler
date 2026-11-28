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
package com.norconex.importer.handler.condition.impl;

import java.io.IOException;

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;
import com.norconex.importer.util.MatchUtil;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * A condition based on a text pattern matching a document content type
 * (without its character set, e.g., <code>text/html</code>). A document
 * with no known content type meets it.
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/ContentTypeCondition">
 *      ContentTypeCondition configuration reference</a>
 */
@EqualsAndHashCode
@ToString
public class ContentTypeCondition
        implements ConfigurableCondition<ContentTypeConditionConfig> {

    @Getter
    private final ContentTypeConditionConfig configuration =
            new ContentTypeConditionConfig();

    public ContentTypeCondition() {
    }

    public ContentTypeCondition(TextMatcher valueMatcher) {
        configuration.setValueMatcher(valueMatcher);
    }

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        return MatchUtil.matchesContentType(
                configuration.getValueMatcher(), docCtx.contentType());
    }
}
