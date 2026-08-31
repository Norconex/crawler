/* Copyright 2013-2025 Norconex Inc.
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
package com.norconex.crawler.fs.doc.operations.checksum;

import static org.apache.commons.lang3.StringUtils.trimToEmpty;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.doc.CrawlerDocMetaConstants;
import com.norconex.crawler.core.doc.operations.checksum.AbstractMetadataChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.BaseChecksummerConfig;
import com.norconex.crawler.core.doc.operations.checksum.MetadataChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.impl.GenericMetadataChecksummer;
import com.norconex.crawler.fs.doc.FsDocMetadata;

import lombok.Data;

/**
 * <p>Default implementation of {@link MetadataChecksummer} which by default
 * returns the combined values of {@link FsDocMetadata#LAST_MODIFIED}
 * and {@link FsDocMetadata#FILE_SIZE}, separated with an underscore
 * (e.g. "14125443181234_123").
 * </p>
 * <p>
 * You have the option to keep the checksum as a document metadata field.
 * When {@link BaseChecksummerConfig#setKeep(boolean)} is
 * <code>true</code>, the checksum will be
 * stored in the target field name specified. If you do not specify any,
 * it stores it under the metadata field name
 * {@link CrawlerDocMetaConstants#CHECKSUM_METADATA}.
 * </p>
 * <p>
 * To use different fields (one or several) to constitute a checksum,
 * you can use the {@link GenericMetadataChecksummer}.
 * </p>
 * @see GenericMetadataChecksummer
 */
@Data
public class FsMetadataChecksummer
        extends AbstractMetadataChecksummer<BaseChecksummerConfig> {

    private final BaseChecksummerConfig configuration =
            new BaseChecksummerConfig();

    @Override
    protected String doCreateMetaChecksum(Properties metadata) {
        var lastMod = trimToEmpty(
                metadata.getString(FsDocMetadata.LAST_MODIFIED));
        var size = trimToEmpty(metadata.getString(FsDocMetadata.FILE_SIZE));
        if (StringUtils.isAllBlank(lastMod, size)) {
            return null;
        }
        return lastMod + "_" + size;
    }
}
