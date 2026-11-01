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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import com.norconex.crawler.core.junit.WithTestWatcherLogging.LoggingTestWatcher;

import lombok.extern.slf4j.Slf4j;

@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(LoggingTestWatcher.class)
@SuppressWarnings("null")
public @interface WithTestWatcherLogging {

    @Slf4j(topic = "TEST")
    public static class LoggingTestWatcher
            implements TestWatcher, BeforeTestExecutionCallback {

        private static final String LINE = "─".repeat(76) + "···";
        private static final String START_TOP = "┌" + LINE;
        private static final String START_BOTTOM = "▼";
        private static final String DONE_TOP = "▲";
        private static final String DONE_BOTTOM = "└" + LINE;

        @Override
        public void beforeTestExecution(ExtensionContext context) {
            var msg = print(false, "🧪", "STARTED", context);
            LOG.info(msg);
        }

        @Override
        public void testSuccessful(ExtensionContext context) {
            var msg = print(true, "✅", "PASSED", context);
            LOG.info(msg);
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            var msg = print(true, "❌", "FAILED", context);
            LOG.warn(msg, cause);
        }

        @Override
        public void testDisabled(
                ExtensionContext context, Optional<String> reason) {
            var msg = print(true, "🚫", "DISABLED", context)
                    + " - Reason: " + reason.orElse("None given.");
            LOG.info(msg);
        }

        @Override
        public void testAborted(ExtensionContext context, Throwable cause) {
            var msg = print(true, "⏹️", "ABORTED", context)
                    + " - Cause: " + cause.getLocalizedMessage();
            LOG.warn(msg);
        }

        private static String print(
                boolean done, String icon, String state,
                ExtensionContext context) {
            var testName = context.getDisplayName();
            var className = context.getTestClass()
                    .map(Class::getSimpleName).orElse("???");
            var top = done ? DONE_TOP : START_TOP;
            var bottom = done ? DONE_BOTTOM : START_BOTTOM;
            return "\n%s\n| %s %s | %s#%s\n%s".formatted(
                    top, icon, state, className, testName, bottom);
        }
    }
}
