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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.norconex.importer.handler.condition.ConditionalDocHandler;

/**
 * Where each handler of an importer's configuration is: its index in the list
 * of handlers, and for one inside a condition, the condition's position, then
 * {@code then} or {@code else}, then its index there ({@code "3"},
 * {@code "5.then.2"}). Worked out from the configuration alone, so it is the
 * same wherever the same configuration runs, and it tells apart two handlers
 * that are otherwise alike. Labels cannot do that: they are optional, and
 * nothing keeps two from being the same.
 */
public final class HandlerPositions {

    private HandlerPositions() {
    }

    /**
     * The position of every handler in the given list, the ones inside
     * conditions included.
     * @param handlers an importer's handlers
     * @return each handler's position, by identity
     */
    public static Map<DocHandler, String> of(List<DocHandler> handlers) {
        Map<DocHandler, String> positions = new IdentityHashMap<>();
        add(positions, handlers, "");
        return Collections.unmodifiableMap(positions);
    }

    private static void add(Map<DocHandler, String> positions,
            List<DocHandler> handlers, String prefix) {
        if (handlers == null) {
            return;
        }
        for (var i = 0; i < handlers.size(); i++) {
            var handler = handlers.get(i);
            var position = prefix + i;
            positions.putIfAbsent(handler, position);
            if (handler instanceof ConditionalDocHandler cond) {
                add(positions, cond.getThenHandlers(), position + ".then.");
                add(positions, cond.getElseHandlers(), position + ".else.");
            }
        }
    }
}
