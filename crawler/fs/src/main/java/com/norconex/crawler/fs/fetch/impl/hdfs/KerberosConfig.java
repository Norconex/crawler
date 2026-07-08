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

import java.nio.file.Path;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for Kerberos/SPNEGO authentication against a secured
 * WebHDFS endpoint. Only used when
 * {@link HdfsFetcherConfig#setAuthMethod(HdfsAuthMethod)} is set to
 * {@link HdfsAuthMethod#KERBEROS}.
 * </p>
 * <h3>Typical usage</h3>
 * <p>
 * At a minimum, you need a valid Kerberos configuration file
 * ({@code krb5.conf}) and either a keytab file or access to the system
 * ticket cache.
 * </p>
 * @since 4.0.0
 */
@Data
@Accessors(chain = true)
public class KerberosConfig {

    /**
     * Path to the Kerberos configuration file ({@code krb5.conf}). If
     * {@code null}, the JVM default is used (e.g. {@code /etc/krb5.conf}
     * on Linux or the value of {@code java.security.krb5.conf}).
     */
    private Path krb5ConfigPath;

    /**
     * Path to a keytab file containing the principal's key. When set,
     * authentication uses this keytab instead of requiring a password or
     * ticket cache.
     */
    private Path keytabPath;

    /**
     * The Kerberos principal name used for login (e.g.
     * {@code user@REALM}). Required when using a keytab file.
     * When not using a keytab, the credentials from
     * {@link HdfsFetcherConfig#getCredentials()} are used.
     */
    private String principal;

    /**
     * Whether to use the system ticket cache for authentication. When
     * {@code true}, an existing ticket obtained via {@code kinit} (or
     * OS-level SSO) is used and no password or keytab is required.
     * Default is {@code false}.
     */
    private boolean useTicketCache;

    /**
     * The JAAS login module name. When {@code null} (default), a
     * built-in JAAS configuration is created automatically based on the
     * other properties in this class. Set this only if you have a custom
     * JAAS login configuration file and entry name.
     */
    private String loginModuleName;
}
