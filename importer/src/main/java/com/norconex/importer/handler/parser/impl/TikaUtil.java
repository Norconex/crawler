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
package com.norconex.importer.handler.parser.impl;

import java.util.List;

import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import com.norconex.commons.lang.EqualsUtil;
import com.norconex.commons.lang.map.Properties;

class TikaUtil {

    private TikaUtil() {
    }

    static void metadataToProperties(
            Metadata tikaMeta, Properties metadata) {
        var names = tikaMeta.names();
        for (String name : names) {
            if (TikaCoreProperties.RESOURCE_NAME_KEY.getName().equals(name)) {
                continue;
            }
            // When Tika 3.x returns dc: prefixed metadata (e.g. dc:title),
            // also store it under the bare field name (e.g. title) to preserve
            // backward compatibility with callers expecting the plain name.
            var tikaValues = tikaMeta.getValues(name);
            var fieldNames = new java.util.ArrayList<String>();
            fieldNames.add(name);
            if (name.startsWith("dc:")) {
                fieldNames.add(name.substring(3));
            }
            for (String fieldName : fieldNames) {
                var nxValues = metadata.getStrings(fieldName);
                for (String tikaValue : tikaValues) {
                    if (!containsSameValue(fieldName, nxValues, tikaValue)) {
                        metadata.add(fieldName, tikaValue);
                    } else {
                        metadata.set(fieldName, tikaValue);
                    }
                }
            }
        }
    }

    private static boolean containsSameValue(
            String name, List<String> nxValues, String tikaValue) {
        if (EqualsUtil.equalsAnyIgnoreCase(
                name,
                HttpHeaders.CONTENT_TYPE.getName(),
                HttpHeaders.CONTENT_ENCODING.getName())) {
            var tk = tikaValue.replaceAll("[\\s]", "");
            for (String nxValue : nxValues) {
                if (nxValue.replaceAll("[\\s]", "").equalsIgnoreCase(tk)) {
                    return true;
                }
            }
            return false;
        }
        return nxValues.contains(tikaValue);
    }
}
