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
package com.norconex.crawler.fs.fetch.impl.cmis;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.Xml;
import com.norconex.crawler.core.doc.CrawlerDocMetaConstants;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * <p>
 * CMIS-enabled Content Management Systems (CMS) fetcher
 * (Atom end-point), backed by a custom, read-only NIO.2
 * {@link java.nio.file.spi.FileSystemProvider} ({@link CmisFileSystemProvider}).
 * The start path can be specified as:
 * <code>cmis:http://yourhost:port/path/to/atom</code>.
 * Optionally you can have a non-root starting path by adding the path
 * name to the base URL, with an exclamation mark as a separator:
 * <code>cmis:http://yourhost:port/path/to/atom!/MyFolder/MySubFolder</code>.
 * Start paths are assumed to be Atom URLs.
 * </p>
 */
@ToString
@EqualsAndHashCode
public class CmisFetcher extends AbstractNioFetcher<CmisFetcherConfig> {

    private static final String CMIS_PREFIX =
            CrawlerDocMetaConstants.PREFIX + "cmis.";

    @Getter
    private final CmisFetcherConfig configuration = new CmisFetcherConfig();

    @EqualsAndHashCode.Exclude
    private final CmisFileSystemProvider provider =
            new CmisFileSystemProvider();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(CmisFileSystem::close);
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "cmis:");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var withoutScheme = reference.replaceFirst("^cmis(?:-atom)?:", "");
        var endpointUrl = StringUtils.substringBefore(withoutScheme, "!");
        var innerPath = StringUtils.defaultIfBlank(
                StringUtils.substringAfter(withoutScheme, "!"), "/");

        var endpointUri = URI.create("cmis:" + endpointUrl);
        var fs = provider.getOrCreateFileSystem(endpointUri, buildEnv());
        return fs.getPath(innerPath);
    }

    private Map<String, Object> buildEnv() {
        Map<String, Object> env = new HashMap<>();
        var cfg = configuration;
        if (cfg.getCredentials().isSet()) {
            env.put("username", cfg.getCredentials().getUsername());
            env.put(
                    "password",
                    EncryptionUtil.decryptPassword(cfg.getCredentials()));
        }
        env.put("repositoryId", cfg.getRepositoryId());
        return env;
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path,
            @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);

        if (attrs instanceof CmisFileAttributes cmisAttrs) {
            var ctx = new Context(cmisAttrs.getDocument(), doc.getMetadata());
            fetchCoreMeta(ctx);
            fetchProperties(ctx);
            if (!configuration.isAclDisabled()) {
                fetchAcl(ctx);
            }
        }
    }

    private void fetchCoreMeta(Context ctx) {
        ctx.addMetaXpath("author.name", "/entry/author/name/text()");
        ctx.addMetaXpath("id", "/entry/id/text()");
        ctx.addMetaXpath("published", "/entry/published/text()");
        ctx.addMetaXpath("title", "/entry/title/text()");
        ctx.addMetaXpath("edited", "/entry/edited/text()");
        ctx.addMetaXpath("updated", "/entry/updated/text()");
        ctx.addMetaXpath("content", "/entry/content/@src");

        var xTargetField = configuration.getXmlTargetField();
        if (StringUtils.isNotBlank(xTargetField)) {
            ctx.metadata.add(xTargetField, ctx.document.toString());
        }
    }

    private void fetchProperties(Context ctx) {
        var propXmlList = ctx.document.getXMLList(
                "/entry/object/properties//"
                        + "*[starts-with(local-name(), 'property')]");
        for (Xml propXml : propXmlList) {
            var propId = propXml.getString("@propertyDefinitionId");
            if (StringUtils.isBlank(propId)) {
                propId = "undefined_property";
            }
            ctx.addMeta(
                    "property." + propId,
                    propXml.getString("value/text()"));
        }
    }

    private void fetchAcl(Context ctx) {
        var permissions = new Properties();
        var permXmlList = ctx.document.getXMLList(
                "/entry/object/acl/permission");
        for (Xml permXml : permXmlList) {
            var principalId = permXml.getString("principal/principalId");
            permXml.getStringList("permission").forEach(p -> {
                if (StringUtils.isNotBlank(p)) {
                    permissions.add("acl." + p, principalId);
                }
            });
        }

        for (Entry<String, List<String>> en : permissions.entrySet()) {
            for (String val : en.getValue()) {
                ctx.addMeta(en.getKey(), val);
            }
        }
    }

    private static class Context {
        private final Xml document;
        private final Properties metadata;

        Context(Xml document, Properties metadata) {
            this.document = document;
            this.metadata = metadata;
        }

        private void addMeta(String key, Object value) {
            var val = Objects.toString(value, null);
            if (StringUtils.isBlank(val)) {
                return;
            }
            metadata.add(CMIS_PREFIX + key, val);
        }

        private void addMetaXpath(String key, String exp) {
            addMeta(key, document.getString(exp));
        }
    }
}
