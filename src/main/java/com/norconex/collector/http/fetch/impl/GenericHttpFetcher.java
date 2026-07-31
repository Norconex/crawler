/* Copyright 2018-2021 Norconex Inc.
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
package com.norconex.collector.http.fetch.impl;

import static com.norconex.collector.http.fetch.HttpMethod.GET;
import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmConstraints;
import java.security.AlgorithmParameters;
import java.security.CryptoPrimitive;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.EqualsExclude;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.HashCodeExclude;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.doc.CrawlDoc;
import com.norconex.collector.core.doc.CrawlState;
import com.norconex.collector.http.HttpCollector;
import com.norconex.collector.http.doc.HttpDocInfo;
import com.norconex.collector.http.doc.HttpDocMetadata;
import com.norconex.collector.http.fetch.AbstractHttpFetcher;
import com.norconex.collector.http.fetch.HttpFetchException;
import com.norconex.collector.http.fetch.HttpFetchResponseBuilder;
import com.norconex.collector.http.fetch.HttpMethod;
import com.norconex.collector.http.fetch.IHttpFetchResponse;
import com.norconex.collector.http.fetch.IHttpFetcher;
import com.norconex.collector.http.fetch.util.ApacheHttpUtil;
import com.norconex.collector.http.fetch.util.ApacheRedirectCaptureStrategy;
import com.norconex.collector.http.fetch.util.HstsResolver;
import com.norconex.collector.http.url.impl.GenericURLNormalizer;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.time.DurationParser;
import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.util.CharsetUtil;

/**
 * <p>
 * Default implementation of {@link IHttpFetcher}, based on Apache HttpClient.
 * </p>
 *
 * <p>
 * The "validStatusCodes" and "notFoundStatusCodes" configuration options
 * expect a comma-separated list of HTTP response codes.  If a code is added
 * to both, the valid list takes precedence.
 * </p>
 *
 * <h3>Accepted HTTP methods</h3>
 * <p>
 * By default this fetcher accepts HTTP GET and HEAD requests.  You can limit
 * it to only process one method with
 * {@link GenericHttpFetcherConfig#setHttpMethods(List)}.
 * </p>
 *
 * <h3>Content type and character encoding</h3>
 * <p>
 * The default way for the HTTP Collector to identify the content type
 * and character encoding of a document is to rely on the
 * "<a href="https://www.w3.org/Protocols/rfc1341/4_Content-Type.html">Content-Type</a>"
 * HTTP response header.  Web servers can sometimes return invalid
 * or missing content type and character encoding information.
 * You can optionally decide not to trust web servers HTTP responses and have
 * the collector perform its own content type and encoding detection.
 * Such detection can be enabled with
 * {@link GenericHttpFetcherConfig#setForceContentTypeDetection(boolean)}
 * and {@link GenericHttpFetcherConfig#setForceCharsetDetection(boolean)}.
 * </p>
 *
 * {@nx.include com.norconex.commons.lang.security.Credentials#doc}
 *
 * <p>
 * XML configuration entries expecting millisecond durations
 * can be provided in human-readable format (English only), as per
 * {@link DurationParser} (e.g., "5 minutes and 30 seconds" or "5m30s").
 * </p>
 *
 * <h3>HSTS Support</h3>
 * <p>
 * Upon first encountering a secure site, this fetcher will check whether the
 * site root domain has the "Strict-Transport-Security" (HSTS) policy support
 * part of its HTTP response headers. That information gets cached for future
 * requests. If the site supports HSTS, any non-secure URLs encountered
 * on the same domain will be automatically converted to "https" (including
 * sub-domains if HSTS indicates as such).
 * </p>
 * <p>
 * If you want to convert non-secure URLs secure ones regardless of website
 * HSTS support, use
 * {@link GenericURLNormalizer.Normalization#secureScheme} instead.
 * To disable HSTS support, use
 * {@link GenericHttpFetcherConfig#setDisableHSTS(boolean)}.
 * </p>
 *
 * <h3>Pro-active change detection</h3>
 * <p>
 * This fetcher takes advantage of the
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag">
 * ETag</a> and
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/If-Modified-Since">
 * If-Modified-Since</a> HTTP specifications.
 * </p>
 * <p>
 * On subsequent crawls, HTTP requests will include previously cached
 * <code>ETag</code> and <code>If-Modified-Since</code> values to tell
 * supporting servers we only want to download a document if it was modified
 * since our last request.
 * To disable support for pro-active change detection, you can use
 * {@link GenericHttpFetcherConfig#setDisableIfModifiedSince(boolean)} and
 * {@link GenericHttpFetcherConfig#setDisableETag(boolean)}.
 * </p>
 * <p>
 * These settings have no effect for web servers not supporting them.
 * </p>
 *
 * {@nx.xml.usage
 * <fetcher class="com.norconex.collector.http.fetch.impl.GenericHttpFetcher">
 *
 *   <userAgent>(identify yourself!)</userAgent>
 *   <cookieSpec>
 *     [STANDARD|DEFAULT|IGNORE_COOKIES|NETSCAPE|STANDARD_STRICT]
 *   </cookieSpec>
 *   <connectionTimeout>(milliseconds)</connectionTimeout>
 *   <socketTimeout>(milliseconds)</socketTimeout>
 *   <connectionRequestTimeout>(milliseconds)</connectionRequestTimeout>
 *   <connectionCharset>...</connectionCharset>
 *   <expectContinueEnabled>[false|true]</expectContinueEnabled>
 *   <maxRedirects>...</maxRedirects>
 *   <redirectURLProvider>(implementation handling redirects)</redirectURLProvider>
 *   <localAddress>...</localAddress>
 *   <maxConnections>...</maxConnections>
 *   <maxConnectionsPerRoute>...</maxConnectionsPerRoute>
 *   <maxConnectionIdleTime>(milliseconds)</maxConnectionIdleTime>
 *   <maxConnectionInactiveTime>(milliseconds)</maxConnectionInactiveTime>
 *
 *   <!-- Be warned: trusting all certificates is usually a bad idea. -->
 *   <trustAllSSLCertificates>[false|true]</trustAllSSLCertificates>
 *
 *   <!-- You can specify SSL/TLS protocols to use -->
 *   <sslProtocols>(coma-separated list)</sslProtocols>
 *
 *   <!-- Disable Server Name Indication (SNI) -->
 *   <disableSNI>[false|true]</disableSNI>
 *
 *   <!-- Disable support for website "Strict-Transport-Security" setting. -->
 *   <disableHSTS>[false|true]</disableHSTS>
 *
 *   <!-- You can use a specific key store for SSL Certificates -->
 *   <keyStoreFile></keyStoreFile>
 *
 *   <proxySettings>
 *     {@nx.include com.norconex.commons.lang.net.ProxySettings@nx.xml.usage}
 *   </proxySettings>
 *
 *   <!-- HTTP request header constants passed on every HTTP requests -->
 *   <headers>
 *     <header name="(header name)">(header value)</header>
 *     <!-- You can repeat this header tag as needed. -->
 *   </headers>
 *
 *   <!-- Disable conditionally getting a document based on last crawl date. -->
 *   <disableIfModifiedSince>[false|true]</disableIfModifiedSince>
 *
 *   <!-- Disable ETag support. -->
 *   <disableETag>[false|true]</disableETag>
 *
 *   <!-- Optional authentication details. -->
 *   <authentication>
 *     {@nx.include com.norconex.collector.http.fetch.impl.HttpAuthConfig@nx.xml.usage}
 *   </authentication>
 *
 *   <validStatusCodes>(defaults to 200)</validStatusCodes>
 *   <notFoundStatusCodes>(defaults to 404)</notFoundStatusCodes>
 *   <headersPrefix>(string to prefix headers)</headersPrefix>
 *
 *   <!-- Force detect, or only when not provided in HTTP response headers -->
 *   <forceContentTypeDetection>[false|true]</forceContentTypeDetection>
 *   <forceCharsetDetection>[false|true]</forceCharsetDetection>
 *
 *   {@nx.include com.norconex.collector.http.fetch.AbstractHttpFetcher#referenceFilters}
 *
 *   <!-- Comma-separated list of supported HTTP methods. -->
 *   <httpMethods>(defaults to: GET, HEAD)</httpMethods>
 *
 * </fetcher>
 * }
 *
 * {@nx.xml.example
 * <fetcher class="GenericHttpFetcher">
 *     <authentication>
 *       <method>form</method>
 *       <credentials>
 *         <username>joeUser</username>
 *         <password>joePasword</password>
 *       </credentials>
 *       <formUsernameField>loginUser</formUsernameField>
 *       <formPasswordField>loginPwd</formPasswordField>
 *       <url>http://www.example.com/login/submit</url>
 *     </authentication>
 * </fetcher>
 * }
 * <p>
 * The above example will authenticate the crawler to a web site before
 * crawling. The website uses an HTML form with a username and password
 * fields called "loginUser" and "loginPwd".
 * </p>
 *
 * @author Pascal Essiembre
 * @since 3.0.0 (Merged from GenericDocumentFetcher and
 *        GenericHttpClientFactory)
 */
