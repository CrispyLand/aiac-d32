package com.crispyland.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportControllerTest {

    @Test
    void resolveClientIpUsesXRealIpWhenFromLoopback() {
        var request = stubRequest("127.0.0.1", "203.0.113.42");
        assertThat(SupportController.resolveClientIp(request)).isEqualTo("203.0.113.42");
    }

    @Test
    void resolveClientIpUsesRemoteAddrWhenNotLoopback() {
        var request = stubRequest("192.168.1.5", "203.0.113.42");
        assertThat(SupportController.resolveClientIp(request)).isEqualTo("192.168.1.5");
    }

    @Test
    void resolveClientIpFallsBackWhenXRealIpMissing() {
        var request = stubRequest("127.0.0.1", null);
        assertThat(SupportController.resolveClientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolveClientIpHandlesIpv6Loopback() {
        var request = stubRequest("0:0:0:0:0:0:0:1", "10.0.0.5");
        assertThat(SupportController.resolveClientIp(request)).isEqualTo("10.0.0.5");
    }

    private static HttpServletRequest stubRequest(String remoteAddr, String xRealIp) {
        return new HttpServletRequestWrapper(new MinimalRequest()) {
            @Override public String getRemoteAddr() { return remoteAddr; }
            @Override public String getHeader(String name) {
                if ("X-Real-IP".equalsIgnoreCase(name)) return xRealIp;
                return null;
            }
        };
    }

    /** Bare minimum to satisfy HttpServletRequestWrapper. */
    private static class MinimalRequest implements HttpServletRequest {
        public String getAuthType() { return null; }
        public Cookie[] getCookies() { return null; }
        public long getDateHeader(String n) { return -1; }
        public String getHeader(String n) { return null; }
        public Enumeration<String> getHeaders(String n) { return Collections.emptyEnumeration(); }
        public Enumeration<String> getHeaderNames() { return Collections.emptyEnumeration(); }
        public int getIntHeader(String n) { return -1; }
        public String getMethod() { return "POST"; }
        public String getPathInfo() { return null; }
        public String getPathTranslated() { return null; }
        public String getContextPath() { return ""; }
        public String getQueryString() { return null; }
        public String getRemoteUser() { return null; }
        public boolean isUserInRole(String r) { return false; }
        public Principal getUserPrincipal() { return null; }
        public String getRequestedSessionId() { return null; }
        public String getRequestURI() { return "/test"; }
        public StringBuffer getRequestURL() { return new StringBuffer("http://localhost/test"); }
        public String getServletPath() { return "/test"; }
        public HttpSession getSession(boolean c) { return null; }
        public HttpSession getSession() { return null; }
        public String changeSessionId() { return null; }
        public boolean isRequestedSessionIdValid() { return false; }
        public boolean isRequestedSessionIdFromCookie() { return false; }
        public boolean isRequestedSessionIdFromURL() { return false; }
        public boolean authenticate(HttpServletResponse r) { return false; }
        public void login(String u, String p) {}
        public void logout() {}
        public Collection<Part> getParts() { return List.of(); }
        public Part getPart(String n) { return null; }
        public <T extends HttpUpgradeHandler> T upgrade(Class<T> c) { return null; }
        public Object getAttribute(String n) { return null; }
        public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
        public String getCharacterEncoding() { return "UTF-8"; }
        public void setCharacterEncoding(String e) {}
        public int getContentLength() { return -1; }
        public long getContentLengthLong() { return -1; }
        public String getContentType() { return null; }
        public ServletInputStream getInputStream() { return null; }
        public String getParameter(String n) { return null; }
        public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
        public String[] getParameterValues(String n) { return null; }
        public Map<String, String[]> getParameterMap() { return Map.of(); }
        public String getProtocol() { return "HTTP/1.1"; }
        public String getScheme() { return "http"; }
        public String getServerName() { return "localhost"; }
        public int getServerPort() { return 8080; }
        public BufferedReader getReader() { return null; }
        public String getRemoteAddr() { return "127.0.0.1"; }
        public String getRemoteHost() { return "localhost"; }
        public void setAttribute(String n, Object o) {}
        public void removeAttribute(String n) {}
        public Locale getLocale() { return Locale.getDefault(); }
        public Enumeration<Locale> getLocales() { return Collections.emptyEnumeration(); }
        public boolean isSecure() { return false; }
        public RequestDispatcher getRequestDispatcher(String p) { return null; }
        public int getRemotePort() { return 0; }
        public String getLocalName() { return "localhost"; }
        public String getLocalAddr() { return "127.0.0.1"; }
        public int getLocalPort() { return 8080; }
        public ServletContext getServletContext() { return null; }
        public AsyncContext startAsync() { return null; }
        public AsyncContext startAsync(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) { return null; }
        public boolean isAsyncStarted() { return false; }
        public boolean isAsyncSupported() { return false; }
        public AsyncContext getAsyncContext() { return null; }
        public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
        public String getRequestId() { return "test"; }
        public String getProtocolRequestId() { return ""; }
        public ServletConnection getServletConnection() { return null; }
    }
}
