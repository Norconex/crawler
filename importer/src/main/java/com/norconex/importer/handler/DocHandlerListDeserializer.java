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
package com.norconex.importer.handler;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.jsontype.TypeDeserializer;
import tools.jackson.dataformat.xml.deser.XmlDeserializationContext;
import com.norconex.commons.lang.ClassUtil;
import com.norconex.importer.handler.condition.AllOf;
import com.norconex.importer.handler.condition.AnyOf;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.ConditionGroup;
import com.norconex.importer.handler.condition.ConditionalDocHandler;
import com.norconex.importer.handler.condition.If;
import com.norconex.importer.handler.condition.IfNot;
import com.norconex.importer.handler.condition.NoneOf;

public class DocHandlerListDeserializer
        extends ValueDeserializer<List<DocHandler>> {

    @Override
    public List<DocHandler> deserialize(JsonParser p,
            DeserializationContext ctxt) {
        // Delegate to type-aware deserialization
        return deserializeWithType(p, ctxt, null);
    }

    @Override
    public List<DocHandler> deserializeWithType(
            JsonParser p,
            DeserializationContext ctxt,
            TypeDeserializer typeDeserializer) {

        if (p.currentToken() == JsonToken.END_OBJECT) {
            // Should not get here, but just in case, handle end of object
            return List.of();
        }

        if (ctxt instanceof XmlDeserializationContext) {
            return readXmlDocHandlerList(p, ctxt);
        }

        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<DocHandler> handlers = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                // Process an array entry: either a handler or if/ifNot
                if (p.currentToken() == JsonToken.START_OBJECT) {
                    p.nextToken(); // Move to the first field or END_OBJECT
                    if (p.currentToken() == JsonToken.PROPERTY_NAME) {
                        var name = p.currentName();
                        if (If.NAME.equals(name)) {
                            var ifHandler = ctxt.readValue(p, If.class);
                            handlers.add(ifHandler);
                        } else if (IfNot.NAME.equals(name)) {
                            var ifnotHandler = ctxt.readValue(p, IfNot.class);
                            handlers.add(ifnotHandler);
                        } else {
                            // dealing with handler, move to object start
                            p.nextToken();
                            var handler = ctxt.readValue(p, DocHandler.class);
                            if (handler != null) {
                                handlers.add(handler);
                            }
                            p.nextToken(); // move to object end
                        }
                    }
                }
            }
            return handlers;
        }
        // should not reach here
        return null;
    }

    private List<DocHandler> readXmlDocHandlerList(
            JsonParser p,
            DeserializationContext ctxt) {

        List<DocHandler> handlers = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            var name = p.currentName();
            if (If.NAME.equals(name)) {
                handlers.add(readXmlConditionalHandler(If.class, p, ctxt));
            } else if (IfNot.NAME.equals(name)) {
                handlers.add(readXmlConditionalHandler(IfNot.class, p, ctxt));
            } else if (DocHandler.NAME.equals(name)) {
                // dealing with handler, move to object start
                p.nextToken();
                var handler = ctxt.readValue(p, DocHandler.class);
                if (handler != null) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private ConditionalDocHandler readXmlConditionalHandler(
            Class<? extends ConditionalDocHandler> cls,
            JsonParser p,
            DeserializationContext ctxt) {

        ConditionalDocHandler condHandler = ClassUtil.newInstance(cls);

        p.nextToken(); // move to conditional handler object start

        while (p.nextToken() != JsonToken.END_OBJECT) {
            var name = p.currentName();
            if ("condition".equals(name)) {
                condHandler.setCondition(readXmlCondition(p, ctxt));
            } else if ("then".equals(name)) {
                p.nextToken(); // move to start object for list
                condHandler.setThenHandlers(readXmlDocHandlerList(p, ctxt));
            } else if ("else".equals(name)) {
                p.nextToken(); // move to start object for list
                condHandler.setElseHandlers(readXmlDocHandlerList(p, ctxt));
            }
        }
        return condHandler;
    }

    private Condition readXmlCondition(
            JsonParser p,
            DeserializationContext ctxt) {

        // next token is either a class or a condition group
        p.nextToken();

        // advance to group object start or "class":
        p.nextToken();

        var name = p.currentName();
        if (AnyOf.NAME.equals(name)) {
            return readXmlConditionGroup(AnyOf.class, p, ctxt);
        }
        if (AllOf.NAME.equals(name)) {
            return readXmlConditionGroup(AllOf.class, p, ctxt);
        }
        if (NoneOf.NAME.equals(name)) {
            return readXmlConditionGroup(NoneOf.class, p, ctxt);
        }

        return ctxt.readValue(p, Condition.class);
    }

    private Condition readXmlConditionGroup(
            Class<? extends ConditionGroup> cls,
            JsonParser p,
            DeserializationContext ctxt) {

        List<Condition> conditions = new ArrayList<>();
        p.nextToken(); // move to first child condition
        while (p.nextToken() != JsonToken.END_OBJECT) {
            conditions.add(readXmlCondition(p, ctxt));
        }
        p.nextToken(); // move to object end (of "array")
        return ClassUtil.newInstance(cls, conditions);
    }
}
