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
package com.norconex.importer.handler;

import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.ConditionDeserializer;
import com.norconex.importer.handler.condition.ConditionSerializer;

import lombok.Data;
import lombok.experimental.Accessors;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Shared base class for document handler configuration beans that support an
 * optional user-supplied label, and conditions under which the handler applies.
 * <p>
 * A handler with a <code>when</code> condition only runs for documents that
 * meet it; one with an <code>unless</code> condition only runs for those that
 * do not. With both, a document must meet <code>when</code> and not meet
 * <code>unless</code>. Where the condition does not hold, the handler is
 * skipped as if it was not there: the document is passed to the next handler
 * untouched. It is a shorter way to write a handler on its own inside an
 * <code>if</code> with nothing in its <code>else</code>. For several handlers
 * under the same condition, or for something to do otherwise, use
 * <code>if</code> or <code>ifNot</code>.
 * </p>
 */
@Data
@Accessors(chain = true)
public class BaseDocHandlerConfig extends BaseLabelableConfig {

    /**
     * A condition the document must meet for this handler to run. Without
     * one, the handler runs on every document it is made for.
     * @param when the condition
     * @return the condition
     */
    @JsonSerialize(using = ConditionSerializer.class)
    @JsonDeserialize(using = ConditionDeserializer.class)
    private Condition when;

    /**
     * A condition the document must not meet for this handler to run.
     * Without one, the handler runs on every document it is made for.
     * @param unless the condition
     * @return the condition
     */
    @JsonSerialize(using = ConditionSerializer.class)
    @JsonDeserialize(using = ConditionDeserializer.class)
    private Condition unless;
}
