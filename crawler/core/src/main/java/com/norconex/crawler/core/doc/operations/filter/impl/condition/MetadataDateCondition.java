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

import java.time.DateTimeException;
import java.time.ZoneId;

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.impl.DateCondition;
import com.norconex.importer.handler.condition.impl.DateValueMatcher;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * Met when at least one value of a metadata field is a date satisfying
 * the supplied comparison(s), such as a last modification date
 * ("Last-Modified"). A value that is not a date in the expected format never
 * matches, and neither does a missing field.
 * </p>
 * <p>
 * Use one value matcher to compare with a date, or two to define a range
 * (both must be satisfied). A matcher date is either absolute
 * ("2025-05-31" or "2025-05-31T22:44:15") or relative to the current date
 * ("TODAY-2Y" or "NOW-6h").
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class MetadataDateCondition extends MetadataCondition {

    private final TextMatcher fieldMatcher = new TextMatcher();
    /**
     * Format of the metadata date values, as per
     * {@link java.time.format.DateTimeFormatter}. Defaults to the number of
     * milliseconds since 1970-01-01 (epoch).
     */
    private String format;
    /**
     * Time zone assumed for metadata date values that have none.
     * Defaults to UTC.
     */
    private ZoneId docZoneId;
    private DateValueMatcher valueMatcher;
    private DateValueMatcher valueMatcherRangeEnd;

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
    public MetadataDateCondition setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    @Override
    Condition toCondition() {
        requireField(fieldMatcher, "date");
        var cond = new DateCondition();
        cond.getConfiguration()
                .setFieldMatcher(fieldMatcher)
                .setFormat(format)
                .setDocZoneId(docZoneId)
                .setValueMatcher(valueMatcher)
                .setValueMatcherRangeEnd(valueMatcherRangeEnd);
        // A value that is not a date in the expected format (a server that sends a malformed Last-Modified, say) is a
        // value that does not match, not a failure of the document: the date parser throws, and the other tests of a
        // group still have their say.
        return ctx -> {
            try {
                return cond.test(ctx);
            } catch (DateTimeException e) {
                return false;
            }
        };
    }
}
