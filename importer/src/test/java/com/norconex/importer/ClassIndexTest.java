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
package com.norconex.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.ClassFinder;

/**
 * The importer's class index must list exactly the concrete classes a scan of
 * its compiled output finds: nothing left out, nothing extra.
 */
class ClassIndexTest {

    @Test
    void indexListsWhatAScanOfTheCompiledOutputFinds() throws Exception {
        var classes = Path.of(Importer.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        assertThat(scanned(classes))
                .as("concrete classes a scan finds")
                .isEqualTo(indexed(classes));
    }

    /** What a scan of the compiled classes finds: each concrete class, by name. */
    private static Set<String> scanned(Path classes) throws Exception {
        var found = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                var relative = classes.relativize(file).toString();
                if (!relative.endsWith(".class")) {
                    continue;
                }
                var name = relative
                        .substring(0, relative.length() - ".class".length())
                        .replace('/', '.').replace('\\', '.');
                var type = Class.forName(name, false,
                        Importer.class.getClassLoader());
                if (!type.isInterface()
                        && !Modifier.isAbstract(type.getModifiers())) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    /** What the index lists, without its comment lines. */
    private static Set<String> indexed(Path classes) throws IOException {
        var found = new TreeSet<String>();
        for (String line : Files
                .readAllLines(classes.resolve(ClassFinder.INDEX_RESOURCE))) {
            if (!line.startsWith("#") && !line.isBlank()) {
                found.add(line.trim());
            }
        }
        return found;
    }
}
