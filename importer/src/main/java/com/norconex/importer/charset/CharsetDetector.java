/* Copyright 2015-2026 Norconex Inc.
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
package com.norconex.importer.charset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.utils.CharsetUtils;

import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * Character set detector.
 */
@Slf4j
public class CharsetDetector {

    private final Charset declaredCharset;
    private final Charset fallbackCharset;
    private final Supplier<Charset> priorityCharset;

    public CharsetDetector(
            Charset declaredCharset,
            Charset fallbackCharset,
            Supplier<Object> priorityCharset) {
        this.declaredCharset = declaredCharset;
        this.fallbackCharset = ofNullable(fallbackCharset).orElse(UTF_8);
        if (priorityCharset != null) {
            this.priorityCharset = () -> {
                var obj = priorityCharset.get();
                if (obj instanceof Charset charset) {
                    return charset;
                }
                try {
                    return Charset.forName(Objects.toString(obj, null));
                } catch (Exception e) {
                    return null;
                }
            };
        } else {
            this.priorityCharset = null;
        }
    }

    public static CharsetDetectorBuilder builder() {
        return new CharsetDetectorBuilder();
    }

    /**
     * <p>
     * Detects a document character using the following logic, in order:
     * </p>
     * <ul>
     *   <li>
     *     If the document is {@code null}, returns the fallback charset
     *     (which is UTF-8 unless explicitly set to something different).
     *   </li>
     *   <li>
     *     If priority charset supplier is not {@code null} and returns
     *     a valid charset, returns that charset.
     *   </li>
     *   <li>
     *     If the charset has already been detected for the document, returns
     *     that charset ({@link Doc#getCharset()}).
     *   </li>
     *   <li>
     *     Try to detect the character set, using any declared charset
     *     to influence the outcome.
     *   </li>
     *   <li>
     *     If it can't be detected, returns the declared charset.
     *   </li>
     *   <li>
     *     If no declared charset, returns the fallback charset
     *     (UTF-8 by default).
     *   </li>
     * </ul>
     *
     * <p>
     * This method will NOT set the detected encoding on the {@link DocHandlerContext}.
     * </p>
     * @param doc document to detect encoding on when applicable
     * @return the detected charset (never {@code null}).
     * @throws IOException problem detecting charset
     */
    public Charset detect(Doc doc) throws IOException {
        if (doc == null) {
            return fallbackCharset;
        }
        return detect(doc.getInputStream(), doc.getCharset());
    }

    /**
     * <p>
     * Detects the character encoding of a string.
     * </p>
     * <p>
     * Detection occurs using the following logic, in order:
     * </p>
     * <ul>
     *   <li>
     *     If the string is {@code null},
     *     returns the fallback charset (which is UTF-8 unless explicitly set
     *     to something different).
     *   </li>
     *   <li>
     *     If priority charset supplier is not {@code null} and returns
     *     a valid charset, returns that charset.
     *   </li>
     *   <li>
     *     Try to detect the character set, using any declared charset
     *     to influence the outcome.
     *   </li>
     *   <li>
     *     If it can't be detected, returns the declared charset.
     *   </li>
     *   <li>
     *     If no declared charset, returns the fallback charset
     *     (UTF-8 by default).
     *   </li>
     * </ul>
     *
     * @param input the input stream to detect encoding on
     * @return the detected charset (never {@code null}).
     */
    public Charset detect(String input) {
        if (StringUtils.isBlank(input)) {
            return fallbackCharset;
        }
        try {
            // Use UTF_8 explicitly: Java Strings are Unicode internally;
            // using getBytes() (platform-default) causes non-deterministic
            // behavior across platforms and Tika versions.
            return detect(new ByteArrayInputStream(input.getBytes(UTF_8)));
        } catch (IOException e) {
            // We swallow and return fallback if it fails. Since
            // the input is a string, it should not happen.
            LOG.error("Could not read a string for charset detection.", e);
            return fallbackCharset;
        }
    }

