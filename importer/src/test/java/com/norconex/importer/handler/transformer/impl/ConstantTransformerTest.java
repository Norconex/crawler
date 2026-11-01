/* Copyright 2010-2026 Norconex Inc.
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

import java.io.InputStream;
import java.util.List;

import org.apache.commons.io.input.NullInputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.TestUtil;
import java.io.IOException;

@Timeout(30)
@SuppressWarnings("null")
class ConstantTransformerTest {

    @Test
    void testWriteRead() {
        var t = new ConstantTransformer();
        t.getConfiguration()
                .setConstants(
                        List.of(
                                Constant.of(
                                        "constant1",
                                        List.of("value1", "value2")),
                                Constant.of(
                                        "constant2",
                                        List.of("valueA", "valueA")),
                                Constant.of("constant3", "valueZ")))
                .setOnSet(PropertySetter.REPLACE);
        BeanMapper.DEFAULT.assertWriteRead(t);
    }

    @Test
    void testOnSet() throws IOException {
        var m = new Properties();
        m.add("test1", "1");
        m.add("test1", "2");
        m.add("test2", "1");
        m.add("test2", "2");
        m.add("test3", "1");
        m.add("test3", "2");
        m.add("test4", "1");
        m.add("test4", "2");

        var t = new ConstantTransformer();
        InputStream is;

        // APPEND
        t.getConfiguration()
                .setOnSet(PropertySetter.APPEND)
                .setConstants(List.of(Constant.of("test1", List.of("3", "4"))));

        is = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", is, m));
        Assertions.assertArrayEquals(
                new String[] {
                        "1", "2", "3", "4" },
                m.getStrings("test1").toArray());
        // REPLACE
        t.getConfiguration()
                .setOnSet(PropertySetter.REPLACE)
                .setConstants(List.of(Constant.of("test2", List.of("3", "4"))));
        is = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", is, m));
        Assertions.assertArrayEquals(
                new String[] {
                        "3", "4" },
                m.getStrings("test2").toArray());
        // OPTIONAL
        t.getConfiguration()
                .setOnSet(PropertySetter.OPTIONAL)
                .setConstants(List.of(Constant.of("test3", List.of("3", "4"))));
        is = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", is, m));
        Assertions.assertArrayEquals(
                new String[] {
                        "1", "2" },
                m.getStrings("test3").toArray());
        // PREPEND
        t.getConfiguration()
                .setOnSet(PropertySetter.PREPEND)
                .setConstants(List.of(Constant.of("test4", List.of("3", "4"))));
        is = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", is, m));
        Assertions.assertArrayEquals(
                new String[] {
                        "3", "4", "1", "2" },
                m.getStrings("test4").toArray());
    }
}
