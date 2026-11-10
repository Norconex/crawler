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

import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataCondition;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Accepts or rejects a reference based on a condition on its metadata
 * (text, number, date, blank, and groups of those).
 * </p>
 */
@Data
@Accessors(chain = true)
public class ConditionMetadataFilterConfig {

    /**
     * What to do when the condition is met. When not set, a reference
     * matching the condition is included (and one that does not match
     * is excluded).
     */
    private OnMatch onMatch;

    /**
     * The condition to test. When not set, every reference is accepted.
     */
    private MetadataCondition condition;
}
