/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link HdfsFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class HdfsFetcherConfig extends BaseAuthNioFetcherConfig {

    /**
     * Authentication method. Defaults to {@link HdfsAuthMethod#SIMPLE}
     * (no real authentication, the configured username if any is sent as
     * the {@code user.name} query parameter). Use
     * {@link HdfsAuthMethod#KERBEROS} for clusters with
     * {@code hadoop.security.authentication=kerberos}, and set
     * {@link #setKerberosConfig(KerberosConfig)} accordingly.
     */
    private HdfsAuthMethod authMethod = HdfsAuthMethod.SIMPLE;

    /**
     * Kerberos/SPNEGO configuration. Required when {@link #getAuthMethod()}
     * is {@link HdfsAuthMethod#KERBEROS}.
     */
    private KerberosConfig kerberosConfig;
}
