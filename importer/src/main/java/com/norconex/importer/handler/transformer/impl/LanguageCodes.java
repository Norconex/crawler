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
package com.norconex.importer.handler.transformer.impl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts between three-letter (ISO 639-2/3) and two-letter (ISO 639-1)
 * language codes. Languages that have no two-letter code are left as is.
 */
final class LanguageCodes {

    private static final Map<String, String> TO_TWO = new HashMap<>();
    private static final Map<String, String> TO_THREE = new HashMap<>();

    static {
        for (var two : Locale.getISOLanguages()) {
            var three = Locale.of(two).getISO3Language();
            TO_TWO.put(three, two);
            TO_THREE.put(two, three);
        }
        // Codes used by detectors that differ from the standard
        // one-to-one mapping.
        TO_TWO.put("nob", "no"); // Norwegian Bokmal (Optimaize uses "no")
        TO_TWO.put("ydd", "yi"); // Eastern Yiddish
        TO_TWO.put("swh", "sw"); // Swahili (individual language)
        TO_TWO.put("cmn", "zh"); // Mandarin
    }

    private LanguageCodes() {
    }

    /**
     * Gets the two-letter code for a three-letter one, when there is one.
     * Anything else (null, two-letter codes, codes without two-letter
     * equivalent) is returned unchanged.
     * @param code language code
     * @return two-letter code, or the code as received
     */
    static String toTwoLetters(String code) {
        if (code == null || code.length() != 3) {
            return code;
        }
        return TO_TWO.getOrDefault(code.toLowerCase(Locale.ROOT), code);
    }

    /**
     * Gets the three-letter code for a two-letter one (a region suffix such
     * as in "zh-cn" is ignored), when there is one.
     * @param code language code
     * @return three-letter code, or the code as received
     */
    static String toThreeLetters(String code) {
        if (code == null) {
            return null;
        }
        var lower = code.toLowerCase(Locale.ROOT);
        var dash = lower.indexOf('-');
        var base = dash < 0 ? lower : lower.substring(0, dash);
        return TO_THREE.getOrDefault(base, code);
    }
}
