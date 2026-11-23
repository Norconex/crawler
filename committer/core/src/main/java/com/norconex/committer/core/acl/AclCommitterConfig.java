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
package com.norconex.committer.core.acl;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * How a committer sends the {@link Acl} documents carry. A document without
 * one is sent as it is, whatever this says.
 * </p>
 * <p>
 * A document is <b>withheld</b> (not sent, a version sent before removed from
 * the destination, and tried again on the crawler's next run) when its ACL
 * cannot be read, is of a version this committer does not know, could not be
 * read at all ({@link AclStatus#NONE}), is {@link AclStatus#PARTIAL} and
 * {@code partial} is {@link AclPartialPolicy#REJECT}, or, in the
 * {@link AclFormat#NATIVE} format, says something the destination cannot
 * express. A withheld document is never sent as readable by everyone.
 * </p>
 */
@Data
@Accessors(chain = true)
public class AclCommitterConfig {

    /** Default prefix of the fields written in the {@code FLAT} format. */
    public static final String DEFAULT_FLAT_PREFIX = "acl";

    /**
     * How documents' ACLs are sent. When not set, the committer's own default
     * applies: {@link AclFormat#NATIVE} for committers whose destination has
     * its own access control, otherwise {@link AclFormat#JSON}.
     */
    private AclFormat format;

    /**
     * What to do with a document whose ACL is partial. Default is
     * {@link AclPartialPolicy#REJECT}.
     */
    private AclPartialPolicy partial = AclPartialPolicy.REJECT;

    /**
     * The prefix of the fields written in the {@link AclFormat#FLAT} format.
     * Default is {@value #DEFAULT_FLAT_PREFIX}.
     */
    private String flatPrefix = DEFAULT_FLAT_PREFIX;
}
