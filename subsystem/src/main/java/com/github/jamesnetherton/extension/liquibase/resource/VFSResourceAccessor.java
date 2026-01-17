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
import com.github.jamesnetherton.extension.liquibase.LiquibaseLogger;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import liquibase.resource.AbstractResource;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor.SearchOptions;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;

public class VFSResourceAccessor extends ClassLoaderResourceAccessor {

    protected final ChangeLogConfiguration configuration;
    private static final String VFS_CONTENTS_PATH_MARKER = "contents";

    public VFSResourceAccessor(ChangeLogConfiguration configuration) {
        super(configuration.getClassLoader());
        this.configuration = configuration;
    }

    @Override
    public InputStreamList openStreams(String relativeTo, String path) throws IOException {
        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.openStreams: relativeTo={}, path={}", relativeTo, path);
        InputStreamList resources = new InputStreamList();
        ClassLoader classLoader = configuration.getClassLoader();

        // TODO: Improve this as it could potentially fail in some edge case scenarios
        if (path.contains("/vfs/")) {
            int index = path.indexOf(VFS_CONTENTS_PATH_MARKER);
            if (index > -1) {
                String resolvedPath = path.substring(index + VFS_CONTENTS_PATH_MARKER.length());
                InputStream resource = classLoader.getResourceAsStream(resolvedPath);
                if (resource != null) {
                    try {
                        resources.add(new URI(resolvedPath), resource);
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException("Invalid URI path: " + resolvedPath);
                    }
                }
            }
            return resources;
        }

        // Normalize path
        String normalizedPath = path;
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        // Try direct lookup first
        InputStream resource = classLoader.getResourceAsStream(normalizedPath);
        if (resource != null) {
            try {
                resources.add(new URI(normalizedPath), resource);
                return resources;
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid URI path: " + normalizedPath);
            }
        }

        // If relativeTo is provided and path is relative (doesn't start with /), resolve it
        if (relativeTo != null && !path.startsWith("/")) {
            String resolvedPath = resolveRelativePath(relativeTo, path);
            if (resolvedPath != null) {
                resource = classLoader.getResourceAsStream(resolvedPath);
                if (resource != null) {
                    try {
                        resources.add(new URI(resolvedPath), resource);
                        return resources;
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException("Invalid URI path: " + resolvedPath);
                    }
                }
            }
        }

        return super.openStreams(relativeTo, path);
    }

    /**
     * Resolve a relative path against a parent file path.
     */
    private String resolveRelativePath(String relativeTo, String path) {
        // Normalize relativeTo
        String parentPath = relativeTo;
        if (parentPath.startsWith("/")) {
            parentPath = parentPath.substring(1);
        }

        // Get the parent directory
        int lastSlash = parentPath.lastIndexOf('/');
        String parentDir = lastSlash > 0 ? parentPath.substring(0, lastSlash + 1) : "";

        // Combine with relative path
        String resolvedPath = parentDir + path;

        // Clean up the path
        resolvedPath = resolvedPath.replace("//", "/");
        if (resolvedPath.startsWith("/")) {
            resolvedPath = resolvedPath.substring(1);
        }

        return resolvedPath;
    }

