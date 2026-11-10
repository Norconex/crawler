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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.importer.handler.condition.Condition;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Base class of conditions made of other conditions. Groups can be nested.
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public abstract class MetadataConditionGroup extends MetadataCondition {

    private final List<MetadataCondition> conditions = new ArrayList<>();

    /**
     * Gets the conditions of this group.
     * @return conditions (never {@code null})
     */
    public List<MetadataCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    /**
     * Sets the conditions of this group. Copies them, ignoring {@code null}
     * ones.
     * @param conditions conditions
     * @return this instance
     */
    public MetadataConditionGroup setConditions(
            List<MetadataCondition> conditions) {
        CollectionUtil.setAll(this.conditions, conditions);
        CollectionUtil.removeNulls(this.conditions);
        return this;
    }

    final Condition toGroup(Function<List<Condition>, Condition> groupMaker) {
        return groupMaker.apply(
                conditions.stream().map(MetadataCondition::toCondition)
                        .toList());
    }
}
