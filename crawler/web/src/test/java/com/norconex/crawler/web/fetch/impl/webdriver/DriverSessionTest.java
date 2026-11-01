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
package com.norconex.crawler.web.fetch.impl.webdriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.WebDriver;

@Timeout(30)
class DriverSessionTest {

    @Test
    void testCloseCallsCloseAction() throws Exception {
        var driver = mock(WebDriver.class);
        var closed = new boolean[] { false };
        var session = new DriverSession(driver, () -> closed[0] = true);

        session.close();

        assertThat(closed[0]).isTrue();
    }

    @Test
    void testCloseIsIdempotent() throws Exception {
        var driver = mock(WebDriver.class);
        var callCount = new int[] { 0 };
        var session = new DriverSession(driver, () -> callCount[0]++);

        session.close();
        session.close();

        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void testDriverReturnsWrappedDriver() throws Exception {
        var driver = mock(WebDriver.class);
        try (var session = new DriverSession(driver, () -> {})) {
            assertThat(session.driver()).isSameAs(driver);
        }
    }

    @Test
    void testOfCreatesSessionWithQuitAction() throws Exception {
        var driver = mock(WebDriver.class);
        var session = DriverSession.of(driver);

        session.close();

        verify(driver).quit();
    }

    @Test
    void testUntrackDoesNotPreventClose() throws Exception {
        var driver = mock(WebDriver.class);
        var closed = new boolean[] { false };
        var session = new DriverSession(driver, () -> closed[0] = true);

        session.untrack();
        session.close();

        assertThat(closed[0]).isTrue();
    }

    @Test
    void testCloseAllOpenSessionsClosesTrackedSessions() throws Exception {
        var driver1 = mock(WebDriver.class);
        var driver2 = mock(WebDriver.class);
        // Create sessions so they register in OPEN_SESSIONS
        DriverSession.of(driver1);
        DriverSession.of(driver2);

        DriverSession.closeAllOpenSessions();

        verify(driver1).quit();
        verify(driver2).quit();
    }

    @Test
    void testCloseAllOpenSessionsHandlesCloseFailureWithSuppressed() {
        var driver = mock(WebDriver.class);
        doThrow(new RuntimeException("quit failed")).when(driver).quit();
        // register a session
        DriverSession.of(driver);

        // Should throw IllegalStateException with suppressed exceptions
        assertThatCode(DriverSession::closeAllOpenSessions)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not close all WebDriver sessions");
    }

    @Test
    void testUntrackedSessionNotClosedByCloseAll() throws Exception {
        var driver = mock(WebDriver.class);
        var session = DriverSession.of(driver);
        session.untrack();

        // closeAll shouldn't close an untracked session
        DriverSession.closeAllOpenSessions();

        verify(driver, never()).quit();
        // Clean up manually
        session.close();
        verify(driver, times(1)).quit();
    }
}
