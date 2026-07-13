/* Copyright 2021-2024 Norconex Inc.
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

import org.apache.commons.lang3.math.NumberUtils;

import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldNameConstants;

/**
 * <p>
 * A condition based on the numeric value(s) of matching
 * metadata fields, supporting decimals. If multiple values are found for a
 * field, only one of them needs to match for this condition to be true.
 * If the value is not a valid number, it is considered not to be matching
 * (i.e., <code>false</code>).
 * The decimal character is expected to be a dot (".").
 * The default operator is "eq" (equals).
 * </p>
 * <h2>Single number vs range of numbers:</h2>
 * <p>
 * This condition accepts zero, one, or two value matchers:
 * </p>
 * <ul>
 *   <li>
 *     <b>0:</b> Use no value matcher to simply evaluate
 *     whether the value is a number (including decimal support).
 *   </li>
 *   <li>
 *     <b>1:</b> Use one value matcher to evaluate if the value is
 *     lower/greater and/or the same as the specified number.
 *   </li>
 *   <li>
 *     <b>2:</b> Use two value matchers to define a numeric range to evaluate
 *     (both matches have to evaluate to <code>true</code>).
 *   </li>
 * </ul>
 *
 * {@nx.include com.norconex.commons.lang.Operator#operators}
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.NumericCondition">
 *
 *   <fieldMatcher>
 *     (expression matching one or more numeric fields)
 *   </fieldMatcher>
 *
 *   <!-- Use two value matchers if you want to define a range. -->
 *   <valueMatcher operator="[gt|ge|lt|le|eq]" number="(number)" />
 * </condition>
 * }
 *
 * {@nx.xml.example
 * <condition class="NumericCondition">
 *   <fieldMatcher>age</fieldMatcher>
 *   <valueMatcher operator="ge" number="20" />
 *   <valueMatcher operator="lt" number="30" />
 *  </condition>
 * }
 * <p>
 * Let's say you are importing customer profile documents
 * and you have a field called "age" and you need to only consider documents
 * for customers in their twenties (greater or equal to
 * 20, but lower than 30). The above example would achieve that.
 * </p>
 *
 */
@FieldNameConstants
@Data
public class NumericCondition
        implements ConfigurableCondition<NumericConditionConfig> {

    @Getter
    private final NumericConditionConfig configuration =
            new NumericConditionConfig();

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        for (String valueStr : docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).valueList()) {
            if (!NumberUtils.isCreatable(valueStr)) {
                continue;
            }
            var number = NumberUtils.toDouble(valueStr);
            if (matches(configuration.getValueMatcher(), number)
                    && matches(
                            configuration.getValueMatcherRangeEnd(), number)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(NumericValueMatcher matcher, double number) {
        if (matcher != null) {
            return matcher.test(number);
        }
        return true;
    }
}
