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
import com.norconex.importer.handler.condition.impl.NumericCondition;
import com.norconex.importer.handler.condition.impl.NumericValueMatcher;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * Met when at least one value of a metadata field is a number satisfying
 * the supplied comparison(s), such as a size ("Content-Length"). Decimals are
 * supported (dot as decimal character). A value that is not a number never
 * matches, and neither does a missing field.
 * </p>
 * <p>
 * Use one value matcher to compare with a number, or two to define a range
 * (both must be satisfied). With none, the condition is met when the value
 * is a number.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class MetadataNumberCondition extends MetadataCondition {

    private final TextMatcher fieldMatcher = new TextMatcher();
    private NumericValueMatcher valueMatcher;
    private NumericValueMatcher valueMatcherRangeEnd;

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
    public MetadataNumberCondition setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    @Override
    Condition toCondition() {
        requireField(fieldMatcher, "number");
        var cond = new NumericCondition();
        cond.getConfiguration()
                .setFieldMatcher(fieldMatcher)
                .setValueMatcher(valueMatcher)
                .setValueMatcherRangeEnd(valueMatcherRangeEnd);
        return cond;
    }
}
