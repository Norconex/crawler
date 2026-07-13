/* Copyright 2019-2026 Norconex Inc.
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

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.norconex.commons.lang.xml.Xml;

import lombok.Getter;

/**
 * Attributes of a CMIS object, backed by the Atom "entry" XML document
 * used to derive them. Since {@link CmisFetcher} receives this same
 * instance via the shared NIO.2 fetch flow, it reads {@link #getDocument()}
 * directly instead of issuing a separate request for CMIS-specific
 * metadata (core Atom fields, properties, ACL).
 */
public class CmisFileAttributes implements BasicFileAttributes {

    private static final String PROP_OBJECT_TYPE_ID = "cmis:objectTypeId";
    private static final String PROP_BASE_TYPE_ID = "cmis:baseTypeId";
    private static final String PROP_LAST_MODIFICATION_DATE =
            "cmis:lastModificationDate";
    private static final String PROP_CONTENT_STREAM_LENGTH =
            "cmis:contentStreamLength";

    @Getter
    private final Xml document;
    @Getter
    private final boolean recognized;
    private final boolean directory;
    private final long size;
    private final FileTime lastModifiedTime;

    public CmisFileAttributes(Xml document) {
        this.document = document;
        var type = resolveType();
        recognized = type != null;
        directory = !"cmis:document".equalsIgnoreCase(type);
        size = NumberUtils.toLong(
                getPropertyValue(PROP_CONTENT_STREAM_LENGTH), -1);
        var date = getPropertyValue(PROP_LAST_MODIFICATION_DATE);
        lastModifiedTime = StringUtils.isNotEmpty(date)
                ? FileTime.fromMillis(
                        ZonedDateTime.parse(date).toInstant().toEpochMilli())
                : FileTime.fromMillis(0);
    }

    // Returns the recognized CMIS type ("cmis:folder" / "cmis:document"),
    // or null if neither the object nor base type is recognized (mirrors
    // the prior VFS-based fetcher treating such objects as non-existent).
    private String resolveType() {
        for (var propId : new String[] {
                PROP_OBJECT_TYPE_ID, PROP_BASE_TYPE_ID }) {
            var type = getPropertyValue(propId);
            if ("cmis:folder".equalsIgnoreCase(type)
                    || "cmis:document".equalsIgnoreCase(type)) {
                return type;
            }
        }
        return null;
    }

    private String getPropertyValue(String propertyDefId) {
        return document.getString(
                """
                        /entry/object/properties/\
                        *[starts-with(local-name(), 'property')]\
                        [@propertyDefinitionId='%s']/value/text()"""
                        .formatted(propertyDefId));
    }

    @Override
    public FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime;
    }

    @Override
    public boolean isRegularFile() {
        return !directory;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return Math.max(size, 0);
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
