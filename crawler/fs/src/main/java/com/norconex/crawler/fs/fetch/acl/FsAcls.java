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
package com.norconex.crawler.fs.fetch.acl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.map.Properties;

import lombok.NonNull;

/**
 * Helpers the file-system fetchers share to write a document's
 * {@link Acl}.
 */
public final class FsAcls {

    private static String localHost;

    private FsAcls() {
    }

    /**
     * Writes a document's ACL.
     * @param meta       the document's metadata
     * @param mode       how permissions were asked for
     * @param levels     the levels, in order
     * @param owners     the owners
     * @param nativeData the source's own entries, or {@code null}
     * @param unread     what could not be read, making the ACL partial when
     *                   not empty
     * @return the ACL written
     */
    public static Acl write(
            @NonNull Properties meta, @NonNull AclFetchMode mode,
            List<AclLevel> levels, List<AclEntry> owners,
            Map<String, Object> nativeData, List<String> unread) {
        var acl = new Acl(Acl.VERSION, AclStatus.COMPLETE, mode.scope(), null,
                levels, owners, nativeData).withUnread(unread);
        AclCodec.write(meta, acl);
        return acl;
    }

    /**
     * Writes the ACL of a document whose permissions could not be read at
     * all. A committer will not send it.
     * @param meta the document's metadata
     * @param mode how permissions were asked for
     * @param why  why, in a few words
     * @return the ACL written
     */
    public static Acl writeNone(
            @NonNull Properties meta, @NonNull AclFetchMode mode, String why) {
        var acl = Acl.none(mode.scope(), why);
        AclCodec.write(meta, acl);
        return acl;
    }

    /**
     * The directory of the users and groups of a Unix host.
     * @param host the host name
     * @return {@code unix:<host>}
     */
    public static String unixDir(String host) {
        return "unix:" + StringUtils.defaultIfBlank(host, "localhost");
    }

    /**
     * The name of this host, for the directory of local users and groups.
     * @return the host name
     */
    public static synchronized String localHostName() {
        if (localHost == null) {
            String host = null;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException | SecurityException e) {
                // fall back on the environment
            }
            if (StringUtils.isBlank(host)) {
                host = StringUtils.firstNonBlank(
                        System.getenv("HOSTNAME"),
                        System.getenv("COMPUTERNAME"),
                        "localhost");
            }
            localHost = host;
        }
        return localHost;
    }

    /**
     * Logs warnings once per crawl rather than once per document.
     */
    public static final class OnceWarnings {
        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        /**
         * Logs a warning the first time a key is seen.
         * @param log the logger
         * @param key what the warning is about
         * @param msg the warning, logged with the arguments
         * @param args the arguments
         */
        public void warn(Logger log, String key, String msg, Object... args) {
            if (seen.add(key)) {
                log.warn(msg, args);
            }
        }

        /** Forgets the warnings logged so far. */
        public void clear() {
            seen.clear();
        }
    }
}
