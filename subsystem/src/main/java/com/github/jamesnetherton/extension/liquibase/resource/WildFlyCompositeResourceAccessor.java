/*-
 * #%L
 * wildfly-liquibase-itests
 * %%
 * Copyright (C) 2017 - 2019 James Netherton
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
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor;

public class WildFlyCompositeResourceAccessor implements ResourceAccessor {

    private final ResourceAccessor[] resourceAccessors;

    public WildFlyCompositeResourceAccessor(ResourceAccessor... resourceAccessors) {
        this.resourceAccessors = resourceAccessors;
    }

    @Override
    public InputStreamList openStreams(String relativeTo, String streamPath) throws IOException {
        WildFlyInputStreamList answer = new WildFlyInputStreamList();
        for (ResourceAccessor accessor : resourceAccessors) {
            answer.addAll(accessor.openStreams(relativeTo, streamPath));
        }
        return answer;
    }

    @Override
    public InputStream openStream(String relativeTo, String streamPath) throws IOException {
        InputStreamList streamList = this.openStreams(relativeTo, streamPath);
        if (streamList == null || streamList.size() == 0) {
            return null;
        } else {
            return streamList.iterator().next();
        }
    }

    @Override
    public SortedSet<String> list(String relativeTo, String path, boolean recursive, boolean includeFiles, boolean includeDirectories) throws IOException {
        SortedSet<String> returnSet = new TreeSet<>();
        for (ResourceAccessor accessor : resourceAccessors) {
            final SortedSet<String> list = accessor.list(relativeTo, path, recursive, includeFiles, includeDirectories);
            if (list != null) {
                returnSet.addAll(list);
            }
        }
        return returnSet;
    }

    @Override
    public List<String> describeLocations() {
        List<String> locations = new ArrayList<>();
        for (ResourceAccessor accessor : resourceAccessors) {
            List<String> accessorLocations = accessor.describeLocations();
            if (accessorLocations != null) {
                locations.addAll(accessorLocations);
            }
        }
        return locations;
    }

    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        List<Resource> resources = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (ResourceAccessor accessor : resourceAccessors) {
            List<Resource> found = accessor.search(path, recursive);
            if (found != null) {
                for (Resource resource : found) {
                    // De-duplicate resources by their path to avoid Liquibase 4.29+ duplicate file errors
                    String resourcePath = resource.getPath();
                    // Normalize path by extracting the file name portion for comparison
                    String normalizedPath = resourcePath;
                    if (normalizedPath.contains("/contents/")) {
                        // VFS extracted path: extract portion after /contents/
                        int idx = normalizedPath.indexOf("/contents/");
                        normalizedPath = normalizedPath.substring(idx + "/contents/".length());
                    } else if (normalizedPath.startsWith("vfs:/content/")) {
                        // VFS virtual path: extract the logical path
                        normalizedPath = normalizedPath.replaceFirst("vfs:/content/[^/]+/", "");
                    }
                    if (seenPaths.add(normalizedPath)) {
                        resources.add(resource);
                    }
                }
            }
        }
        return resources;
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        List<Resource> resources = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (ResourceAccessor accessor : resourceAccessors) {
            List<Resource> found = accessor.getAll(path);
            if (found != null) {
                for (Resource resource : found) {
                    // De-duplicate resources by their path to avoid Liquibase 4.29+ duplicate file errors
                    String resourcePath = resource.getPath();
                    // Normalize path by extracting the file name portion for comparison
                    String normalizedPath = resourcePath;
                    if (normalizedPath.contains("/contents/")) {
                        // VFS extracted path: extract portion after /contents/
                        int idx = normalizedPath.indexOf("/contents/");
                        normalizedPath = normalizedPath.substring(idx + "/contents/".length());
                    } else if (normalizedPath.startsWith("vfs:/content/")) {
                        // VFS virtual path: extract the logical path
                        normalizedPath = normalizedPath.replaceFirst("vfs:/content/[^/]+/", "");
                    }
                    if (seenPaths.add(normalizedPath)) {
                        resources.add(resource);
                    }
                }
            }
        }
        return resources;
    }

    @Override
    public void close() throws Exception {
        for (ResourceAccessor accessor : resourceAccessors) {
            accessor.close();
        }
    }
}
