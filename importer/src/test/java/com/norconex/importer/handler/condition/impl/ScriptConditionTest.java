/* Copyright 2022-2026 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.ScriptRunner;

@Timeout(30)
@SuppressWarnings("null")
class ScriptConditionTest {

    @ParameterizedTest
    @ArgumentsSource(SimpleProvider.class)
    void testScriptCondition(String engineName, String script)
            throws IOException {
        var cond = Configurable.configure(
                new ScriptCondition(), c -> c
                        .setEngineName(engineName)
                        .setScript(script));

        var htmlFile = TestUtil.getAliceHtmlFile();
        InputStream is = new BufferedInputStream(new FileInputStream(htmlFile));
        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        var returnValue = cond.test(
                TestUtil.newHandlerContext(
                        htmlFile.getAbsolutePath(), is, metadata));
        is.close();
        Assertions.assertTrue(returnValue);
    }

    static class SimpleProvider implements ArgumentsProvider {
        @Override
        public Stream<Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    Arguments.of(ScriptRunner.JAVASCRIPT_ENGINE, """
                        returnValue = metadata.getString('character') == 'Alice'
                            || content.indexOf('Alice') > -1;
                        """),
                    Arguments.of(ScriptRunner.LUA_ENGINE, """
                        returnValue = metadata:getString('character') == 'Alice'
                            or content:find('Alice') ~= nil;
                        """),
                    //Arguments.of(ScriptRunner.PYTHON_ENGINE, """
                    //    returnValue = metadata.getString('character') == 'Alice' \
                    //        or content.__contains__('Alice');
                    //    """),
                    Arguments.of(ScriptRunner.VELOCITY_ENGINE, """
                            #set($returnValue =
                                $metadata.getString("character") == "Alice"
                                    || $content.contains("Alice"))
                            """));
        }
    }

    @Test
    void testWriteRead() {
        BeanMapper.DEFAULT
                .assertWriteRead(Configurable.configure(
                        new ScriptCondition(),
                        c -> c.setEngineName(
                                ScriptRunner.JAVASCRIPT_ENGINE)
                                .setScript("returnValue = blah == 'blah';")));
    }

    @Test
    void testExecuteScript() throws IOException {
        var cond = new ScriptCondition();
        cond.getConfiguration().setEngineName(null);
        cond.getConfiguration().setScript(
                "returnValue = content == 'potato';");

        var ctx = TestUtil.newHandlerContext("ref", "potato");
        var returnValue = cond.test(ctx);

        assertThat(returnValue).isTrue();
    }

    @Test
    void testNoScriptMustThrow() {
        var cond = new ScriptCondition();
        cond.getConfiguration().setScript(null);

        var ctx = TestUtil.newHandlerContext("ref", "potato");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> cond.test(ctx));
    }
}
