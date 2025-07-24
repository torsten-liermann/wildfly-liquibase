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
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
/*-
 * #%L
 * wildfly-liquibase-subsystem
 * %%
 * Copyright (C) 2017 - 2020 James Netherton
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
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor;
import org.jboss.logging.Logger;

public class WildFlyCompositeResourceAccessor implements ResourceAccessor {

    private static final Logger LOG = Logger.getLogger(WildFlyCompositeResourceAccessor.class);
    private final ResourceAccessor[] resourceAccessors;

    public WildFlyCompositeResourceAccessor(ResourceAccessor... resourceAccessors) {
        this.resourceAccessors = resourceAccessors;
    }

    @Override
    @Deprecated
    public InputStreamList openStreams(String relativeTo, String streamPath) throws IOException {
        WildFlyInputStreamList answer = new WildFlyInputStreamList();
        for (ResourceAccessor accessor : resourceAccessors) {
            answer.addAll(accessor.openStreams(relativeTo, streamPath));
        }
        return answer;
    }

    @Override
    @Deprecated
    public InputStream openStream(String relativeTo, String streamPath) throws IOException {
        InputStreamList streamList = this.openStreams(relativeTo, streamPath);
        if (streamList == null || streamList.isEmpty()) {
            return null;
        } else {
            return streamList.iterator().next();
        }
    }

    @Override
    @Deprecated
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

    // MIGRATION NOTE: Added missing getAll method for Liquibase 4.32.0 compatibility
    @Override
    public List<Resource> getAll(String path) throws IOException {
        LOG.infof("WildFlyCompositeResourceAccessor.getAll() called with path: %s", path);
        List<Resource> resources = new ArrayList<>();
        for (ResourceAccessor accessor : resourceAccessors) {
            final List<Resource> list = accessor.getAll(path);
            if (list != null) {
                // Deduplicate resources based on URI to avoid "Found 2 files" errors
                for (Resource newResource : list) {
                    boolean isDuplicate = false;
                    for (Resource existingResource : resources) {
                        if (existingResource.getUri() != null && newResource.getUri() != null) {
                            // Compare URIs after normalizing paths
                            String existingPath = existingResource.getUri().toString();
                            String newPath = newResource.getUri().toString();
                            
                            // Remove VFS-specific path components for comparison
                            existingPath = normalizeVfsPath(existingPath);
                            newPath = normalizeVfsPath(newPath);
                            
                            if (existingPath.equals(newPath)) {
                                isDuplicate = true;
                                break;
                            }
                        }
                    }
                    if (!isDuplicate) {
                        resources.add(newResource);
                    }
                }
            }
        }
        return resources;
    }
    
    private String normalizeVfsPath(String path) {
        // Remove VFS-specific components for path comparison
        if (path.contains("/vfs/")) {
            int vfsIndex = path.indexOf("/vfs/");
            int contentsIndex = path.indexOf("/contents/", vfsIndex);
            if (contentsIndex > -1) {
                return path.substring(contentsIndex + "/contents/".length());
            }
        }
        // Remove file:// prefix if present
        if (path.startsWith("file://")) {
            path = path.substring(7);
        } else if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        return path;
    }

    // MIGRATION NOTE: Added missing search method for Liquibase 4.32.0 compatibility
    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        LOG.infof("WildFlyCompositeResourceAccessor.search() called with path=%s, recursive=%s", path, recursive);
        List<Resource> resources = new ArrayList<>();
        
        for (ResourceAccessor accessor : resourceAccessors) {
            try {
                final List<Resource> list = accessor.search(path, recursive);
                LOG.infof("ResourceAccessor %s found %d resources for path %s", 
                         accessor.getClass().getSimpleName(), list != null ? list.size() : 0, path);
                if (list != null && !list.isEmpty()) {
                    resources.addAll(list);
                }
            } catch (IOException e) {
                // Log the error but continue with other accessors
                // This is important for VFS URLs that some accessors can't handle
                LOG.debugf("ResourceAccessor %s failed to search path %s: %s", 
                          accessor.getClass().getSimpleName(), path, e.getMessage());
            } catch (Exception e) {
                // Catch any other exceptions to ensure we continue with other accessors
                LOG.debugf("ResourceAccessor %s threw exception searching path %s: %s", 
                          accessor.getClass().getSimpleName(), path, e.getMessage());
            }
        }
        
        LOG.infof("WildFlyCompositeResourceAccessor.search() total resources found: %d", resources.size());
        LOG.infof("WildFlyCompositeResourceAccessor.search() returning %d resources", resources.size());
        return resources;
    }

    // MIGRATION NOTE: Updated return type for Liquibase 4.32.0 compatibility
    @Override
    public List<String> describeLocations() {
        return Collections.emptyList();
    }

    // MIGRATION NOTE: Added close method for AutoCloseable interface in Liquibase 4.32.0
    @Override
    public void close() throws Exception {
        for (ResourceAccessor accessor : resourceAccessors) {
            if (accessor != null) {
                accessor.close();
            }
        }
    }
}
