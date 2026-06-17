/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.core.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.SerializationException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.NonNull;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

//MAYBE: consider moving somewhere more generic if we see value.
public final class SerialUtil {

    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .changeDefaultPropertyInclusion(
                    v -> v.withValueInclusion(JsonInclude.Include.NON_EMPTY))
            .build();

    private SerialUtil() {
    }

    public static TokenStreamFactory jsonFactory() {
        return mapper.tokenStreamFactory();
    }

    public static JsonGenerator jsonGenerator(@NonNull OutputStream os) {
        return jsonGenerator(os, false);
    }

    public static JsonGenerator jsonGenerator(
            @NonNull OutputStream os, boolean pretty) {
        try {
            var writer = pretty
                    ? mapper.writer().withDefaultPrettyPrinter()
                    : mapper.writer();
            return writer.createGenerator(os, JsonEncoding.UTF8);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not create JsonGenerator.", e);
        }
    }

    public static JsonParser jsonParser(@NonNull InputStream is) {
        try {
            return mapper.createParser(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not create JsonParser.", e);
        }
    }

    public static String toJsonString(@NonNull Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not serialize object to JSON: " + object, e);
        }
    }

    public static Reader toJsonReader(@NonNull Object object) {
        return new StringReader(toJsonString(object));
    }

    public static <T> T fromJson(String json, @NonNull Class<T> cls) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, cls);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not deserialize JSON %s to object of type: %s"
                            .formatted(json, cls.getName()),
                    e);
        }
    }

    public static <T> T fromJson(@NonNull Reader json, @NonNull Class<T> cls) {
        try {
            return mapper.readValue(json, cls);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not deserialize JSON reader to object." + json, e);
        }
    }

    public static <T> T fromJson(
            @NonNull JsonParser json, @NonNull Class<T> cls) {
        try {
            return mapper.readValue(json, cls);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not deserialize JSON parser to object." + json, e);
        }
    }

    public static <T> T fromJson(
            @NonNull JsonNode json, @NonNull Class<T> cls) {
        try {
            return mapper.treeToValue(json, cls);
        } catch (JacksonException e) {
            throw new SerializationException(
                    "Could not deserialize JSON node to object." + json, e);
        }
    }

    /**
     * Returns the shared ObjectMapper instance.
     * @return the ObjectMapper
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}
