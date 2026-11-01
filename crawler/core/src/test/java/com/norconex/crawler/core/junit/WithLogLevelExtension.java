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
package com.norconex.crawler.core.junit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.norconex.crawler.core.junit.WithLogLevel.WithLogLevels;

@SuppressWarnings("null")
public class WithLogLevelExtension
        implements BeforeEachCallback, AfterEachCallback {
    private final Map<String, Level> originalLevels = new HashMap<>();
    private static final boolean LOG4J_AVAILABLE = isLog4jPresent();

    @Override
    public void beforeEach(ExtensionContext context) {
        if (!LOG4J_AVAILABLE) {
            return;
        }
        var method = context.getRequiredTestMethod();
        var testClass = context.getRequiredTestClass();
        resolveAnnotations(
                () -> testClass.getAnnotation(WithLogLevel.WithLogLevels.class),
                () -> testClass.getAnnotation(WithLogLevel.class));
        resolveAnnotations(
                () -> method.getAnnotation(WithLogLevel.WithLogLevels.class),
                () -> method.getAnnotation(WithLogLevel.class));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!LOG4J_AVAILABLE) {
            return;
        }
        originalLevels.forEach(Configurator::setLevel);
    }

    private void resolveAnnotations(
            Supplier<WithLogLevels> multiAnnotSupplier,
            Supplier<WithLogLevel> singleAnnotSupplier) {
        var container = multiAnnotSupplier.get();
        if (container != null) {
            for (WithLogLevel annotation : container.value()) {
                applyLogLevel(annotation);
            }
        } else {
            var annotation = singleAnnotSupplier.get();
            if (annotation != null) {
                applyLogLevel(annotation);
            }
        }
    }

    private void applyLogLevel(WithLogLevel annotation) {
        var newLevel = annotation.value();
        var loggerContext = LoggerContext.getContext(false);
        for (Class<?> clazz : annotation.classes()) {
            var loggerName = clazz.getName();
            var loggerConfig = loggerContext.getConfiguration()
                    .getLoggerConfig(loggerName);
            originalLevels.put(loggerName, loggerConfig.getLevel());
            Configurator.setLevel(loggerName, newLevel);
        }
    }

    private static boolean isLog4jPresent() {
        try {
            Class.forName("org.apache.logging.log4j.core.LoggerContext");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
