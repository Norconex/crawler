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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.norconex.crawler.fs.fetch.impl.hdfs;

/**
 * WebHDFS authentication methods.
 */
public enum HdfsAuthMethod {
    /**
     * No real authentication: the configured username, if any, is sent
     * as the {@code user.name} query parameter. This is how an
     * unsecured (non-Kerberized) HDFS cluster identifies the caller.
     */
    SIMPLE,
    /**
     * Kerberos/SPNEGO authentication, for clusters with
     * {@code hadoop.security.authentication=kerberos}. Requires a
     * {@link KerberosConfig}.
     */
    KERBEROS
}