    /**
     * <p>
     * Detects the character encoding of an input stream.
     * {@link InputStream#markSupported()} must return <code>true</code>
     * otherwise no decoding will be attempted and the fallback charset will
     * be returned.
     * </p>
     * <p>
     * Detection occurs using the following logic, in order:
     * </p>
     * <ul>
     *   <li>
     *     If the stream is {@code null} or does not support marking,
     *     returns the fallback charset (which is UTF-8 unless explicitly set
     *     to something different).
     *   </li>
     *   <li>
     *     If priority charset supplier is not {@code null} and returns
     *     a valid charset, returns that charset.
     *   </li>
     *   <li>
     *     Try to detect the character set, using any declared charset
     *     to influence the outcome.
     *   </li>
     *   <li>
     *     If it can't be detected, returns the declared charset.
     *   </li>
     *   <li>
     *     If no declared charset, returns the fallback charset
     *     (UTF-8 by default).
     *   </li>
     * </ul>
     *
     * @param input the input stream to detect encoding on
     * @return the detected charset (never {@code null}).
     * @throws IOException problem detecting charset
     */
    public Charset detect(InputStream input) throws IOException {
        return detect(input, null);
    }

    private Charset detect(InputStream input, Charset documentCharset)
            throws IOException {
        if (input == null) {
            return fallbackCharset;
        }

        var priority = ofNullable(priorityCharset).map(Supplier::get);
        if (priority.isPresent()) {
            return priority.get();
        }

        if (documentCharset != null) {
            return documentCharset;
        }

        if (!input.markSupported()) {
            LOG.warn(
                    "mark/reset not supported on input stream. "
                            + "Will not attempt to detect encoding.");
            return fallbackCharset;
        }

        var cd = new org.apache.tika.parser.txt.CharsetDetector();
        if (declaredCharset != null) {
            cd.setDeclaredEncoding(declaredCharset.toString());
        }
        cd.enableInputFilter(true);
        cd.setText(input);
        var detectedCharset = doDetect(cd);
        rewind(input);

        if (detectedCharset != null) {
            return detectedCharset;
        }

        if (declaredCharset != null) {
            return declaredCharset;
        }

        return fallbackCharset;
    }

    /*
     * We bias towards UTF-8: If UTF-8 is detected but not the highest, and
     * the highest is below 50% confidence, we check if there is less than
     * 20% difference in confidence between the highest and UTF-8 charset
     * and if so, we return UTF-8 instead.
     */
    private static Charset doDetect(
            org.apache.tika.parser.txt.CharsetDetector cd) {
        var matches = cd.detectAll();
        if (ArrayUtils.isEmpty(matches)) {
            return null;
        }

        String charset = null;

        var utf8 = StandardCharsets.UTF_8.toString();

        var firstMatch = matches[0];
        if (matches.length == 1
                || utf8.equalsIgnoreCase(firstMatch.getName())) {
            charset = firstMatch.getName();
        }

        if (charset == null || firstMatch.getConfidence() <= 50) {
            var utf8Match = Stream.of(matches)
                    .filter(m -> utf8.equalsIgnoreCase(m.getName()))
                    .findFirst();
            if (utf8Match.isPresent()
                    && firstMatch.getConfidence()
                            - utf8Match.get().getConfidence() <= 20) {
                charset = utf8;
            }
        }

        if (charset == null) {
            charset = firstMatch.getName();
        }

        LOG.debug("Detected encoding: {}", charset);
        return Charset.forName(charset);
    }

    private static void rewind(InputStream is) {
        //MAYBE: investigate why regular reset on CachedInputStream has
        //no effect and returns an empty stream when read again. Fix that
        //instead of having this method.
        //NOTE: Maybe already fixed. To confirm.
        if (is instanceof CachedInputStream cis) {
            cis.rewind();
        }
    }

    @Data
    @Accessors(fluent = true)
    public static class CharsetDetectorBuilder {
        private Charset declaredCharset;
        private Charset fallbackCharset;
        private Supplier<Object> priorityCharset;

        CharsetDetectorBuilder() {
        }

        public CharsetDetector build() {
            return new CharsetDetector(
                    declaredCharset, fallbackCharset, priorityCharset);
        }

        public CharsetDetectorBuilder declaredCharset(String charset) {
            if (StringUtils.isNotBlank(charset)) {
                declaredCharset = Charset.forName(CharsetUtils.clean(charset));
            }
            return this;
        }

        public CharsetDetectorBuilder declaredCharset(Charset charset) {
            declaredCharset = charset;
            return this;
        }

        public CharsetDetectorBuilder fallbackCharset(String charset) {
            if (StringUtils.isNotBlank(charset)) {
                fallbackCharset = Charset.forName(CharsetUtils.clean(charset));
            }
            return this;
        }

        public CharsetDetectorBuilder fallbackCharset(Charset charset) {
            fallbackCharset = charset;
            return this;
        }

        public CharsetDetectorBuilder priorityCharset(
                Supplier<Object> charsetSupplier) {
            priorityCharset = charsetSupplier;
            return this;
        }
    }
}
