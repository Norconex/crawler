/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.importer.handler.condition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.importer.ImporterRuntimeException;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.NonNull;

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.WRAPPER_OBJECT
)
@Data
public abstract class ConditionGroup implements Condition {
    @JsonSerialize(contentUsing = ConditionSerializer.class)
    @JsonDeserialize(contentUsing = ConditionDeserializer.class)
    //        @JsonUnwrapped
    final List<Condition> conditions = new ArrayList<>();

    ConditionGroup(@NonNull List<Condition> conditions) {
        CollectionUtil.setAll(this.conditions, conditions);
        CollectionUtil.removeNulls(this.conditions);
    }

    public List<Condition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    boolean execCondition(Condition cond, DocHandlerContext ctx) {
        try {
            return cond.test(ctx);
        } catch (IOException e) {
            throw new ImporterRuntimeException(
                    "Could not execute condition: "
                            + cond.getClass().getName(),
                    e);
        }
    }
}
