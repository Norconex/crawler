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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;

import javax.security.auth.Subject;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpHost;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for HDFS (<code>webhdfs://</code>), backed by a custom, read-only
 * NIO.2 {@link java.nio.file.spi.FileSystemProvider}
 * ({@link HdfsFileSystemProvider}) that speaks the WebHDFS REST API
 * directly over Apache HttpClient - no Hadoop client dependency.
 * </p>
 * <p>
 * The reference host and port must be the NameNode's WebHDFS/HTTP port
 * (typically {@code 9870} on Hadoop 3.x), not the RPC port ({@code 8020})
 * used by the native Hadoop client.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class HdfsFetcher extends AbstractNioFetcher<HdfsFetcherConfig> {

    @Getter
    private final HdfsFetcherConfig configuration = new HdfsFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final HdfsFileSystemProvider provider =
            new HdfsFileSystemProvider();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Subject kerberosSubject;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        if (configuration.getAuthMethod() == HdfsAuthMethod.KERBEROS) {
            kerberosSubject = performKerberosLogin();
        }
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close HDFS file system.", e);
            }
        });
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "webhdfs://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = provider.getOrCreateFileSystem(
                uri, configuration.getCredentials().getUsername(),
                kerberosSubject, this::buildHttpClient);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    // Builds an HttpClient configured from this fetcher's settings, scoped
    // to the given target host. Called once per authority by the provider.
    // Package-private so it can be exercised in tests without a live
    // server.
    CloseableHttpClient buildHttpClient(HttpHost targetHost) {
        var builder = HttpClientBuilder.create();
        if (configuration.getAuthMethod() == HdfsAuthMethod.KERBEROS) {
            // A credential entry is required for HttpClient's auth state
            // machine to attempt the Negotiate (SPNEGO) scheme at all; the
            // actual Kerberos material comes from the ambient Subject
            // (see HdfsFileSystemProvider.doAs), not from this credential.
            var credsProvider = new BasicCredentialsProvider();
            var creds = configuration.getCredentials();
            credsProvider.setCredentials(
                    new AuthScope(
                            targetHost, null, HdfsAuthMethod.KERBEROS.name()),
                    new UsernamePasswordCredentials(
                            StringUtils.defaultString(creds.getUsername()),
                            toCharArray(
                                    EncryptionUtil.decryptPassword(creds))));
            builder.setDefaultCredentialsProvider(credsProvider);
        }
        return builder.build();
    }

    private static char[] toCharArray(String s) {
        return s == null ? new char[0] : s.toCharArray();
    }

    /**
     * Performs a JAAS login to obtain a Kerberos ticket and returns the
     * resulting {@link Subject}, used to wrap each WebHDFS HTTP call via
     * {@code Subject.callAs()}.
     */
    private Subject performKerberosLogin() {
        var krbConfig = configuration.getKerberosConfig();
        if (krbConfig == null) {
            throw new CrawlerException(
                    """
                        Kerberos configuration is required when using \
                        KERBEROS authentication.""");
        }

        setupKerberosSystemProperties(krbConfig);

        try {
            var loginContext = createKerberosLoginContext(krbConfig);
            loginContext.login();
            var subject = loginContext.getSubject();
            LOG.debug(
                    "Kerberos: Successfully logged in as principal: {}",
                    subject.getPrincipals());
            return subject;
        } catch (LoginException e) {
            throw new CrawlerException("Failed to perform Kerberos login.", e);
        }
    }

    private void setupKerberosSystemProperties(KerberosConfig krbConfig) {
        if (krbConfig.getKrb5ConfigPath() != null) {
            System.setProperty(
                    "java.security.krb5.conf",
                    krbConfig.getKrb5ConfigPath()
                            .toAbsolutePath().toString());
            LOG.debug(
                    "Kerberos: Using krb5.conf: {}",
                    krbConfig.getKrb5ConfigPath());
        }
        // Required for SPNEGO
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
    }

    private LoginContext createKerberosLoginContext(KerberosConfig krbConfig)
            throws LoginException {
        // If a custom login module name is provided, use it
        if (krbConfig.getLoginModuleName() != null) {
            LOG.debug(
                    "Kerberos: Using custom JAAS login module: {}",
                    krbConfig.getLoginModuleName());
            return new LoginContext(krbConfig.getLoginModuleName());
        }

        // Otherwise, create a programmatic JAAS configuration
        var options = new HashMap<String, String>();
        options.put("isInitiator", "true");

        if (krbConfig.getKeytabPath() != null) {
            options.put("useKeyTab", "true");
            options.put(
                    "keyTab",
                    krbConfig.getKeytabPath().toAbsolutePath().toString());
            options.put("storeKey", "true");
            LOG.debug("Kerberos: Using keytab: {}", krbConfig.getKeytabPath());
        }
        if (krbConfig.isUseTicketCache()) {
            options.put("useTicketCache", "true");
            LOG.debug("Kerberos: Using ticket cache.");
        }
        if (krbConfig.getPrincipal() != null) {
            options.put("principal", krbConfig.getPrincipal());
        }

        var jaasConfig = new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(
                    String name) {
                return new AppConfigurationEntry[] {
                        new AppConfigurationEntry(
                                "com.sun.security.auth.module.Krb5LoginModule",
                                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                options)
                };
            }
        };

        // If using username/password (no keytab, no ticket cache), provide
        // a callback handler
        if (krbConfig.getKeytabPath() == null
                && !krbConfig.isUseTicketCache()
                && configuration.getCredentials().isSet()) {
            var password = EncryptionUtil.decryptPassword(
                    configuration.getCredentials());
            return new LoginContext(
                    "NxHdfsKerberosLogin",
                    null,
                    callbacks -> {
                        for (var cb : callbacks) {
                            if (cb instanceof NameCallback nc) {
                                nc.setName(
                                        configuration.getCredentials()
                                                .getUsername());
                            } else if (cb instanceof PasswordCallback pc) {
                                pc.setPassword(toCharArray(password));
                            }
                        }
                    },
                    jaasConfig);
        }

        return new LoginContext(
                "NxHdfsKerberosLogin", null, null, jaasConfig);
    }
}
