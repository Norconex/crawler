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
package com.norconex.committer.idol;

/**
 * The text an IDOL access control list is written as in a field. Which one
 * applies is decided by the security type set up on the IDOL server.
 */
public enum IdolAclSyntax {
    /**
     * The NT style, with the four sections always present:
     * {@code <everyone>:U:<users>:G:<groups>:NU:<denied users>:NG:<denied
     * groups>}. The first part is 1 when everyone may read, else 0, and
     * each list is separated by commas.
     */
    NT,
    /**
     * The simple style of connectors that only know who may read:
     * {@code :U:<users>:G:<groups>}, each list separated by commas. It cannot
     * express a denial, so a document with one is not sent.
     */
    SIMPLE
}
