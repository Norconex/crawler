/* Copyright 2020-2024 Norconex Inc.
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
package com.norconex.importer.handler.parser;

/**
 * <p>
 * Act as a flag indicating if a document has been parsed or not in
 * a given process flow.
 * </p>
 * <p>
 * Typically, a pre-parsed document still has its original format
 * (HTML, PDF, etc.). A post-parsed document on the other hand,
 * usually has its plain text extracted and all formating is gone.
 * Pre-parsed documents can be in binary format while post-parsed
 * onces are in text-format (unless parsing was explicitely disabled).
 * </p>
 */
public enum ParseState {

    PRE, POST;

    public boolean isPre() {
        return this == PRE;
    }

    public boolean isPost() {
        return this == POST;
    }

    public static boolean isPre(ParseState parseState) {
        return parseState == null || parseState == PRE;
    }

    public static boolean isPost(ParseState parseState) {
        return parseState != null && parseState == POST;
    }
}