@SuppressWarnings("javadoc")
public class GenericHttpFetcher extends AbstractHttpFetcher {

    private static final Logger LOG =
            LoggerFactory.getLogger(GenericHttpFetcher.class);

    /** Form-based authentication method. */
    public static final String AUTH_METHOD_FORM = "form";
    /** BASIC authentication method. */
    public static final String AUTH_METHOD_BASIC = "basic";
    /** DIGEST authentication method. */
    public static final String AUTH_METHOD_DIGEST = "digest";
    /** NTLM authentication method. */
    public static final String AUTH_METHOD_NTLM = "ntlm";
    /** Experimental: SPNEGO authentication method. */
    public static final String AUTH_METHOD_SPNEGO = "SPNEGO";
    /** Experimental: Kerberos authentication method. */
    public static final String AUTH_METHOD_KERBEROS = "Kerberos";

    private static final int FTP_PORT = 80;

    private static final SchemePortResolver SCHEME_PORT_RESOLVER = host -> {
        Args.notNull(host, "HTTP host");
        final var port = host.getPort();
        if (port > 0) {
            return port;
        }
        final var name = host.getSchemeName();
        if ("ftp".equalsIgnoreCase(name)) {
            return FTP_PORT;
        }
        return DefaultSchemePortResolver.INSTANCE.resolve(host);
    };

