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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import liquibase.resource.OpenOptions;
import liquibase.resource.Resource;

/**
 * A Liquibase Resource implementation for inline changelog definitions.
 * This is used when changelog content is provided directly in the DMR model
 * rather than being loaded from a file.
 */
public class InlineChangeLogResource implements Resource {
    
    private final String path;
    private final String content;
    
    public InlineChangeLogResource(String path, String content) {
        this.path = path;
        this.content = content;
    }
    
    @Override
    public String getPath() {
        return path;
    }
    
    @Override
    public InputStream openInputStream() throws IOException {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public boolean exists() {
        return true;
    }
    
    @Override
    public liquibase.resource.Resource resolve(String other) {
        // Inline resources don't support relative resolution
        return null;
    }
    
    @Override
    public liquibase.resource.Resource resolveSibling(String other) {
        // Inline resources don't support sibling resolution
        return null;
    }
    
    @Override
    public boolean isWritable() {
        // Inline resources are read-only
        return false;
    }
    
    @Override
    public OutputStream openOutputStream(OpenOptions options) throws IOException {
        throw new IOException("Inline changelog resources are read-only");
    }
    
    @Override
    public URI getUri() {
        try {
            // Create a synthetic URI for the inline resource
            return new URI("inline", null, "/" + path, null);
        } catch (Exception e) {
            return null;
        }
    }
}