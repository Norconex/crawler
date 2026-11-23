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

import java.util.HashSet;

import com.norconex.commons.lang.map.Properties;

/**
 * <p>
 * Writes an {@link Acl} as plain multi-valued fields, for destinations that
 * cannot take JSON ({@link AclFormat#FLAT}). With the default prefix:
 * </p>
 * <pre>
 * acl.levels      = share, file
 * acl.share.allow = group:ad:CORP:Sales
 * acl.file.allow  = group:ad:CORP:Sales
 * acl.file.allow  = special:everyone
 * acl.file.deny   = group:ad:CORP:Interns
 * acl.owners      = user:ad:CORP:pascal
 * </pre>
 * <p>
 * Each value is a principal as {@code kind:dir:id} (or {@code special:id}),
 * see {@link AclEntry#principalText()}. {@code acl.levels} names the levels
 * in order. Whoever searches the destination may show a document to a person
 * who matches an allow value of <b>every</b> level and no deny value of any:
 * a level without allow values admits nobody. Two levels of the same name
 * get a number added to the second ({@code file}, {@code file2}).
 * </p>
 */
public final class AclFlattener {

    private AclFlattener() {
    }

    /**
     * @param acl    the ACL
     * @param prefix the prefix of the fields written, without the dot
     * @return the fields
     */
    public static Properties flatten(Acl acl, String prefix) {
        var fields = new Properties();
        var names = new HashSet<String>();
        for (AclLevel level : acl.levels()) {
            var name = level.name();
            for (var i = 2; !names.add(name); i++) {
                name = level.name() + i;
            }
            fields.add(prefix + ".levels", name);
            for (AclEntry entry : level.entries()) {
                var suffix = entry.access() == AclAccess.DENY
                        ? ".deny"
                        : ".allow";
                fields.add(prefix + "." + name + suffix,
                        entry.principalText());
            }
        }
        for (AclEntry owner : acl.owners()) {
            fields.add(prefix + ".owners", owner.principalText());
        }
        return fields;
    }
}
