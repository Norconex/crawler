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
package com.norconex.crawler.fs.fetch.impl.smb;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.importer.doc.Doc;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * CIFS fetcher (Samba, Windows share) (<code>smb://</code>), backed by a
 * custom, read-only NIO.2 {@link java.nio.file.spi.FileSystemProvider}
 * ({@link SmbFileSystemProvider}) built directly on jcifs-ng's classic
 * {@code SmbFile} API.
 * </p>
 *
 * <h2>Access Control List (ACL)</h2>
 * <p>
 * This fetcher will try to extract access control information for each
 * SMB file. If you have no need for them, you can disable
 * acquiring them with {@link SmbFetcherConfig#setAclDisabled(boolean)}.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class SmbFetcher extends AbstractNioFetcher<SmbFetcherConfig> {

    private static final String ACL_PREFIX = FsDocMetadata.ACL + ".smb";
    private static final String ACE = ".ace";
    private static final String SID = ".sid";
    private static final String SID_TEXT = ".sidAsText";
    private static final String TYPE = ".type";
    private static final String TYPE_TEXT = ".typeAsText";
    private static final String DOMAIN_SID = ".domainSid";
    private static final String DOMAIN_NAME = ".domainName";
    private static final String ACCOUNT_NAME = ".accountName";

    @Getter
    private final SmbFetcherConfig configuration = new SmbFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final SmbFileSystemProvider provider = new SmbFileSystemProvider();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private CIFSContext context;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        var base = SingletonContext.getInstance();
        var creds = configuration.getCredentials();
        context = creds.isSet()
                ? base.withCredentials(new NtlmPasswordAuthenticator(
                        configuration.getDomain(), creds.getUsername(),
                        EncryptionUtil.decryptPassword(creds)))
                : base;
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(SmbFileSystem::close);
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "smb://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = provider.getOrCreateFileSystem(uri, context);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path, @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);

        if (!configuration.isAclDisabled()) {
            try {
                var acl = provider.getAcl((SmbPath) path);
                storeSID(acl, doc.getMetadata());
            } catch (IOException e) {
                LOG.error("Could not retrieve SMB ACL data.", e);
            }
        }
    }

    private void storeSID(jcifs.ACE[] acls, Properties metadata) {
        for (var i = 0; i < acls.length; i++) {
            var acl = acls[i];
            var sid = acl.getSID();
            metaSet(metadata, i, ACE, acl);
            metaSet(metadata, i, SID, sid);
            metaSet(metadata, i, SID_TEXT, sid.toDisplayString());
            metaSet(metadata, i, TYPE, sid.getType());
            metaSet(metadata, i, TYPE_TEXT, sid.getTypeText());
            metaSet(metadata, i, DOMAIN_SID, sid.getDomainSid());
            metaSet(metadata, i, DOMAIN_NAME, sid.getDomainName());
            metaSet(metadata, i, ACCOUNT_NAME, sid.getAccountName());
        }
    }

    private void metaSet(
            Properties metadata, int index, String suffix, Object value) {
        var v = StringUtils.trimToNull(Objects.toString(value, null));
        if (v != null) {
            metadata.set(key(index, suffix), v);
        }
    }

    private String key(int index, String suffix) {
        return ACL_PREFIX + "[" + index + "]" + suffix;
    }
}
