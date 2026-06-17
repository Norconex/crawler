/* Copyright 2015-2025 Norconex Inc.
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
package com.norconex.crawler.web.fetch.util;

import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcherConfig;

/**
 * Responsible for providing a target absolute URL each time an HTTP redirect
 * is encountered when invoking a URL.  Target URLs are treated as new URLs
 * to process, while the original URL gets rejected.
 * Sometimes redirect URLs returned with the HTTP headers are relatives,
 * have bad encoding, etc.
 * Implementors are free to handle/fix these conditions as they see fit.
 * @since 2.4.0
 */
@JsonDeserialize(
    as = GenericRedirectUrlProvider.class
)
public interface RedirectUrlProvider {

    /**
     * Provides the redirect URL that the crawler must follow. This method
     * is only invoked when a redirect has been detected. As such, it should
     * rarely return {@code null}. Returning {@code null} effectively
     * prevents a redirect from happening, but it is an efficient way to
     * disable redirects. The recommended approach to disable redirects is to
     * set zero on {@link HttpClientFetcherConfig#setMaxRedirects(int)}
     * @param request the HTTP request that led to the redirect
     * @param response original URL HTTP response
     * @param context execution state of an HTTP process
     * @return redirect URL
     */
    String provideRedirectURL(
            final HttpRequest request,
            final HttpResponse response,
            final HttpContext context);
}
