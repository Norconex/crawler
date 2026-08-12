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

    /*
     * Element names are written one of two ways, depending on generator state.
     * Raw writes (used for the flow-control tags) do not advance that state, so
     * it is tracked here instead: until the first POJO of an element has been
     * written, the generator is positioned on a value and only setNextName
     * applies; afterwards it expects a property name and writeName applies.
     * "atStart" carries that distinction down the recursion.
     */
    private void writeXmlName(
            String name, ToXmlGenerator gen, boolean atStart) {
        if (atStart) {
            gen.setNextName(QName.valueOf(name));
        } else {
            gen.writeName(name);
        }
    }

    private void writeXmlDocHandlerList(
            List<DocHandler> handlers,
            ToXmlGenerator gen,
            boolean atStart) {
        var first = atStart;
        for (var handler : handlers) {
            if ((handler instanceof ConditionalDocHandler condHandler)) {
                writeXmlConditionalHandler(
                        condHandler.getName(), condHandler, gen, first);
            } else {
                writeXmlName(DocHandler.NAME, gen, first);
                gen.writePOJO(handler);
            }
            first = false;
        }
    }

    private void writeXmlConditionalHandler(
            String tagName, ConditionalDocHandler condHandler,
            ToXmlGenerator gen, boolean atStart) {
        gen.writeRaw("<%s>".formatted(tagName));
        gen.flush();

        writeXmlCondition(condHandler.getCondition(), gen, atStart);

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
            Condition condition, ToXmlGenerator gen, boolean atStart) {
        if (condition instanceof ConditionGroup condGroup) {
            var tag = WordUtils
                    .uncapitalize(condGroup.getClass().getSimpleName());
            gen.writeRaw("<condition>");
            gen.writeRaw("<%s>".formatted(tag));
            gen.flush();
            var first = atStart;
            for (var cond : condGroup.getConditions()) {
                // each child writes its own "condition" name
                writeXmlCondition(cond, gen, first);
                first = false;
            }
            gen.writeRaw("</%s>".formatted(tag));
            gen.writeRaw("</condition>");
            gen.flush();
        } else {
            writeXmlName("condition", gen, atStart);
            gen.writePOJO(condition);
        }
    }
}
