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
package com.norconex.crawler.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.junit.WithLogLevel;

@Timeout(30)
@WithLogLevel(value = "OFF", classes = ExceptionSwallower.class) // tests expect exceptions
class ExceptionSwallowerTest {

    // -------------------------------------------------------------------------
    // swallow(FailableRunnable)
    // -------------------------------------------------------------------------

    @Test
    void swallow_runsRunnable() {
        var ran = new AtomicBoolean(false);
        ExceptionSwallower.swallow(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void swallow_nullRunnable_isNoOp() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.swallow(
                        (org.apache.commons.lang3.function.FailableRunnable<
                                Exception>) null));
    }

    @Test
    void swallow_exceptionIsSwallowed() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.swallow(() -> {
                    throw new RuntimeException("test exception");
                }));
    }

    @Test
    void swallow_withMessage_exceptionIsSwallowed() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.swallow(
                        () -> {
                            throw new RuntimeException("boom");
                        },
                        "custom message"));
    }

    // -------------------------------------------------------------------------
    // swallowQuietly(FailableRunnable)
    // -------------------------------------------------------------------------

    @Test
    void swallowQuietly_runsRunnable() {
        var ran = new AtomicBoolean(false);
        ExceptionSwallower.swallowQuietly(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void swallowQuietly_exceptionIsSwallowedSilently() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.swallowQuietly(() -> {
                    throw new IllegalStateException("silent exception");
                }));
    }

    // -------------------------------------------------------------------------
    // close(AutoCloseable...)
    // -------------------------------------------------------------------------

    @Test
    void close_closesResource() {
        var closed = new AtomicBoolean(false);
        AutoCloseable resource = () -> closed.set(true);
        ExceptionSwallower.close(resource);
        assertThat(closed).isTrue();
    }

    @Test
    void close_nullArray_isNoOp() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.close((AutoCloseable[]) null));
    }

    @Test
    void close_nullElement_isSkipped() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.close((AutoCloseable) null));
    }

    @Test
    void close_multipleResources_allClosed() {
        var count = new AtomicInteger(0);
        AutoCloseable r1 = count::incrementAndGet;
        AutoCloseable r2 = count::incrementAndGet;
        ExceptionSwallower.close(r1, r2);
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void close_withException_swallowsAndContinues() {
        var secondClosed = new AtomicBoolean(false);
        AutoCloseable throwing = () -> {
            throw new Exception("close error");
        };
        AutoCloseable second = () -> secondClosed.set(true);
        // close() does NOT swallow for non-critical by default – they log and continue
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.close(throwing, second));
        assertThat(secondClosed).isTrue();
    }

    // -------------------------------------------------------------------------
    // closeQuietly(AutoCloseable...)
    // -------------------------------------------------------------------------

    @Test
    void closeQuietly_closesResource() {
        var closed = new AtomicBoolean(false);
        ExceptionSwallower.closeQuietly(() -> closed.set(true));
        assertThat(closed).isTrue();
    }

    @Test
    void closeQuietly_exceptionIsSwallowed() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.closeQuietly(
                        () -> {
                            throw new Exception("quiet error");
                        }));
    }

    @Test
    void closeQuietly_nullArray_isNoOp() {
        assertThatNoException().isThrownBy(
                () -> ExceptionSwallower.closeQuietly((AutoCloseable[]) null));
    }

    // -------------------------------------------------------------------------
    // runWithInterruptClear(Runnable)
    // -------------------------------------------------------------------------

    @Test
    void runWithInterruptClear_runsRunnable() {
        var ran = new AtomicBoolean(false);
        ExceptionSwallower.runWithInterruptClear(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void runWithInterruptClear_restoresInterruptFlag() {
        Thread.currentThread().interrupt(); // set flag
        ExceptionSwallower.runWithInterruptClear(() -> {}); // clears then restores
        assertThat(Thread.interrupted()).isTrue(); // flag was restored
    }

    @Test
    void runWithInterruptClear_whenRunnableThrows_exceptionPropagates() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ExceptionSwallower.runWithInterruptClear(() -> {
                    throw new RuntimeException("propagated");
                })).isInstanceOf(RuntimeException.class)
                .hasMessage("propagated");
    }
}
