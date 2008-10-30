// Copyright 2008 Google Inc.  All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.saml.common;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Useful utilities for SAML testing.
 */
public final class SamlTestUtil {

  private final static DateFormat httpDateFormat =
      new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
  static {
    httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  public static MockHttpServletRequest makeMockHttpGet(MockServletContext context, URL clientUrl,
      URL serverUrl) {
    MockHttpServletRequest request = makeMockHttpRequest(context, "GET", clientUrl, serverUrl);
    request.setContent(new byte[0]);
    return request;
  }

  public static MockHttpServletRequest makeMockHttpPost(MockServletContext context, URL clientUrl,
      URL serverUrl) {
    return makeMockHttpRequest(context, "POST", clientUrl, serverUrl);
  }

  private static MockHttpServletRequest makeMockHttpRequest(MockServletContext context,
      String method, URL clientUrl, URL serverUrl) {
    MockHttpServletRequest request =
        new MockHttpServletRequest(context, method, serverUrl.getPath());
    request.setScheme(serverUrl.getProtocol());
    request.setServerName(serverUrl.getHost());
    request.setServerPort(serverUrl.getPort());
    request.setRemoteHost(clientUrl.getHost());
    request.setRemotePort(clientUrl.getPort());
    request.addHeader("Host", urlHostPort(serverUrl));
    request.addHeader("Date", httpDateString());
    request.addHeader("Accept", "text/html, text/xhtml;q=0.9, text/plain;q=0.5, text/*;q=0.1");
    request.addHeader("Accept-Charset", "us-ascii, iso-8859-1, utf-8");
    request.addHeader("Accept-Encoding", "identity");
    request.addHeader("Accept-Language", "en-us, en;q=0.9");
    return request;
  }

  public static String httpDateString() {
    return httpDateString(new Date());
  }

  public static String httpDateString(Date date) {
    return httpDateFormat.format(date);
  }

  public static String urlHostPort(URL url) {
    String host = url.getHost();
    int port = url.getPort();
    return (port < 0) ? host : String.format("%s:%d", host, port);
  }

  public static String servletRequestToString(HttpServletRequest request) throws IOException {
    StringWriter out = new StringWriter();
    writeServletRequest(request, out);
    String result = out.toString();
    out.close();
    return result;
  }

  public static void writeServletRequest(HttpServletRequest request, Writer out) throws IOException {
    writeRequestLine(request, out);
    writeRequestHeaders(request, out);
    copyText(request.getReader(), out);
  }

  private static void writeRequestLine(HttpServletRequest request, Writer out) throws IOException {
    out.write(request.getMethod());
    out.write(" ");
    out.write(request.getRequestURI());
    {
      String qs = request.getQueryString();
      if (qs != null) {
        out.write(qs);
      }
    }
    out.write(" ");
    out.write("HTTP/1.1");
    out.write("\n");
  }

  @SuppressWarnings("unchecked")
  private static void writeRequestHeaders(HttpServletRequest request, Writer out)
      throws IOException {
    for (Enumeration<String> names = request.getHeaderNames(); names.hasMoreElements();) {
      String name = names.nextElement();
      for (Enumeration<String> values = request.getHeaders(name); values.hasMoreElements();) {
        out.write(name);
        out.write(": ");
        out.write(values.nextElement());
        out.write("\n");
      }
    }
    out.write("\n");
  }

  public static void initializeServletResponse(HttpServletResponse response) {
    response.addHeader("Date", httpDateString());
  }

  public static String servletResponseToString(MockHttpServletResponse response) throws IOException {
    StringWriter out = new StringWriter();
    writeServletResponse(response, out);
    String result = out.toString();
    out.close();
    return result;
  }

  public static void writeServletResponse(MockHttpServletResponse response, Writer out)
      throws IOException {
    {
      String url = response.getRedirectedUrl();
      if (url != null) {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", url);
      }
    }
    writeResponseLine(response, out);
    writeResponseHeaders(response, out);
    copyText(new InputStreamReader(new ByteArrayInputStream(response.getContentAsByteArray())), out);
  }

  private static void writeResponseLine(MockHttpServletResponse response, Writer out)
      throws IOException {
    out.write("HTTP/1.1");
    out.write(" ");
    out.write(String.format("%03d", response.getStatus()));
    out.write(" ");
    out.write("insert reason here");
    out.write("\n");
  }

  @SuppressWarnings("unchecked")
  private static void writeResponseHeaders(MockHttpServletResponse response, Writer out)
      throws IOException {
    for (String name : (Set<String>) response.getHeaderNames()) {
      for (String value : (List<String>) response.getHeaders(name)) {
        out.write(name);
        out.write(": ");
        out.write(value);
        out.write("\n");
      }
    }
    out.write("\n");
  }

  private static void copyText(Reader in, Writer out) throws IOException {
    char[] buffer = new char[0x1000];
    while (true) {
      int nRead = in.read(buffer);
      if (nRead < 1) {
        break;
      }
      out.write(buffer, 0, nRead);
    }
    in.close();
  }
}