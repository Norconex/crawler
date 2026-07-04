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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * A condition based on a text pattern matching a document reference (e.g. URL).
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.ReferenceCondition">
 *   <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (expression of reference value to match)
 *   </valueMatcher>
 * </condition>
 * }
 *
 * {@nx.xml.example
 * <condition class="ReferenceCondition">
 *   <valueMatcher method="regex">.*&#47;login/.*</valueMatcher>
 * </condition>
 * }
 * <p>
 * The above example reject documents having "/login/" in their reference.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@EqualsAndHashCode
@ToString
public class ReferenceCondition
        implements ConfigurableCondition<ReferenceConditionConfig> {

    @Getter
    private final ReferenceConditionConfig configuration =
            new ReferenceConditionConfig();

    public ReferenceCondition() {
    }

    public ReferenceCondition(TextMatcher valueMatcher) {
        configuration.setValueMatcher(valueMatcher);
    }

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        return configuration.getValueMatcher().matches(docCtx.reference());
    }
}
