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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.impl.BlankCondition;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * Met when metadata fields are blank or missing. By default, all the values
 * of the matching fields must be blank. A field name pattern is required: the
 * document content is never tested. To test that a field is present, negate
 * this condition with a "noneOf" group.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class MetadataBlankCondition extends MetadataCondition {

    private final TextMatcher fieldMatcher = new TextMatcher();
    /**
     * Whether a single blank value is enough for the condition to be met,
     * instead of all values having to be blank.
     */
    private boolean matchAnyBlank;

    /**
     * Gets the matcher of field names.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the matcher of field names. Copies it.
     * @param fieldMatcher field matcher
     * @return this instance
     */
    public MetadataBlankCondition setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    @Override
    Condition toCondition() {
        requireField(fieldMatcher, "blank");
        var cond = new BlankCondition();
        cond.getConfiguration()
                .setFieldMatcher(fieldMatcher)
                .setMatchAnyBlank(matchAnyBlank);
        return cond;
    }
}
