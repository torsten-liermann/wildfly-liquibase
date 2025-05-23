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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;
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
    public List<Resource> getAll(String path) throws IOException {
        List<Resource> resources = new ArrayList<>();
        ClassLoader classLoader = configuration.getClassLoader();

        // Handle VFS-specific paths
        if (path.contains("/vfs/")) {
            int index = path.indexOf(VFS_CONTENTS_PATH_MARKER);
            if (index > -1) {
                String resolvedPath = path.substring(index + VFS_CONTENTS_PATH_MARKER.length());
                URL resource = classLoader.getResource(resolvedPath);
                if (resource != null) {
                    resources.add(new VFSResource(resolvedPath, resource, this));
                }
            }
            return resources;
        }

        // Handle regular paths
        URL resource = classLoader.getResource(path);
        if (resource != null) {
            resources.add(new VFSResource(path, resource, this));
            return resources;
        }

        // Fallback to parent implementation
        return super.getAll(path);
    }

    @Override
    @Deprecated
    public InputStreamList openStreams(String relativeTo, String path) throws IOException {
        // MIGRATION NOTE: This method is deprecated. Use getAll(path) and Resource.openInputStream() instead.
        // This implementation delegates to the modern approach for consistency.
        InputStreamList inputStreamList = new InputStreamList();
        List<Resource> resources = getAll(path);
        
        for (Resource resource : resources) {
            if (resource.exists()) {
                try {
                    inputStreamList.add(resource.getUri(), resource.openInputStream());
                } catch (IOException e) {
                    // Log and continue with other resources
                    // In the original implementation, individual failures were handled gracefully
                }
            }
        }
        
        // If no resources found via modern approach, fallback to parent
        if (inputStreamList.isEmpty()) {
            return super.openStreams(relativeTo, path);
        }
        
        return inputStreamList;
    }

    @Override
    @Deprecated
    public SortedSet<String> list(String relativeTo, String path, boolean includeFiles, boolean includeDirectories, boolean recursive) {
        SortedSet<String> resources = new TreeSet<>();
        ClassLoader classLoader = configuration.getClassLoader();

        if (relativeTo != null) {
            String tempPath =  configuration.getPath().replace("/content/" + configuration.getDeployment(), "");
            final String parentPath = tempPath.replace(Objects.requireNonNull(configuration.getFileName()), "");
            URL parentUrl = classLoader.getResource(parentPath + path);

            if (parentUrl == null) {
                throw new IllegalStateException("Cannot locate resource parent of " + relativeTo);
            }

            URI parentUri;
            try {
                parentUri = parentUrl.toURI();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid parent resource URI " + parentUrl);
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
}
