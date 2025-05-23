/*-
 * #%L
 * wildfly-liquibase-subsystem
 * %%
 * Copyright (C) 2017 James Netherton
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.jamesnetherton.extension.liquibase.resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import liquibase.resource.AbstractResource;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor;

/**
 * Modern Resource implementation for VFS-based resources in WildFly.
 * Replaces deprecated openStreams pattern with Resource-based approach.
 */
public class VFSResource extends AbstractResource {
    
    private final String path;
    private final URL url;
    
    public VFSResource(String path, URL url, ResourceAccessor resourceAccessor) {
        super(path, getUriFromUrl(url));
        this.path = path;
        this.url = url;
    }
    
    private static URI getUriFromUrl(URL url) {
        if (url == null) {
            return null;
        }
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI for URL: " + url, e);
        }
    }
    
    @Override
    public String getPath() {
        return path;
    }
    
    @Override
    public boolean exists() {
        return url != null;
    }
    
    @Override
    public InputStream openInputStream() throws IOException {
        if (url == null) {
            return null;
        }
        return url.openStream();
    }
    
    @Override
    @Deprecated
    public OutputStream openOutputStream(boolean append) throws IOException {
        // MIGRATION NOTE: This method is deprecated. VFS resources are read-only in WildFly.
        throw new IOException("VFS resources are read-only");
    }
    
    // MIGRATION NOTE: Modern API method - not deprecated
    public OutputStream openOutputStream() throws IOException {
        throw new IOException("VFS resources are read-only");
    }
    
    @Override
    public URI getUri() {
        return getUriFromUrl(url);
    }
    
    @Override
    public boolean isWritable() {
        return false;
    }
    
    @Override
    public Resource resolve(String other) {
        // Resolve relative to current path
        String resolvedPath;
        if (other.startsWith("/")) {
            // Absolute path
            resolvedPath = other.substring(1); // Remove leading slash for classloader
        } else {
            // Relative path - resolve relative to current path
            String parentPath = path.contains("/") ? path.substring(0, path.lastIndexOf("/")) : "";
            resolvedPath = parentPath.isEmpty() ? other : parentPath + "/" + other;
        }
        
        // Try to get the URL for the resolved path
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            URL resolvedUrl = cl.getResource(resolvedPath);
            return new VFSResource(resolvedPath, resolvedUrl, null);
        } catch (Exception e) {
            // Return a resource that doesn't exist
            return new VFSResource(resolvedPath, null, null);
        }
    }

    @Override
    public Resource resolveSibling(String relativePath) {
        // For VFS resources, resolve relative to the parent path
        String parentPath = path.contains("/") ? path.substring(0, path.lastIndexOf("/")) : "";
        String resolvedPath = parentPath.isEmpty() ? relativePath : parentPath + "/" + relativePath;
        
        // Try to get the URL for the resolved path
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            URL resolvedUrl = cl.getResource(resolvedPath);
            return new VFSResource(resolvedPath, resolvedUrl, null);
        } catch (Exception e) {
            // Return a resource that doesn't exist
            return new VFSResource(resolvedPath, null, null);
        }
    }
    
    @Override
    public String toString() {
        return "VFSResource{path='" + path + "', url=" + url + "}";
    }
}