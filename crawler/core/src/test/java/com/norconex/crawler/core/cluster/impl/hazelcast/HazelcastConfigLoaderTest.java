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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.cluster.ClusterException;

@Timeout(30)
class HazelcastConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_readsYamlFromFilesystemAndInterpolatesVariables()
            throws Exception {
        var file = tempDir.resolve("hazelcast.yaml");
        Files.writeString(file, """
                hazelcast:
                  cluster-name: ${clusterName|fallback}
                """);

        var config = HazelcastConfigLoader.load(
                file.toString(), Map.of("clusterName", "yaml-cluster"));

        assertThat(config.getClusterName()).isEqualTo("yaml-cluster");
    }

    @Test
    void load_readsXmlFromFilesystemAndInterpolatesVariables()
            throws Exception {
        var file = tempDir.resolve("hazelcast.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <hazelcast xmlns="http://www.hazelcast.com/schema/config">
                  <cluster-name>${clusterName|fallback}</cluster-name>
                </hazelcast>
                """);

        var config = HazelcastConfigLoader.load(
                file.toString(), Map.of("clusterName", "xml-cluster"));

        assertThat(config.getClusterName()).isEqualTo("xml-cluster");
    }

    @Test
    void load_readsClasspathResource() {
        var config = HazelcastConfigLoader.load(
                "classpath:cache/hazelcast-standalone.yaml", Map.of());

        assertThat(config.getClusterName()).isEqualTo("standalone-crawler");
    }

    @Test
    void load_rejectsUnknownFileType() {
        assertThatThrownBy(() -> HazelcastConfigLoader.load(
                "hazelcast.json", Map.of()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(
                                "Unknown Hazelcast config file type");
    }

    @Test
    void load_wrapsMissingClasspathResource() {
        assertThatThrownBy(() -> HazelcastConfigLoader.load(
                "classpath:missing-config.yaml", Map.of()))
                        .isInstanceOf(ClusterException.class)
                        .hasMessageContaining(
                                "Could not load Hazelcast configuration");
    }
}
