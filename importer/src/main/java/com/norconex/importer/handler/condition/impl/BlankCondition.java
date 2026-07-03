/* Copyright 2021-2025 Norconex Inc.
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

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.io.IoUtil;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldNameConstants;

/**
 * <p>
 * A condition based on whether the document content (default) or
 * any of the specified metadata fields are blank or inexistent.
 * For metadata fields,
 * control characters (char &lt;= 32) are removed before evaluating whether
 * their values are empty. Dealing with the document content will
 * instead check if it is {@code null} or empty (no bytes returned
 * when read).
 * </p>
 *
 * <h2>Multiple fields and values:</h2>
 * <p>
 * By default, ALL values for all fields matched by your field matcher
 * expression must be blank for this condition to be <code>true</code>.
 * You can change the logic to have ANY values to be blank for the condition
 * to be <code>true</code> with {@link #setMatchAnyBlank(boolean)}.
 * </p>
 * <p>
 * If no fields are matched, the conditions is also considered empty
 * (<code>true</code>).
 * </p>
 *
 * {@nx.xml.usage
 * <condition
 *     class="com.norconex.importer.handler.condition.impl.BlankCondition"
 *     matchAnyBlank="[false|true]">
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression matching fields we want to test if blank,
 *      instead of using the document content.)
 *   </fieldMatcher>
 * </condition>
 * }
 *
 * {@nx.xml.example
 * <condition class="BlankCondition">
 *   <fieldMatcher method="regex">(title|dc:title)</fieldMatcher>
 * </condition>
 * }
 * <p>
 * The above example condition will return <code>true</code> if both
 * "title" or "dc:title" are blank.
 * </p>
 * <p>
 */
@SuppressWarnings("javadoc")
@FieldNameConstants
@Data
public class BlankCondition
        implements ConfigurableCondition<BlankConditionConfig> {

    @Getter
    private final BlankConditionConfig configuration =
            new BlankConditionConfig();

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        // do content
        if (configuration.getFieldMatcher().getPattern() == null) {
            return IoUtil.isEmpty(docCtx.input().asInputStream());
        }

        // If no values returned, call it blank
        var values = docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).valueList();
        if (values.isEmpty()) {
            return true;
        }

        // if some fields are returned, check them all for blankness
        // one at a time
        if (configuration.isMatchAnyBlank()) {
            return values.stream().anyMatch(StringUtils::isBlank);
        }
        return values.stream().allMatch(StringUtils::isBlank);
    }
}
