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

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.jsontype.TypeDeserializer;

public class ConditionDeserializer
        extends ValueDeserializer<Condition> {

    @Override
    public Condition deserialize(JsonParser p, DeserializationContext ctxt) {
        // Delegate to type-aware deserialization
        return deserializeWithType(p, ctxt, null);
    }

    @Override
    public Condition deserializeWithType(
            JsonParser p,
            DeserializationContext ctxt,
            TypeDeserializer typeDeserializer) {

        var currentToken = p.currentToken();
        if (p.currentToken() == JsonToken.END_OBJECT) {
            // Gracefully handle end of object
            return null;
        }

        if (currentToken == JsonToken.START_OBJECT) {
            p.nextToken(); // Move to the first field or END_OBJECT

            if (p.currentToken() == JsonToken.PROPERTY_NAME) {
                var name = p.currentName();

                if (!"class".equals(name)) {
                    p.nextToken(); // Move to the START_ARRAY or other token

                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        // Deserialize the array of conditions
                        List<Condition> conditions = new ArrayList<>();
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            var condition =
                                    ctxt.readValue(p, Condition.class);
                            if (condition != null) {
                                conditions.add(condition);
                            }
                        }
                        p.nextToken(); // move to object end

                        // Return a new `ConditionGroup`
                        if (AnyOf.NAME.equals(name)) {
                            return new AnyOf(conditions);
                        }
                        if (NoneOf.NAME.equals(name)) {
                            return new NoneOf(conditions);
                        }
                        if (AllOf.NAME.equals(name)) {
                            return new AllOf(conditions);
                        }

                        // should not reach here
                        return null;
                    }
                }
            }
        }

        // Fallback: Direct deserialization (unwrapped)
        return ctxt.readValue(p, Condition.class);
    }
}
