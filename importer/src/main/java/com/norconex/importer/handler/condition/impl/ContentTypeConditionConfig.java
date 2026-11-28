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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.condition.BaseConditionConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * A condition based on a text pattern matching a document content type.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/ContentTypeCondition">
 *      ContentTypeCondition configuration reference</a>
 */
@Data
@Accessors(chain = true)
public class ContentTypeConditionConfig extends BaseConditionConfig {

    private final TextMatcher valueMatcher = new TextMatcher();

    /**
     * Gets the text matcher for the content type.
     * @return text matcher
     */
    public TextMatcher getValueMatcher() {
        return valueMatcher;
    }

    /**
     * Sets the text matcher for the content type. Copies it.
     * @param valueMatcher text matcher
     * @return this
     */
    public ContentTypeConditionConfig setValueMatcher(
            TextMatcher valueMatcher) {
        this.valueMatcher.copyFrom(valueMatcher);
        return this;
    }
}