    @Override
    public SortedSet<String> list(String relativeTo, String path, boolean includeFiles, boolean includeDirectories, boolean recursive) {
        SortedSet<String> resources = new TreeSet<>();
        ClassLoader classLoader = configuration.getClassLoader();

        if (relativeTo != null) {
            String tempPath =  configuration.getPath().replace("/content/" + configuration.getDeployment(), "");
            final String parentPath = tempPath.replace(configuration.getFileName(), "");
            URL parentUrl = classLoader.getResource(parentPath + path);

            if (parentUrl == null) {
                throw new IllegalStateException("Cannot locate resource parent of " + relativeTo);
            }

            URI parentUri;
            try {
                parentUri = parentUrl.toURI();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid parent resource URI " + parentUrl.toString());
            }

            VirtualFile parentFile = VFS.getChild(parentUri);
            VirtualFile parentDir = parentFile.getParent();
            VirtualFile changeLogFiles = parentDir.getChild(path);
            changeLogFiles.getChildren()
                .stream()
                .map(VirtualFile::getName)
                .map(name -> parentPath + path + name)
                .forEach(resources::add);
        }

        URL url = classLoader.getResource(path);
        if (url != null) {
            URI uri;
            try {
                uri = url.toURI();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid resource URI " + path);
            }

            VFS.getChild(uri)
                .getChildren()
                .stream()
                .map(VirtualFile::getName)
                .map(name -> path + name)
                .forEach(resources::add);
        }
        return resources;
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        ClassLoader classLoader = configuration.getClassLoader();

        // Normalize path (remove leading slash for classloader lookup)
        String normalizedPath = path;
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        // Try to load directly from classloader first
        InputStream resource = classLoader.getResourceAsStream(normalizedPath);
        if (resource != null) {
            resource.close(); // Close test stream, we'll reopen when needed
            return createResourceList(classLoader, normalizedPath, path);
        }

        // Extract the classpath base path from the VFS path
        String classpathBasePath = getClasspathBasePath();
        if (classpathBasePath != null && !classpathBasePath.isEmpty()) {
            // Try combining base path with relative path
            String fullPath = classpathBasePath + "/" + normalizedPath;
            fullPath = fullPath.replace("//", "/");
            if (fullPath.startsWith("/")) {
                fullPath = fullPath.substring(1);
            }

            resource = classLoader.getResourceAsStream(fullPath);
            if (resource != null) {
                resource.close();
                return createResourceList(classLoader, fullPath, path);
            }
        }

        // Fall back to parent implementation
        return super.getAll(path);
    }

    /**
     * Extract the classpath resource base path (parent directory) from the VFS path.
     * VFS paths look like: /path/to/wildfly/tmp/vfs/.../contents/com/example/changelog.xml
     * We need to extract "com/example" as the base path.
     */
    private String getClasspathBasePath() {
        String vfsPath = configuration.getPath();
        if (vfsPath == null) {
            return null;
        }

        // Look for /contents/ marker which indicates where the archive contents start
        int contentsIdx = vfsPath.indexOf("/contents/");
        if (contentsIdx > 0) {
            String classpathPath = vfsPath.substring(contentsIdx + "/contents/".length());
            // Remove the filename to get the parent directory
            int lastSlash = classpathPath.lastIndexOf('/');
            if (lastSlash > 0) {
                return classpathPath.substring(0, lastSlash);
            }
            return "";
        }

        // Fallback: try to use the path relative to deployment
        String parentPath = vfsPath.replace("/content/" + configuration.getDeployment(), "");
        parentPath = parentPath.replace(configuration.getFileName(), "");
        if (parentPath.endsWith("/")) {
            parentPath = parentPath.substring(0, parentPath.length() - 1);
        }
        if (parentPath.startsWith("/")) {
            parentPath = parentPath.substring(1);
        }
        return parentPath;
    }

