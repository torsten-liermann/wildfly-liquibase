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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import liquibase.resource.AbstractResource;
import liquibase.resource.Resource;

/**
 * Special resource implementation for standalone XML deployments.
 * This resource holds the XML content directly instead of loading from a file.
 */
public class StandaloneXmlResource extends AbstractResource {
    
    private final String xmlContent;
    
    public StandaloneXmlResource(String path, String xmlContent) {
        // Create a synthetic URI for this resource
        super(path, createUri(path));
        this.xmlContent = xmlContent;
    }
    
    private static URI createUri(String path) {
        try {
            return new URI("standalone", null, "/" + path, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create URI for standalone XML resource", e);
        }
    }
    
    @Override
    public Resource resolveSibling(String other) {
        // Standalone XML resources don't have siblings
        return null;
    }
    
    @Override
    public Resource resolve(String other) {
        // Standalone XML resources can't resolve relative paths
        return null;
    }
    
    @Override
    public InputStream openInputStream() throws IOException {
        if (xmlContent == null) {
            throw new IOException("No XML content available for standalone deployment");
        }
        return new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public boolean exists() {
        return xmlContent != null;
    }
    
    @Override
    public boolean isWritable() {
        return false;
    }
}