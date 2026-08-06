package com.woobeee.mvc._common.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class MutableHttpServletRequest extends HttpServletRequestWrapper {
    private final Map<String, String> customHeaders = new LinkedHashMap<>();
    private final Set<String> removedHeaders = new LinkedHashSet<>();

    public MutableHttpServletRequest(HttpServletRequest request) {
        super(request);
    }

    public void putHeader(String name, String value) {
        removedHeaders.remove(name);
        customHeaders.put(name, value);
    }

    public void removeHeader(String name) {
        customHeaders.remove(name);
        removedHeaders.add(name);
    }

    @Override
    public String getHeader(String name) {
        if (removedHeaders.contains(name)) {
            return null;
        }
        String value = customHeaders.get(name);
        return value != null ? value : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> headerNames = new LinkedHashSet<>(customHeaders.keySet());
        Enumeration<String> originalHeaderNames = super.getHeaderNames();
        while (originalHeaderNames.hasMoreElements()) {
            headerNames.add(originalHeaderNames.nextElement());
        }
        headerNames.removeAll(removedHeaders);
        return Collections.enumeration(headerNames);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (removedHeaders.contains(name)) {
            return Collections.emptyEnumeration();
        }
        String value = customHeaders.get(name);
        if (value != null) {
            return Collections.enumeration(Collections.singletonList(value));
        }
        return super.getHeaders(name);
    }
}