    @Override
    public List<Resource> search(String path, SearchOptions searchOptions) throws IOException {
        boolean recursive = searchOptions != null && searchOptions.getRecursive();
        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search(SearchOptions): path={}, recursive={}", path, recursive);

        // VFS-based implementation for WildFly deployments
        List<Resource> resources = new ArrayList<>();
        ClassLoader classLoader = configuration.getClassLoader();

        // Normalize path (remove leading slash)
        String normalizedPath = path;
        if (normalizedPath != null && normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: trying VFS, normalized={}", normalizedPath);

        // Try to find the directory using classloader
        URL url = classLoader.getResource(normalizedPath);
        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: classloader.getResource({}) = {}", normalizedPath, url);
        if (url != null) {
            try {
                URI uri = url.toURI();
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: URI = {}", uri);
                VirtualFile dir = VFS.getChild(uri);
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: VFS dir = {}, isDirectory = {}, exists = {}", dir, dir.isDirectory(), dir.exists());
                if (dir.isDirectory()) {
                    // Enumerate all changelog files in the directory
                    List<VirtualFile> children = recursive ? dir.getChildrenRecursively() : dir.getChildren();
                    LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: found {} children", children.size());
                    for (VirtualFile child : children) {
                        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: child = {}, isFile = {}", child.getName(), child.isFile());
                        if (child.isFile()) {
                            String fileName = child.getName().toLowerCase();
                            // Apply endsWithFilter if specified
                            String endsWithFilter = searchOptions != null ? searchOptions.getEndsWithFilter() : "";
                            if (endsWithFilter != null && !endsWithFilter.isEmpty()) {
                                if (!fileName.endsWith(endsWithFilter.toLowerCase())) {
                                    continue;
                                }
                            }
                            // Only include changelog files (xml, yaml, yml, json, sql)
                            if (fileName.endsWith(".xml") || fileName.endsWith(".yaml") ||
                                fileName.endsWith(".yml") || fileName.endsWith(".json") ||
                                fileName.endsWith(".sql")) {
                                // Build the classpath path for this file
                                String childPath = normalizedPath;
                                if (!childPath.endsWith("/")) {
                                    childPath += "/";
                                }
                                // Get relative path from dir to child
                                String relativePath = child.getPathNameRelativeTo(dir);
                                String fullPath = childPath + relativePath;
                                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: constructing fullPath = {}", fullPath);
                                List<Resource> childResources = getAll(fullPath);
                                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: getAll({}) returned {} resources", fullPath, childResources != null ? childResources.size() : 0);
                                if (childResources != null) {
                                    resources.addAll(childResources);
                                }
                            }
                        }
                    }
                }
            } catch (URISyntaxException e) {
                throw new IOException("Invalid URI for path: " + normalizedPath, e);
            }
        }

        // If VFS didn't find anything, try parent implementation
        if (resources.isEmpty()) {
            try {
                List<Resource> parentResources = super.search(path, searchOptions);
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: parent returned {} resources", parentResources != null ? parentResources.size() : 0);
                if (parentResources != null && !parentResources.isEmpty()) {
                    return parentResources;
                }
            } catch (IOException e) {
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.search: parent failed with: {}", e.getMessage());
            }
        }

        return resources;
    }

    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        SearchOptions searchOptions = new SearchOptions();
        searchOptions.setRecursive(recursive);
        return search(path, searchOptions);
    }

    private List<Resource> createResourceList(ClassLoader classLoader, String resolvedPath, String originalPath) {
        List<Resource> result = new ArrayList<>();
        final String finalPath = resolvedPath;
        final VFSResourceAccessor accessor = this;
        LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.createResourceList: resolvedPath={}, originalPath={}", resolvedPath, originalPath);
        result.add(new AbstractResource(originalPath, URI.create("classpath:" + resolvedPath)) {
            @Override
            public InputStream openInputStream() throws IOException {
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.openInputStream: finalPath={}", finalPath);
                InputStream is = classLoader.getResourceAsStream(finalPath);
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.openInputStream: stream={}", is != null ? "not null" : "null");
                return is;
            }

            @Override
            public boolean exists() {
                boolean exists = classLoader.getResource(finalPath) != null;
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.exists: finalPath={}, exists={}", finalPath, exists);
                return exists;
            }

            @Override
            public Resource resolve(String other) {
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.resolve: other={}, finalPath={}", other, finalPath);
                // Handle relative path resolution
                int lastSlash = finalPath.lastIndexOf('/');
                String newPath = lastSlash > 0 ? finalPath.substring(0, lastSlash + 1) + other : other;
                // Clean up the path
                newPath = newPath.replace("//", "/");
                if (newPath.startsWith("/")) {
                    newPath = newPath.substring(1);
                }
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.resolve: newPath={}", newPath);
                try {
                    List<Resource> resolved = accessor.getAll(newPath);
                    LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.resolve: resolved {} resources", resolved != null ? resolved.size() : 0);
                    return resolved != null && !resolved.isEmpty() ? resolved.get(0) : null;
                } catch (IOException e) {
                    LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.resolve: error={}", e.getMessage());
                    return null;
                }
            }

            @Override
            public Resource resolveSibling(String other) {
                LiquibaseLogger.ROOT_LOGGER.info("VFSResourceAccessor.Resource.resolveSibling: other={}", other);
                return resolve(other);
            }
        });
        return result;
    }
}
