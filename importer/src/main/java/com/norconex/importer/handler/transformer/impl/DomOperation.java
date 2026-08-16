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

import com.norconex.commons.lang.map.PropertySetter;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * DOM operation details.
 */
@Data
@Accessors(chain = true)
public class DomOperation {

    private String selector;

    /**
     * The target field for extracted content.
     * Not applicable if delete is <code>true</code>.
     * @param toField target field.
     * @return target field
     */
    private String toField;

    /**
     * The gets the property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    private String extract;

    /**
     * The gets whether elements with blank values should be considered a
     * match and have an empty string returned as opposed to nothing at all.
     * @param matchBlanks <code>true</code> to support elements with
     *                    blank values
     * @return <code>true</code> if elements with blank values are supported
     */
    private boolean matchBlanks;

    /**
     * The gets whether to delete DOM attributes/elements matching the
     * specified selector.
     * @param delete <code>true</code> if deleting
     * @return <code>true</code> if deleting
     */
    private boolean delete;

    /**
     * The value to store in the target field if there are no matches.
     * Not applicable if delete is <code>true</code>.
     * @param defaultValue default value
     * @return default value
     */
    private String defaultValue;
}
