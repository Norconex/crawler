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
package com.norconex.importer.handler;

import java.util.List;

import javax.xml.namespace.QName;

import org.apache.commons.text.WordUtils;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.ConditionGroup;
import com.norconex.importer.handler.condition.ConditionalDocHandler;
import com.norconex.importer.handler.condition.If;
import com.norconex.importer.handler.condition.IfNot;

public class DocHandlerListSerializer
        extends ValueSerializer<List<DocHandler>> {

    @Override
    public void serialize(
            List<DocHandler> handlers,
            JsonGenerator gen,
            SerializationContext sp) {

        if (gen instanceof ToXmlGenerator xmlGen) {
            writeXmlDocHandlerList(handlers, xmlGen, true);
            return;
        }

        gen.writeStartArray();
        for (var handler : handlers) {
            if ((handler instanceof If) || (handler instanceof IfNot)) {
                gen.writePOJO(handler);
            } else {
                gen.writeStartObject();
                gen.writeName(DocHandler.NAME);
                gen.writePOJO(handler);
                gen.writeEndObject();
            }
        }
        gen.writeEndArray();
    }

    private void writeXmlDocHandlerList(
            List<DocHandler> handlers,
            ToXmlGenerator gen,
            boolean isRoot) {
        var first = true;
        for (var handler : handlers) {
            if ((handler instanceof ConditionalDocHandler condHandler)) {
                writeXmlConditionalHandler(
                        condHandler.getName(), condHandler, gen);
            } else {
                // no idea why, but first field name can't be written.
                if (!isRoot || !first) {
                    gen.writeName(DocHandler.NAME);
                } else {
                    gen.setNextName(QName.valueOf(DocHandler.NAME));
                }
                gen.writePOJO(handler);
            }
            first = false;
        }
    }

    private void writeXmlConditionalHandler(
            String tagName, ConditionalDocHandler condHandler,
            ToXmlGenerator gen) {
        gen.writeRaw("<%s>".formatted(tagName));
        gen.flush();

        writeXmlCondition(condHandler.getCondition(), gen);

        gen.writeRaw("<then>");
        gen.flush();
        writeXmlDocHandlerList(condHandler.getThenHandlers(), gen, false);
        gen.writeRaw("</then>");
        gen.flush();

        if (!condHandler.getElseHandlers().isEmpty()) {
            gen.writeRaw("<else>");
            gen.flush();
            writeXmlDocHandlerList(condHandler.getElseHandlers(), gen, false);
            gen.writeRaw("</else>");
            gen.flush();
        }
        gen.writeRaw("</%s>".formatted(tagName));
        gen.flush();
    }

    private void writeXmlCondition(
            Condition condition, ToXmlGenerator gen) {
        if (condition instanceof ConditionGroup condGroup) {
            var tag = WordUtils
                    .uncapitalize(condGroup.getClass().getSimpleName());
            gen.writeRaw("<condition>");
            gen.writeRaw("<%s>".formatted(tag));
            gen.flush();
            for (var cond : condGroup.getConditions()) {
                gen.writeName("condition");
                writeXmlCondition(cond, gen);
            }
            gen.writeRaw("</%s>".formatted(tag));
            gen.writeRaw("</condition>");
            gen.flush();
        } else {
            gen.setNextName(QName.valueOf("condition"));
            gen.writePOJO(condition);
        }
    }
}