    private final GenericHttpFetcherConfig cfg;
    private HttpClient httpClient;
    @HashCodeExclude
    @EqualsExclude
    private final AuthCache authCache = new BasicAuthCache();
    private Object userToken;

    public GenericHttpFetcher() {
        this(new GenericHttpFetcherConfig());
    }
    public GenericHttpFetcher(GenericHttpFetcherConfig httpFetcherConfig) {
        Objects.requireNonNull(
                httpFetcherConfig, "'httpFetcherConfig' must not be null.");
        cfg = httpFetcherConfig;
    }

    public GenericHttpFetcherConfig getConfig() {
        return cfg;
    }
    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    protected boolean accept(HttpMethod httpMethod) {
        return cfg.getHttpMethods().contains(httpMethod);
    }

    @Override
    protected void fetcherStartup(HttpCollector c) {
    	httpClient = createHttpClient();
        var userAgent = cfg.getUserAgent();
        if (StringUtils.isBlank(userAgent)) {
            LOG.info("User-Agent: <None specified>");
            LOG.debug("It is recommended you identify yourself to web sites "
                    + "by specifying a user agent "
                    + "(https://en.wikipedia.org/wiki/User_agent)");
        } else {
            LOG.info("User-Agent: {}", userAgent);
        }

        if (cfg.getAuthConfig() != null && AUTH_METHOD_FORM.equalsIgnoreCase(
                cfg.getAuthConfig().getMethod())) {
            authenticateUsingForm(httpClient);
        }
    }
    @Override
	protected void fetcherShutdown(HttpCollector c) {
        if (httpClient instanceof CloseableHttpClient) {
            try {
                ((CloseableHttpClient) httpClient).close();
            } catch (IOException e) {
                LOG.error("Cannot close HttpClient.", e);
            }
        }
    }

    @Override
    public String getUserAgent() {
        return cfg.getUserAgent();
    }

