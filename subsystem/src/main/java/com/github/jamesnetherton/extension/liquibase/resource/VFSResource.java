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
import org.jboss.logging.Logger;

/**
 * Modern Resource implementation for VFS-based resources in WildFly.
 * Replaces deprecated openStreams pattern with Resource-based approach.
 */
public class VFSResource extends AbstractResource {
    
    private static final Logger LOG = Logger.getLogger(VFSResource.class);
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
        // Ensure we never return null
        return path != null ? path : "";
    }
    
    @Override
    public boolean exists() {
        return url != null;
    }
    
    @Override
    public InputStream openInputStream() throws IOException {
        if (url != null) {
            LOG.debugf("VFSResource.openInputStream() opening stream for path: %s, url: %s", path, url);
            InputStream stream = url.openStream();
            if (stream != null) {
                // Log the first few bytes to verify content is readable
                if (LOG.isDebugEnabled()) {
                    stream = new java.io.BufferedInputStream(stream);
                    stream.mark(100);
                    byte[] buffer = new byte[100];
                    int read = stream.read(buffer);
                    if (read > 0) {
                        LOG.debugf("VFSResource.openInputStream() first %d bytes: %s", read, new String(buffer, 0, read, "UTF-8"));
                    }
                    stream.reset();
                }
            }
            return stream;
        }
        throw new IOException("Resource not found: " + path);
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
        if (other == null) {
            return null;
        }
        
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
        
        // Normalize the path
        resolvedPath = normalizePath(resolvedPath);
        
        // Try to get the URL for the resolved path
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            URL resolvedUrl = cl.getResource(resolvedPath);
            // Even if URL is null, create a valid resource with a non-null path
            return new VFSResource(resolvedPath, resolvedUrl, null);
        } catch (Exception e) {
            // Return a resource with a valid path but no URL
            return new VFSResource(resolvedPath, null, null);
        }
    }

    @Override
    public Resource resolveSibling(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        
        // For VFS resources, resolve relative to the parent path
        String parentPath = path.contains("/") ? path.substring(0, path.lastIndexOf("/")) : "";
        String resolvedPath = parentPath.isEmpty() ? relativePath : parentPath + "/" + relativePath;
        
        // Normalize the path to remove any "./" or "../" segments
        resolvedPath = normalizePath(resolvedPath);
        
        // Try to get the URL for the resolved path
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            URL resolvedUrl = cl.getResource(resolvedPath);
            // Even if URL is null, create a valid resource with a non-null path
            return new VFSResource(resolvedPath, resolvedUrl, null);
        } catch (Exception e) {
            // Return a resource with a valid path but no URL
            return new VFSResource(resolvedPath, null, null);
        }
    }
    
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        // Remove leading slash for classloader resources
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        // Simple normalization - could be enhanced
        path = path.replace("./", "");
        while (path.contains("/../")) {
            int idx = path.indexOf("/../");
            if (idx > 0) {
                int prevSlash = path.lastIndexOf("/", idx - 1);
                if (prevSlash >= 0) {
                    path = path.substring(0, prevSlash) + path.substring(idx + 3);
                } else {
                    path = path.substring(idx + 4);
                }
            } else {
                path = path.substring(4);
            }
        }
        return path;
    }
    
    @Override
    public String toString() {
        return "VFSResource{path='" + path + "', url=" + url + "}";
    }
}