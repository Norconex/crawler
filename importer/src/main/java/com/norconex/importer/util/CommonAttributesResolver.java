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
package com.norconex.importer.util;

import static com.norconex.importer.doc.DocMetaConstants.CONTENT_ENCODING;
import static com.norconex.importer.doc.DocMetaConstants.CONTENT_FAMILY;
import static com.norconex.importer.doc.DocMetaConstants.CONTENT_TYPE;

import java.io.IOException;

import com.norconex.importer.charset.CharsetDetector;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Detects common document attributes and set them as document metadata fields.
 * The attributes are only detected and set if it is not already present,
 * unless overwrite is <code>true</code>.
 * </p>
 * <p>
 * You can expect the following properties/fields to be set after using this
 * class.
 * </p>
 * <table>
 *   <caption>DocRecord property to metadata field mapping</caption>
 *   <tr>
 *     <th style="text-align:left; padding-right: 20px;">DocRecord property</th>
 *     <th style="text-align:left;">Metadata field</th>
 *   </tr>
 *   <tr>
 *     <td>contentEncoding</td><td>{@link DocMetaConstants#CONTENT_ENCODING}</td>
 *   </tr>
 *   <tr>
 *     <td>contentType</td><td>{@link DocMetaConstants#CONTENT_TYPE}</td>
 *   </tr>
 *   <tr>
 *     <td></td><td>{@link DocMetaConstants#CONTENT_FAMILY}</td>
 *   </tr>
 * </table>
 * @since 4.0.0
 */
@Slf4j
public final class CommonAttributesResolver {

    private CommonAttributesResolver() {
    }

    //TODO use early in importer (will do nothing if already set).

    public static void resolve(Doc doc) {
        resolve(doc, false);
    }

    public static void resolve(Doc doc, boolean overwrite) {

        resolveContenTypeAndFamily(doc, overwrite);
        resolveContentEncoding(doc, overwrite);

    }

    private static void resolveContenTypeAndFamily(Doc doc, boolean overwrite) {
        var meta = doc.getMetadata();

        // DocRecord Content-Type
        if (overwrite || doc.getContentType() == null) {
            try {
                doc.setContentType(ContentTypeDetector.detect(
                        doc.getInputStream(), doc.getReference()));
            } catch (IOException e) {
                LOG.warn("Could not perform content type detection.", e);
            }
        }

        // Metadata Content-Type
        var ct = doc.getContentType();
        if (overwrite || (meta.getString(CONTENT_TYPE) == null && ct != null)) {
            meta.set(CONTENT_TYPE, ct.toString());
        }

        // Metadata Content Family
        if (overwrite
                || (meta.getString(CONTENT_FAMILY) == null && ct != null)) {
            meta.set(CONTENT_FAMILY, ct.getContentFamily().toString());
        }
    }

    private static void resolveContentEncoding(Doc doc, boolean overwrite) {
        var meta = doc.getMetadata();

        // Doc Record character encoding
        if (overwrite || doc.getCharset() == null) {
            try {
                doc.setCharset(CharsetDetector.builder().build().detect(
                        doc.getInputStream()));
            } catch (IOException e) {
                LOG.warn("Could not perform character encoding detection.", e);
            }
        }

        // Metadata character encoding
        var enc = doc.getCharset();
        if (overwrite
                || (meta.getString(CONTENT_ENCODING) == null && enc != null)) {
            meta.set(CONTENT_ENCODING, enc);
        }
    }
}