    @Override
    public IHttpFetchResponse fetch(CrawlDoc doc, HttpMethod httpMethod)
            throws HttpFetchException {

        if (httpClient == null) {
            throw new IllegalStateException("GenericHttpFetcher was not "
                    + "initialized ('httpClient' not set).");
        }

        HttpRequestBase request = null;
        try {

            //--- HSTS Policy --------------------------------------------------
            if (!cfg.isDisableHSTS()) {
                HstsResolver.resolve(
                        httpClient, (HttpDocInfo) doc.getDocInfo());
            }

            //--- Prepare the request ------------------------------------------

            LOG.debug("Fetching: {}", doc.getReference());

            var method = ofNullable(httpMethod).orElse(GET);
            request = ApacheHttpUtil.createUriRequest(
                    doc.getReference(), method);

            var ctx = HttpClientContext.create();
            // auth cache
            ctx.setAuthCache(authCache);
            // user token
            if (userToken != null) {
                ctx.setUserToken(userToken);
            }

            if (!cfg.isDisableETag()) {
                ApacheHttpUtil.setRequestIfNoneMatch(request, doc);
            }
            if (!cfg.isDisableIfModifiedSince()) {
                ApacheHttpUtil.setRequestIfModifiedSince(request, doc);
            }

            // Execute the method.
            var response = httpClient.execute(request, ctx);

            //--- Process the response -----------------------------------------

            var statusCode = response.getStatusLine().getStatusCode();
            var reason = response.getStatusLine().getReasonPhrase();

            LOG.debug("Fetch status for: \"{}\": {} - {}",
                    doc.getReference(), statusCode, reason);

            var responseBuilder =
                    new HttpFetchResponseBuilder()
                .setStatusCode(statusCode)
                .setReasonPhrase(reason)
                .setUserAgent(cfg.getUserAgent())
                .setRedirectTarget(
                        ApacheRedirectCaptureStrategy.getRedirectTarget(ctx));

            //--- Extract headers ---
            ApacheHttpUtil.applyResponseHeaders(
                    response, cfg.getHeadersPrefix(), doc);

            //--- Extra document metadata ---
            doc.getMetadata().add(HttpDocMetadata.HTTP_STATUS_CODE, statusCode);
            doc.getMetadata().add(
                    HttpDocMetadata.HTTP_STATUS_REASON, reason);

            //--- Extract body ---
            if (HttpMethod.GET.is(method) || HttpMethod.POST.is(method)) {
                if (ApacheHttpUtil.applyResponseContent(response, doc)) {
                    performDetection(doc);
                } else {
                    LOG.debug("No content returned for: {}",
                            doc.getReference());
                }
            }

            //--- VALID http response handling ---------------------------------
            if (cfg.getValidStatusCodes().contains(statusCode)) {
                userToken = ctx.getUserToken();

                return responseBuilder
                        .setCrawlState(CrawlState.NEW)
                        .create();
            }

            //--- INVALID http response handling -------------------------------

            // UNMODIFIED
            if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                return responseBuilder
                        .setCrawlState(CrawlState.UNMODIFIED)
                        .create();
            }

            // NOT_FOUND
            if (cfg.getNotFoundStatusCodes().contains(statusCode)) {
                return responseBuilder
                        .setCrawlState(CrawlState.NOT_FOUND)
                        .create();
            }

            // BAD_STATUS
            LOG.debug("Unsupported HTTP Response: {}",
                    response.getStatusLine());
            return responseBuilder
                    .setCrawlState(CrawlState.BAD_STATUS)
                    .create();
        } catch (Exception e) {
            analyseException(e);
            //TODO set exception on response instead?
            throw new HttpFetchException(
                    "Could not fetch document: " + doc.getReference(), e);
        } finally {
            if (request != null) {
                request.releaseConnection();
            }
        }
    }

    //TODO remove this method and configuration options: always do it
    // by framework?  Then how to leverage getting it from client
    // directly (e.g. http response headers)?  Rely on metadata for that?
    private void performDetection(Doc doc) {
        var info = doc.getDocInfo();
        try {
            if (cfg.isForceContentTypeDetection()
                    || info.getContentType() == null) {
                info.setContentType(ContentTypeDetector.detect(
                        doc.getInputStream(), doc.getReference()));
            }
        } catch (IOException e) {
            LOG.warn("Cannont perform content type detection.", e);
        }
        try {
            if (cfg.isForceCharsetDetection()
                    || StringUtils.isBlank(info.getContentEncoding())) {
                info.setContentEncoding(CharsetUtil.detectCharset(
                        doc.getInputStream()));
            }
        } catch (IOException e) {
            LOG.warn("Cannot perform charset type detection.", e);
        }
    }

    //TODO Offer global PropertySetter option when adding headers and/or other fields

    protected HttpClient createHttpClient() {
        var builder = HttpClientBuilder.create();
        var sslContext = createSSLContext();
        builder.setSSLContext(sslContext);
        builder.setSSLSocketFactory(createSSLSocketFactory(sslContext));
        builder.setSchemePortResolver(createSchemePortResolver());
        builder.setDefaultRequestConfig(createRequestConfig());
        builder.setProxy(createProxy());
        builder.setDefaultCredentialsProvider(createCredentialsProvider());
        builder.setDefaultConnectionConfig(createConnectionConfig());
        builder.setUserAgent(cfg.getUserAgent());
        builder.setMaxConnTotal(cfg.getMaxConnections());
        builder.setMaxConnPerRoute(cfg.getMaxConnectionsPerRoute());
        builder.evictExpiredConnections();
        builder.evictIdleConnections(
                cfg.getMaxConnectionIdleTime(), TimeUnit.MILLISECONDS);
        builder.setDefaultHeaders(createDefaultRequestHeaders());
        builder.setDefaultCookieStore(createDefaultCookieStore());
        builder.setRedirectStrategy(new ApacheRedirectCaptureStrategy(
                cfg.getRedirectURLProvider()));

        buildCustomHttpClient(builder);

        HttpClient client = builder.build();
        hackValidateAfterInactivity(client);
        return client;
    }

    /**
     * This is a hack to work around
     * PoolingHttpClientConnectionManager#setValidateAfterInactivity(int)
     * not being exposed to the builder.
     */
    //TODO get rid of this method in favor of setXXX method when available
    // in a future version of HttpClient (planned for 5.0.0).
    private void hackValidateAfterInactivity(HttpClient httpClient) {
        if (cfg.getMaxConnectionInactiveTime() <= 0) {
            return;
        }
        try {
            var connManager =
                    FieldUtils.readField(httpClient, "connManager", true);
            if (connManager instanceof PoolingHttpClientConnectionManager) {
                ((PoolingHttpClientConnectionManager) connManager)
                        .setValidateAfterInactivity(
                                cfg.getMaxConnectionInactiveTime());
            } else {
                LOG.warn("\"maxConnectionInactiveTime\" could not be set since "
                        + "internal connection manager does not support it.");
            }
        } catch (Exception e) {
            LOG.warn("\"maxConnectionInactiveTime\" could not be set since "
                    + "internal connection manager does not support it.");
        }
    }


    /**
     * For implementors to subclass.  Does nothing by default.
     * @param builder http client builder
     */
    protected void buildCustomHttpClient(HttpClientBuilder builder) {
        //do nothing by default
    }

    protected void authenticateUsingForm(HttpClient httpClient) {
        try {
            ApacheHttpUtil.authenticateUsingForm(
                    httpClient, cfg.getAuthConfig());
        } catch (IOException | URISyntaxException e) {
            analyseException(e);
            throw new CollectorException(
                    "Could not perform FORM-based authentication.", e);
        }
    }

    /**
     * Creates the default cookie store to be added to each request context.
     * @return a cookie store
     */
    protected CookieStore createDefaultCookieStore() {
        return new BasicCookieStore();
    }

    /**
     * <p>
     * Creates a list of HTTP headers based on configuration.
     * </p>
     * <p>
     * This method will also add a "Basic" authentication
     * header if "preemptive" is <code>true</code> on the authentication
     * configuration and credentials were supplied.
     * </p>
     * @return a list of HTTP request headers
     */
    protected List<Header> createDefaultRequestHeaders() {
        //--- Configuration-defined headers
        List<Header> headers = new ArrayList<>();
        for (String name : cfg.getRequestHeaderNames()) {
            headers.add(new BasicHeader(name, cfg.getRequestHeader(name)));
        }

        //--- preemptive headers
        // preemptive authorisation could be done by creating a HttpContext
        // passed to the HttpClient execute method, but since that method
        // is not invoked from this class, we want to keep things
        // together and we add the preemptive authentication directly
        // in the default HTTP headers.
        if (cfg.getAuthConfig() != null && cfg.getAuthConfig().isPreemptive()) {
            var authConfig = cfg.getAuthConfig();
            if (StringUtils.isBlank(
                    authConfig.getCredentials().getUsername())) {
                LOG.warn("Preemptive authentication is enabled while no "
                        + "username was provided.");
                return headers;
            }
            if (!AUTH_METHOD_BASIC.equalsIgnoreCase(authConfig.getMethod())) {
                LOG.warn("Using preemptive authentication with a "
                        + "method other than \"Basic\" may not produce the "
                        + "expected outcome.");
            }
            var password = EncryptionUtil.decryptPassword(
                    authConfig.getCredentials());
            var auth = authConfig.getCredentials().getUsername()
                    + ":" + password;
            var encodedAuth = Base64.encodeBase64(
                    auth.getBytes(StandardCharsets.ISO_8859_1));
            var authHeader = "Basic " + new String(encodedAuth);
            headers.add(new BasicHeader(HttpHeaders.AUTHORIZATION, authHeader));
        }
        return headers;
    }

    protected SchemePortResolver createSchemePortResolver() {
        return SCHEME_PORT_RESOLVER;
    }
    protected RequestConfig createRequestConfig() {
        var builder = RequestConfig.custom()
                .setConnectTimeout(cfg.getConnectionTimeout())
                .setSocketTimeout(cfg.getSocketTimeout())
                .setConnectionRequestTimeout(cfg.getConnectionRequestTimeout())
                .setMaxRedirects(cfg.getMaxRedirects())
                .setExpectContinueEnabled(cfg.isExpectContinueEnabled())
                .setCookieSpec(cfg.getCookieSpec());
        if (cfg.getMaxRedirects() <= 0) {
            builder.setRedirectsEnabled(false);
        }
        if (StringUtils.isNotBlank(cfg.getLocalAddress())) {
            try {
                builder.setLocalAddress(
                        InetAddress.getByName(cfg.getLocalAddress()));
            } catch (UnknownHostException e) {
                throw new CollectorException(
                        "Invalid local address: " + cfg.getLocalAddress(), e);
            }
        }
        return builder.build();
    }
    protected HttpHost createProxy() {
        if (cfg.getProxySettings().isSet()) {
            return new HttpHost(
                    cfg.getProxySettings().getHost().getName(),
                    cfg.getProxySettings().getHost().getPort(),
                    cfg.getProxySettings().getScheme());
        }
        return null;
    }
    protected CredentialsProvider createCredentialsProvider() {
        CredentialsProvider credsProvider = null;

        //--- Proxy ---
        var proxy = cfg.getProxySettings();
        if (proxy.isSet() && proxy.getCredentials().isSet()) {
            var password = EncryptionUtil.decryptPassword(
                    proxy.getCredentials());
            credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(
                    proxy.getHost().getName(),
                    proxy.getHost().getPort(),
                    proxy.getRealm()),
                    new UsernamePasswordCredentials(
                            proxy.getCredentials().getUsername(),
                            password));
        }

        //--- Auth ---
        var authConfig = cfg.getAuthConfig();
        if (authConfig != null
                && authConfig.getCredentials().isSet()
                && !AUTH_METHOD_FORM.equalsIgnoreCase(authConfig.getMethod())) {
            if (credsProvider == null) {
                credsProvider = new BasicCredentialsProvider();
            }
            Credentials creds = null;
            var password = EncryptionUtil.decryptPassword(
                    authConfig.getCredentials());
            if (AUTH_METHOD_NTLM.equalsIgnoreCase(authConfig.getMethod())) {
                creds = new NTCredentials(
                        authConfig.getCredentials().getUsername(),
                        password,
                        authConfig.getWorkstation(),
                        authConfig.getDomain());
            } else {
                creds = new UsernamePasswordCredentials(
                        authConfig.getCredentials().getUsername(),
                        password);
            }
            // A host is optional. As documented on HttpAuthConfig#getHost(),
            // not specifying one means the credentials apply to "any host"
            // (AuthScope.ANY_HOST/ANY_PORT), consistent with how this was
            // handled prior to 3.0.0.
            var authHost = authConfig.getHost();
            credsProvider.setCredentials(new AuthScope(
                    authHost != null ? authHost.getName() : null,
                    authHost != null ? authHost.getPort() : AuthScope.ANY_PORT,
                    authConfig.getRealm(),
                    authConfig.getMethod()),
                    creds);
        }
        return credsProvider;
    }
    protected ConnectionConfig createConnectionConfig() {
        if (cfg.getProxySettings().getCredentials().isSet()) {
            return ConnectionConfig.custom()
                    .setCharset(Consts.UTF_8)
                    .build();
        }
        return null;
    }

    protected LayeredConnectionSocketFactory createSSLSocketFactory(
            SSLContext sslContext) {
        if (!cfg.isTrustAllSSLCertificates()
                && cfg.getSSLProtocols().isEmpty()) {
            return null;
        }

        var context = sslContext;
        if (context == null) {
            try {
                context = SSLContexts.custom()
                        .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                        .build();
            } catch (KeyManagementException | NoSuchAlgorithmException
                    | KeyStoreException e) {
                throw new CollectorException(
                        "Cannot create SSL context.", e);
            }
        }

        // Turn off host name verification and remove all algorithm constraints.
        return new SSLConnectionSocketFactory(
                        context, new NoopHostnameVerifier()) {
            @Override
            protected void prepareSocket(SSLSocket socket)
                    throws IOException {
                var sslParams = new SSLParameters();

                // Trust all certificates
                if (cfg.isTrustAllSSLCertificates()) {
                    LOG.debug("SSL: Turning off host name verification.");
                    sslParams.setAlgorithmConstraints(
                            new AlgorithmConstraints() {
                        @Override
                        public boolean permits(
                                Set<CryptoPrimitive> primitives, Key key) {
                            return true;
                        }
                        @Override
                        public boolean permits(Set<CryptoPrimitive> primitives,
                                String algorithm,
                                AlgorithmParameters parameters) {
                            return true;
                        }
                        @Override
                        public boolean permits(
                                Set<CryptoPrimitive> primitives,
                                String algorithm, Key key,
                                AlgorithmParameters parameters) {
                            return true;
                        }
                    });
                }

                // Specify protocols
                if (!cfg.getSSLProtocols().isEmpty()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("SSL: Protocols={}",
                                StringUtils.join(cfg.getSSLProtocols(), ","));
                    }
                    sslParams.setProtocols(cfg.getSSLProtocols().toArray(
                            ArrayUtils.EMPTY_STRING_ARRAY));
                }

                sslParams.setEndpointIdentificationAlgorithm("HTTPS");

                if (cfg.isDisableSNI()) {
                    // Disabling SNI extension introduced in Java 7 is necessary
                    // to avoid
                    // SSLProtocolException: handshake alert: unrecognized_name
                    // for some sites with wrong Virtual Host - config.
                    // Described here:
                    // http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7127374
                    // Instead of using the SystemProperty to disable SNI,
                    // follow the approach from here
                    // https://github.com/lightbody/browsermob-proxy/issues/117#issuecomment-141363454
                    // and disable SNI for this SSLConnectionSocketFactory only
                    LOG.debug("SSL: Disabling SNI Extension for this "
                            + "httpClientFactory.");
                    sslParams.setServerNames(Collections.emptyList());
                }

                socket.setSSLParameters(sslParams);
            }
        };
    }

    protected SSLContext createSSLContext() {
        if (!cfg.isTrustAllSSLCertificates()) {
            return null;
        }
        LOG.info("SSL: Trusting all certificates.");

        // Use a trust strategy that always returns true
        try {
            return SSLContexts.custom()
                    .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                    .build();
        } catch (Exception e) {
            throw new CollectorException(
                    "Cannot create SSL context trusting all certificates.", e);
        }
    }

    private void analyseException(Exception e) {
        if (e instanceof SSLHandshakeException
                && !cfg.isTrustAllSSLCertificates()) {
            LOG.warn("SSL handshake exception. Consider "
                    + "setting 'trustAllSSLCertificates' to true.");
        }
    }

    @Override
    public void loadHttpFetcherFromXML(XML xml) {
        xml.populate(cfg);
    }

    @Override
    public void saveHttpFetcherToXML(XML xml) {
        cfg.saveToXML(xml);
    }

    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other);
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(
                this, ToStringStyle.SHORT_PREFIX_STYLE).toString();
    }
}
