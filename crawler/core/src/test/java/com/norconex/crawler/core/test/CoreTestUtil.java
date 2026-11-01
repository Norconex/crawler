/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core.test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;

import com.norconex.commons.lang.SystemUtil.UncheckedCallableException;
import com.norconex.commons.lang.TimeIdGenerator;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings("null")
public final class CoreTestUtil {

    public static final String CONTAINER_POSTGRESQL = "postgres:16";

    public static final String SYS_PROP_PREFER_IPV4 =
            "java.net.preferIPv4Stack";
    public static final String SYS_PROP_PREFER_IPV6 =
            "java.net.preferIPv6Addresses";

    public static Path writeToDir(Object obj, Path targetDir) {
        var file = targetDir.resolve(TimeIdGenerator.next() + ".yaml");
        writeToFile(obj, file);
        return file;
    }

    public static String[] nodeNames(int numNodes) {
        var nodeNames = new String[numNodes];
        for (var i = 0; i < nodeNames.length; i++) {
            nodeNames[i] = "node-" + (i + 1);
        }
        return nodeNames;
    }

    public static void writeToFile(Object obj, Path targetFile) {
        try {
            Files.createDirectories(targetFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(
                    targetFile.getParent(),
                    targetFile.getFileName().toString(),
                    ".tmp");
            try (Writer w = Files.newBufferedWriter(
                    tempFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                BeanMapper.DEFAULT.write(obj, w, Format.YAML);
            }
            Files.move(tempFile, targetFile,
                    StandardCopyOption.REPLACE_EXISTING);
            tempFile = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // Ignore cleanup errors
                }
            }
        }
    }

    public static <T> T readFromFile(Path file, Class<T> objectType) {
        try (Reader r = Files.newBufferedReader(file)) {
            return BeanMapper.DEFAULT.read(objectType, r, Format.YAML);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T withIpv4(Callable<?> callable) {
        var preferIpv4 = System.getProperty(SYS_PROP_PREFER_IPV4);
        var preferIpv6 = System.getProperty(SYS_PROP_PREFER_IPV6);
        try {
            System.setProperty(SYS_PROP_PREFER_IPV4, "true");
            System.setProperty(SYS_PROP_PREFER_IPV6, "false");
            return (T) callable.call();
        } catch (Exception e) {
            throw new UncheckedCallableException(e);
        } finally {
            if (preferIpv4 != null) {
                System.setProperty(SYS_PROP_PREFER_IPV4, preferIpv4);
            }
            if (preferIpv6 != null) {
                System.setProperty(SYS_PROP_PREFER_IPV6, preferIpv6);
            }
        }
    }

    public static List<String> listFiles(Path dir) {
        try {
            return Files.list(dir).map(Path::toString).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String extractErrorLines(String output) {
        var errors = output.lines()
                .filter(line -> line.contains(" ERROR ")
                        || line.contains("Exception")
                        || line.contains("Failed to")
                        || line.contains("FAILED")
                        || line.contains("Caused by")
                        || line.contains("at org.")
                        || line.contains("at com.")
                        || line.contains("at java."))
                .limit(100)
                .toList();
        if (!errors.isEmpty()) {
            return String.join("\n", errors);
        }
        return "";
    }
}
