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
package com.norconex.crawler.fs.fetch.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The access checks some providers cannot answer. */
class AbstractNioFetcherAccessTest {

    @Test
    void theResultOfTheCheckIsReturned() {
        assertThat(AbstractNioFetcher.isAllowed(() -> true)).isTrue();
        assertThat(AbstractNioFetcher.isAllowed(() -> false)).isFalse();
    }

    @Test
    void aCheckTheProviderDoesNotSupportIsNotAllowedAndIsNotAnError() {
        var calls = new AtomicInteger();
        assertThat(AbstractNioFetcher.isAllowed(() -> {
            calls.incrementAndGet();
            throw new UnsupportedOperationException("EXECUTE");
        })).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void anotherFailureIsNotHidden() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> AbstractNioFetcher.isAllowed(() -> {
                    throw new IllegalStateException("broken");
                })).isInstanceOf(IllegalStateException.class);
    }
}
