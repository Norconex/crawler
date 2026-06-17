/* Copyright 2025 Norconex Inc.
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerListDeserializer;
import com.norconex.importer.handler.DocHandlerListSerializer;

import lombok.Data;
import lombok.NonNull;

/**
 * Conditionally execute one or more doc handlers (possibly including other
 * conditional doc handlers) upon matching a condition, or not. Conditional
 * handlers effectively allows you to perform if/then/else using objects,
 * facilitating basic decision branches in Importer flow configuration.
 */
@Data
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.WRAPPER_OBJECT
)
public abstract class ConditionalDocHandler implements DocHandler {

    @JsonIgnore
    private final boolean negated;

    @JsonSerialize(using = ConditionSerializer.class)
    @JsonDeserialize(using = ConditionDeserializer.class)
    private Condition condition;

    @JsonProperty("then")
    @JsonSerialize(using = DocHandlerListSerializer.class)
    @JsonDeserialize(using = DocHandlerListDeserializer.class)
    private final List<DocHandler> thenHandlers = new ArrayList<>();

    @JsonProperty("else")
    @JsonSerialize(using = DocHandlerListSerializer.class)
    @JsonDeserialize(using = DocHandlerListDeserializer.class)
    private final List<DocHandler> elseHandlers = new ArrayList<>();

    protected ConditionalDocHandler(boolean negated) {
        this(null, negated);
    }

    protected ConditionalDocHandler(Condition condition, boolean negated) {
        this.condition = condition;
        this.negated = negated;
    }

    public abstract String getName();

    public List<DocHandler> getThenHandlers() {
        return Collections.unmodifiableList(thenHandlers);
    }

    public ConditionalDocHandler
            setThenHandlers(List<DocHandler> thenHandlers) {
        CollectionUtil.setAll(this.thenHandlers, thenHandlers);
        CollectionUtil.removeNulls(this.thenHandlers);
        return this;
    }

    public List<DocHandler> getElseHandlers() {
        return Collections.unmodifiableList(elseHandlers);
    }

    public ConditionalDocHandler
            setElseHandlers(List<DocHandler> elseHandlers) {
        CollectionUtil.setAll(this.elseHandlers, elseHandlers);
        CollectionUtil.removeNulls(this.elseHandlers);
        return this;
    }

    @Override
    public boolean handle(@NonNull DocHandlerContext docHandlerContext)
            throws IOException {
        var conditionMet =
                condition != null && condition.test(docHandlerContext);
        if (negated) {
            conditionMet = !conditionMet;
        }
        if (conditionMet) {
            return executeHandlers(thenHandlers, docHandlerContext);
        }
        return executeHandlers(elseHandlers, docHandlerContext);
    }

    private boolean executeHandlers(
            List<DocHandler> handlers, DocHandlerContext ctx)
            throws IOException {
        for (DocHandler handler : handlers) {
            if (!ctx.executeDocHandler(handler)) {
                return false;
            }
        }
        return true;
    }
}
