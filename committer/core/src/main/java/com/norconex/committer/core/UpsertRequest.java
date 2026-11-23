/* Copyright 2020-2025 Norconex Inc.
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
package com.norconex.committer.core;

import java.io.InputStream;

import com.norconex.commons.lang.map.Properties;

import lombok.Data;
import lombok.NonNull;
import lombok.ToString;

/**
 * A committer upsert request (update or insert).
 */
@Data
public class UpsertRequest implements CommitterRequest {

    private final String reference;
    @ToString.Exclude
    private final Properties metadata = new Properties();
    @ToString.Exclude
    private final InputStream content;
    /**
     * Why the committer withheld this document rather than send it (for
     * example its access control list could not be read), or {@code null}
     * when it did not. Set by the committer; read by whoever called it, to
     * try the document again later.
     */
    private String withheldReason;

    public UpsertRequest(
            @NonNull String reference,
            Properties metadata,
            InputStream content) {

        this.reference = reference;
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
        this.content = content != null
                ? content
                : InputStream.nullInputStream();
    }

    @Override
    public String getReference() {
        return reference;
    }

    @Override
    public Properties getMetadata() {
        return metadata;
    }

    public InputStream getContent() {
        return content;
    }
}
