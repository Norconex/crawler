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
package com.norconex.crawler.fs.fetch.impl.ftp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;

import com.norconex.crawler.fs.FsTestUtil;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MockFtpServer {

    @Getter
    private int port;

    private String startPath;
    private FtpServer server;

    private final File tempDir;
    private final boolean secure;
    /** Optional: override the directory served over FTP. Uses mock-fs if null. */
    private String servedDir;

    /**
     * Sets the root directory served to FTP clients. Defaults to
     * {@link FsTestUtil#TEST_FS_PATH} when not set.
     * @param dir absolute path to the directory to serve
     * @return this, for chaining
     */
    public MockFtpServer setServedDir(String dir) {
        this.servedDir = dir;
        return this;
    }

    public void start() throws IOException {

        var serverFactory = new FtpServerFactory();
        var factory = new ListenerFactory();
        port = FsTestUtil.freePort();
        factory.setPort(port);

        if (secure) {
            // SSL config
            var ssl = new SslConfigurationFactory();
            ssl.setKeystoreFile(new File(FsTestUtil.TEST_KEYSTORE_PATH));
            ssl.setKeystorePassword("password");
            // set the SSL configuration for the listener
            factory.setSslConfiguration(ssl.createSslConfiguration());
            factory.setImplicitSsl(false);
        }
        serverFactory.addListener("default", factory.createListener());

        // User manager
        var userManagerFactory = new PropertiesUserManagerFactory();
        userManagerFactory.setFile(new File(tempDir, "users.properties"));
        Files.createFile(userManagerFactory.getFile().toPath());
        var userManager = userManagerFactory.createUserManager();
        var user = new BaseUser();
        user.setName("testuser");
        user.setPassword("testpassword");
        user.setEnabled(true);
        user.setHomeDirectory(
                servedDir != null ? servedDir : FsTestUtil.TEST_FS_PATH);
        try {
            userManager.save(user);
            serverFactory.setUserManager(userManager);

            server = serverFactory.createServer();
            server.start();
        } catch (FtpException e) {
            throw new IOException(e);
        }

        startPath = "ftp%s://localhost:%s".formatted(secure ? "s" : "", port);
    }

    public void stop() {
        if (server != null && !server.isStopped()) {
            server.stop();
        }
    }

    /**
     * Null-safe stop.
     * @param server ftp server
     */
    public static void stop(MockFtpServer server) {
        if (server != null) {
            server.stop();
        }
    }

    public String getStartPath() {
        return startPath;
    }

    public static FtpFetcher fetcherClient() {
        var fetcher = new FtpFetcher();
        fetcher.getConfiguration()
                .setUserDirIsRoot(false)
                .getCredentials()
                .setUsername("testuser")
                .setPassword("testpassword");
        return fetcher;
    }
}
