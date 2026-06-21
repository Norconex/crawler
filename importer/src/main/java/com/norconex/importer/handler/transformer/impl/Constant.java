/* Copyright 2023-2024 Norconex Inc.
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
package com.norconex.importer.handler.transformer.impl;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.norconex.commons.lang.map.PropertySetter;

import lombok.Data;
import lombok.NonNull;

@Data
public class Constant {

    private final String name;
    private final List<String> values;
    private final PropertySetter onSet;

    private Constant(String name, List<String> values, PropertySetter onSet) {
        this.name = name;
        this.values = values;
        this.onSet = onSet;
    }

    public static Constant of(@NonNull String name, @NonNull String value) {
        return of(name, List.of(value), null);
    }

    public static Constant of(
            @NonNull String name, @NonNull List<String> values) {
        return of(name, values, null);
    }

    @JsonCreator
    public static Constant of(
            @JsonProperty("name") @NonNull String name,
            @JsonProperty("values") @NonNull List<String> values,
            @JsonProperty("onSet") PropertySetter onSet) {
        return new Constant(name, values, onSet);
    }
}
