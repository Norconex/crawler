/* Copyright 2017-2026 Norconex Inc.
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
package com.norconex.importer.handler;

import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.META_FORMAT_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.RegexFieldValueExtractor;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.parser.ParseState;
import com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig;
import com.norconex.importer.util.ExternalApp;

@Timeout(30)
public abstract class AbstractExternalTest {

    static final String INPUT = "1 2 3\n4 5 6\n7 8 9";
    static final String EXPECTED_OUTPUT = "3 2 1\n6 5 4\n9 8 7";

    private final Supplier<Configurable<ExternalTransformerConfig>> supplier;

    protected AbstractExternalTest(
            Supplier<Configurable<ExternalTransformerConfig>> supplier) {
        this.supplier = supplier;
    }

    @Test
    public void testWriteRead() {
        Map<String, String> setEnvs = new HashMap<>();
        setEnvs.put("env1", "value1");
        setEnvs.put("env2", "value2");
        setEnvs.put("env3", "value3");
        setEnvs.put("env4", "value4");
        setEnvs.put("env5", "value5");

        var t = supplier.get();
        t.getConfiguration()
                .setCommand("my command")
                .setTempDir(Paths.get("/some-path"))
                .setMetadataInputFormat("json")
                .setMetadataOutputFormat("xml")
                .setExtractionPatterns(
                        List.of(
                                new RegexFieldValueExtractor("aaa.*", "111"),
                                new RegexFieldValueExtractor("bbb.*", "222"),
                                new RegexFieldValueExtractor("ccc.*", "333"),
                                new RegexFieldValueExtractor("ddd.*", "444",
                                        2)))
                .setEnvironmentVariables(setEnvs);

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(t));
    }

    @Test
    public void testInFileOutFile()
            throws IOException {
        testWithExternalApp("-ic ${INPUT} -oc ${OUTPUT} -ref ${REFERENCE}");
    }

    @Test
    public void testInFileStdout()
            throws IOException {
        testWithExternalApp("-ic ${INPUT}");
    }

    @Test
    public void testStdinOutFile()
            throws IOException {
        testWithExternalApp("-oc ${OUTPUT} -ref ${REFERENCE}");
    }

    @Test
    public void testStdinStdout()
            throws IOException {
        testWithExternalApp("");
    }

    @Test
    public void testMetaInputOutputFiles()
            throws IOException {
        testWithExternalApp("""
                -ic ${INPUT} -oc ${OUTPUT}\s\
                -im ${INPUT_META} -om ${OUTPUT_META}\s\
                -ref ${REFERENCE}""", true);
    }

    private void testWithExternalApp(String command)
            throws IOException {
        testWithExternalApp(command, false);
    }

    private void testWithExternalApp(String command, boolean metaFiles)
            throws IOException {
        var input = inputAsStream();
        var metadata = new Properties();
        if (metaFiles) {
            metadata.set(
                    "metaFileField1", "this is a first test");
            metadata.set(
                    "metaFileField2",
                    "this is a second test value1",
                    "this is a second test value2");
        }

        var t = supplier.get();
        var cfg = t.getConfiguration();
        cfg.setCommand(ExternalApp.newCommandLine(command));
        addPatternsAndEnvs(cfg);
        cfg
                .setMetadataInputFormat(META_FORMAT_PROPERTIES)
                .setMetadataOutputFormat(META_FORMAT_PROPERTIES)
                .setOnSet(PropertySetter.REPLACE);
        var doc = TestUtil.newHandlerContext(
                "c:\\ref with spaces\\doc.txt", input,
                metadata, ParseState.PRE);
        ((DocHandler) t).handle(doc);

        var content = doc.input().asString();
        // remove any stdout content that could be mixed with output to
        // properly validate
        content = content.replace("field1:StdoutBefore", "");
        content = content.replace("<field2>StdoutAfter</field2>", "");
        content = content.trim();

        Assertions.assertEquals(EXPECTED_OUTPUT, content);
        if (metaFiles) {
            assertMetadataFiles(metadata);
        } else {
            assertMetadata(metadata, command.contains("${REFERENCE}"));
        }
    }

    private void assertMetadataFiles(Properties meta) {
        Assertions.assertEquals(
                "test first a is this", meta.getString("metaFileField1"));
        Assertions.assertEquals(
                "value1 test second a is this",
                meta.getStrings("metaFileField2").get(0));
        Assertions.assertEquals(
                "value2 test second a is this",
                meta.getStrings("metaFileField2").get(1));
    }

    private void assertMetadata(Properties meta, boolean testReference) {
        Assertions.assertEquals("StdoutBefore", meta.getString("field1"));
        var field2 = meta.getString("field2");
        var maxAttempts = 100;
        while (field2 == null && --maxAttempts >= 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            field2 = meta.getString("field2");
        }
        Assertions.assertEquals("StdoutAfter", field2);
        Assertions.assertEquals(
                "field3 StdErrBefore",
                meta.getString("field3"));
        var field4 = meta.getString("field4");
var maxAttemptsField4 = 100;
        while (field4 == null && --maxAttemptsField4 >= 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            field4 = meta.getString("field4");
        }
        Assertions.assertEquals("StdErrAfter", field4);
        if (testReference) {
            Assertions.assertEquals(
                    "c:\\ref with spaces\\doc.txt",
                    meta.getString("reference"));
        }
    }

    private void addPatternsAndEnvs(ExternalTransformerConfig cfg) {
        Map<String, String> envs = new HashMap<>();
        envs.put(ExternalApp.ENV_STDOUT_BEFORE, "field1:StdoutBefore");
        envs.put(ExternalApp.ENV_STDOUT_AFTER, "<field2>StdoutAfter</field2>");
        envs.put(ExternalApp.ENV_STDERR_BEFORE, "field3 StdErrBefore");
        envs.put(ExternalApp.ENV_STDERR_AFTER, "StdErrAfter:field4");
        cfg.setEnvironmentVariables(envs);

        cfg.setExtractionPatterns(
                List.of(
                        new RegexFieldValueExtractor("^(f.*):(.*)", 1, 2),
                        new RegexFieldValueExtractor(
                                "^<field2>(.*)</field2>", "field2",
                                1),
                        new RegexFieldValueExtractor(
                                "^f.*StdErr.*", "field3", 1),
                        new RegexFieldValueExtractor("^(S.*?):(.*)", 2, 1),
                        new RegexFieldValueExtractor(
                                "^(reference)\\=(.*)", 1, 2)));
    }

    private InputStream inputAsStream() {
        return new ByteArrayInputStream(INPUT.getBytes());
    }
}
