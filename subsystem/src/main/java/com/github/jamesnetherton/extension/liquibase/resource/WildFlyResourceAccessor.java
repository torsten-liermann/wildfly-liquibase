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

import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration;
import com.github.jamesnetherton.extension.liquibase.ChangeLogFormat;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import liquibase.resource.AbstractResource;
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;

public final class WildFlyResourceAccessor extends VFSResourceAccessor {

    private static final String LIQUIBASE_ELEMENT_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\" \n" + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n"
        + "xmlns:ext=\"http://www.liquibase.org/xml/ns/dbchangelog-ext\" \n"
        + "xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\n"
        + "http://www.liquibase.org/xml/ns/dbchangelog-ext http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-ext.xsd\">\n";
    private static final String LIQUIBASE_ELEMENT_END = "</databaseChangeLog>";
    private static final String LIQUIBASE_XSD_PATH = "www.liquibase.org/xml/ns/dbchangelog";

    public WildFlyResourceAccessor(ChangeLogConfiguration configuration) {
        super(configuration);
    }

    @Override
    public InputStreamList openStreams(String relativeTo, String path) throws IOException {
        // Prevent 'Found x copies of resource' errors
        if (path.startsWith(LIQUIBASE_XSD_PATH)) {
            return null;
        }

        File file = new File(path);
        InputStreamList resources = new InputStreamList();
        InputStream resource = configuration.getClassLoader().getResourceAsStream(path);

        if (resource == null) {
            String definition = configuration.getDefinition();
            if (definition != null && configuration.isSubsystemOrigin() && configuration.getFormat().equals(ChangeLogFormat.XML)) {
                if (!definition.contains("http://www.liquibase.org/xml/ns/dbchangelog")) {
                    definition = LIQUIBASE_ELEMENT_START + definition;
                }

                if (!definition.contains(LIQUIBASE_ELEMENT_END)) {
                    definition += LIQUIBASE_ELEMENT_END;
                }
            }

            if (definition != null && path.equals(configuration.getFileName())) {
                resources.add(file.toURI(), new ByteArrayInputStream(definition.getBytes(StandardCharsets.UTF_8)));
            } else if (definition == null || !path.equals(configuration.getFileName())) {
                resources = super.openStreams(relativeTo, path);
                if (resources == null || resources.isEmpty()) {
                    // Attempt to work out the 'relative to' change log path
                    String parentPath =  configuration.getPath().replace("/content/" + configuration.getDeployment(), "");
                    parentPath = parentPath.replace(configuration.getFileName(), "");
                    resource = configuration.getClassLoader().getResourceAsStream(parentPath + path);
                    if (resource != null) {
                        resources = new InputStreamList();
                        resources.add(file.toURI(), resource);
                    }
                }
            }
        } else {
            resources.add(file.toURI(), resource);
        }

        return resources;
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        // If file is in WEB-INF (not on classpath), let FileSystemResourceAccessor handle it
        // WEB-INF files (not in classes or lib/*.jar) are not accessible via ClassLoader
        String vfsPath = configuration.getPath();
        boolean isWebInfNonClasspath = vfsPath != null
            && vfsPath.contains("/WEB-INF/")
            && !vfsPath.contains("/WEB-INF/classes/")
            && !vfsPath.contains(".jar/");
        if (isWebInfNonClasspath && path != null && path.equals(configuration.getFileName())) {
            return super.getAll(path);
        }
        // First check if this is the main changelog file with pre-loaded definition
        if (path != null && path.equals(configuration.getFileName())) {
            String definition = configuration.getDefinition();
            if (definition != null && !definition.isEmpty()) {
                // Wrap the definition for subsystem origin XML changelogs
                if (configuration.isSubsystemOrigin() && configuration.getFormat().equals(ChangeLogFormat.XML)) {
                    if (!definition.contains("http://www.liquibase.org/xml/ns/dbchangelog")) {
                        definition = LIQUIBASE_ELEMENT_START + definition;
                    }
                    if (!definition.contains(LIQUIBASE_ELEMENT_END)) {
                        definition += LIQUIBASE_ELEMENT_END;
                    }
                }
                final String finalDefinition = definition;
                final String currentPath = path;
                final WildFlyResourceAccessor accessor = this;
                List<Resource> result = new ArrayList<>();
                result.add(new AbstractResource(path, new File(path).toURI()) {
                    @Override
                    public InputStream openInputStream() throws IOException {
                        return new ByteArrayInputStream(finalDefinition.getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public boolean exists() {
                        return true;
                    }

                    @Override
                    public Resource resolve(String other) {
                        return resolveRelativePath(other);
                    }

                    @Override
                    public Resource resolveSibling(String other) {
                        return resolveRelativePath(other);
                    }

                    private Resource resolveRelativePath(String other) {
                        // Compute the resolved path relative to the current changelog
                        int lastSlash = currentPath.lastIndexOf('/');
                        String parentDir = lastSlash > 0 ? currentPath.substring(0, lastSlash + 1) : "";
                        String resolvedPath = parentDir + other;

                        // Clean up path (remove double slashes, handle ../)
                        resolvedPath = resolvedPath.replace("//", "/");
                        if (resolvedPath.startsWith("/")) {
                            resolvedPath = resolvedPath.substring(1);
                        }

                        try {
                            List<Resource> resolved = accessor.getAll(resolvedPath);
                            return (resolved != null && !resolved.isEmpty()) ? resolved.get(0) : null;
                        } catch (IOException e) {
                            return null;
                        }
                    }
                });
                return result;
            }
        }
        // Fall back to parent implementation
        return super.getAll(path);
    }

    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        // First check if this matches the main changelog file
        if (path != null && (path.equals(configuration.getFileName()) ||
                configuration.getFileName().startsWith(path) ||
                configuration.getFileName().endsWith(path))) {
            List<Resource> resources = getAll(configuration.getFileName());
            if (resources != null && !resources.isEmpty()) {
                return resources;
            }
        }
        return super.search(path, recursive);
    }
}
