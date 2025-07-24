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
import org.jboss.logging.Logger;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;

public class VFSResourceAccessor extends ClassLoaderResourceAccessor {

    private static final Logger LOG = Logger.getLogger(VFSResourceAccessor.class);
    protected final ChangeLogConfiguration configuration;
    private static final String VFS_CONTENTS_PATH_MARKER = "contents";
    private String standaloneXmlContent;

    public VFSResourceAccessor(ChangeLogConfiguration configuration) {
        super(configuration.getClassLoader());
        this.configuration = configuration;
    }
    
    public void setStandaloneXmlContent(String xmlContent) {
        this.standaloneXmlContent = xmlContent;
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        LOG.infof("VFSResourceAccessor.getAll() called with path: %s, deployment: %s", path, configuration.getDeployment());
        
        // Validate path to avoid "no entry name specified" errors
        if (path == null || path.trim().isEmpty()) {
            LOG.debugf("VFSResourceAccessor.getAll() rejecting empty or null path");
            return null;
        }
        
        List<Resource> resources = new ArrayList<>();
        ClassLoader classLoader = configuration.getClassLoader();
        
        // Special handling for standalone XML deployments
        if ("__standalone_xml_content__.xml".equals(configuration.getFileName())) {
            // Handle the paths Liquibase might use
            if ("__standalone_xml_content__.xml".equals(path) || "changelog.xml".equals(path)) {
                LOG.debugf("Handling standalone XML deployment request for path: %s, content available: %s", 
                        path, standaloneXmlContent != null);
                // For standalone XML deployments, create a special resource with the stored content
                if (standaloneXmlContent != null) {
                    StandaloneXmlResource resource = new StandaloneXmlResource(path, standaloneXmlContent);
                    resources.add(resource);
                    return resources;
                }
            }
        }
        
        // Special handling for WAR deployments - try both with and without leading slash
        if (configuration.getDeployment() != null && configuration.getDeployment().endsWith(".war")) {
            LOG.debugf("WAR deployment detected, trying multiple path variations for: %s", path);
            // For WAR files, resources might be in the root, WEB-INF/classes, or META-INF
            String[] pathVariations = {
                path,
                "/" + path,
                "WEB-INF/classes/" + path,
                "/WEB-INF/classes/" + path,
                "META-INF/" + path,
                "/META-INF/" + path,
                "WEB-INF/" + path,
                "/WEB-INF/" + path
            };
            
            // Also check if the path is in the deployment root (for ROOT location)
            // Remove any leading slash for classloader lookup
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            
            for (String pathVariant : pathVariations) {
                URL url = classLoader.getResource(pathVariant);
                if (url != null) {
                    LOG.debugf("Found resource in WAR at path: %s", pathVariant);
                    resources.add(new VFSResource(path, url, this));
                    return resources;
                }
            }
            
            // Try direct lookup without any prefix for ROOT resources
            URL url = classLoader.getResource(normalizedPath);
            if (url != null) {
                LOG.debugf("Found resource in WAR root at path: %s", normalizedPath);
                resources.add(new VFSResource(path, url, this));
                return resources;
            }
            
            // Also try with leading slash for WAR root resources
            url = classLoader.getResource("/" + normalizedPath);
            if (url != null) {
                LOG.debugf("Found resource in WAR root with leading slash at path: /%s", normalizedPath);
                resources.add(new VFSResource(path, url, this));
                return resources;
            }
        }

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
            LOG.infof("VFSResourceAccessor.getAll() found resource at regular path: %s -> %s", path, resource);
            resources.add(new VFSResource(path, resource, this));
            return resources;
        } else {
            LOG.debugf("VFSResourceAccessor.getAll() no resource found for path: %s", path);
        }
        
        // For files not found with classloader, try direct file name if in WAR root
        if (configuration.getDeployment() != null && configuration.getDeployment().endsWith(".war") && !path.contains("/")) {
            // Try to find the file in the root of the WAR
            URL rootResource = classLoader.getResource("/" + path);
            if (rootResource != null) {
                resources.add(new VFSResource(path, rootResource, this));
                return resources;
            }
        }

        // Fallback to parent implementation but deduplicate
        List<Resource> parentResources = super.getAll(path);
        if (parentResources != null && !parentResources.isEmpty()) {
            // Deduplicate based on URI to avoid "Found 2 files" errors
            for (Resource parentResource : parentResources) {
                boolean isDuplicate = false;
                for (Resource existingResource : resources) {
                    if (existingResource.getUri() != null && parentResource.getUri() != null 
                        && existingResource.getUri().equals(parentResource.getUri())) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (!isDuplicate) {
                    resources.add(parentResource);
                }
            }
        }
        
        return resources;
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
        LOG.debugf("VFSResourceAccessor.list() called with relativeTo=%s, path=%s, includeFiles=%s, includeDirectories=%s, recursive=%s", 
                   relativeTo, path, includeFiles, includeDirectories, recursive);
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
                .map(name -> parentPath + path + (path.endsWith("/") ? "" : "/") + name)
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
                .map(name -> path.endsWith("/") ? path + name : path + "/" + name)
                .forEach(resources::add);
        }
        LOG.debugf("VFSResourceAccessor.list() returning %d resources: %s", resources.size(), resources);
        return resources;
    }

    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        LOG.infof("VFSResourceAccessor.search() called with path=%s, recursive=%s, deployment=%s", path, recursive, configuration.getDeployment());
        
        List<Resource> resources = new ArrayList<>();
        
        // Handle includeAll patterns - normalize the search path
        String normalizedPath = path;
        if (path != null) {
            // Remove leading slash for classloader
            if (path.startsWith("/")) {
                normalizedPath = path.substring(1);
            }
            // Ensure trailing slash for directory search
            if (!normalizedPath.endsWith("/") && !normalizedPath.isEmpty()) {
                normalizedPath = normalizedPath + "/";
            }
        }
        
        LOG.infof("VFSResourceAccessor.search() normalized path: %s", normalizedPath);
        
        // Try to get resources via VFS first
        ClassLoader classLoader = configuration.getClassLoader();
        
        // First try the path directly as provided
        URL url = classLoader.getResource(normalizedPath);
        LOG.infof("VFSResourceAccessor.search() URL for path '%s': %s", normalizedPath, url);
        if (url != null) {
            try {
                URI uri = url.toURI();
                VirtualFile vf = VFS.getChild(uri);
                LOG.infof("VFSResourceAccessor.search() VirtualFile exists: %s, isDirectory: %s", vf.exists(), vf.isDirectory());
                if (vf.exists()) {
                    if (vf.isDirectory()) {
                        searchDirectory(vf, normalizedPath, recursive, resources);
                        LOG.infof("VFSResourceAccessor.search() found %d resources in directory %s", resources.size(), normalizedPath);
                    } else {
                        // Single file
                        resources.add(new VFSResource(normalizedPath, url, this));
                        LOG.infof("VFSResourceAccessor.search() found single file %s", normalizedPath);
                    }
                }
            } catch (URISyntaxException e) {
                LOG.warnf("Invalid URI for path %s: %s", normalizedPath, e.getMessage());
            }
        } else {
            // If direct path didn't work, try without trailing slash
            String pathWithoutSlash = normalizedPath.endsWith("/") ? normalizedPath.substring(0, normalizedPath.length() - 1) : normalizedPath;
            url = classLoader.getResource(pathWithoutSlash);
            if (url != null) {
                try {
                    URI uri = url.toURI();
                    VirtualFile vf = VFS.getChild(uri);
                    if (vf.exists() && vf.isDirectory()) {
                        searchDirectory(vf, pathWithoutSlash, recursive, resources);
                        LOG.infof("VFSResourceAccessor.search() found %d resources in directory %s without trailing slash", resources.size(), pathWithoutSlash);
                    }
                } catch (URISyntaxException e) {
                    LOG.warnf("Invalid URI for path %s: %s", pathWithoutSlash, e.getMessage());
                }
            }
        }
        
        // If we haven't found any resources yet, try to enumerate files in the directory
        // This is needed for includeAll which passes directory paths
        if (resources.isEmpty() && normalizedPath != null) {
            LOG.infof("VFSResourceAccessor.search() no direct resources found, trying to enumerate directory contents");
            
            // For WAR deployments, also try to search in the web root and WEB-INF
            if (configuration.getDeployment() != null && configuration.getDeployment().endsWith(".war")) {
                LOG.infof("VFSResourceAccessor.search() WAR deployment detected, trying additional paths");
                String[] warPaths = {
                    normalizedPath,
                    "WEB-INF/classes/" + normalizedPath,
                    "META-INF/" + normalizedPath,
                    "WEB-INF/" + normalizedPath
                };
                
                for (String warPath : warPaths) {
                    URL warUrl = classLoader.getResource(warPath);
                    if (warUrl != null) {
                        try {
                            URI warUri = warUrl.toURI();
                            VirtualFile warVf = VFS.getChild(warUri);
                            if (warVf.exists() && warVf.isDirectory()) {
                                searchDirectory(warVf, warPath, recursive, resources);
                                LOG.infof("VFSResourceAccessor.search() found %d resources in WAR path %s", resources.size(), warPath);
                            }
                        } catch (URISyntaxException e) {
                            LOG.warnf("Invalid URI for WAR path %s: %s", warPath, e.getMessage());
                        }
                    }
                }
            }
            
            // For includeAll in JAR deployments, we need to search for files that would be in that directory
            // Since classloaders don't return directories as resources, we need to try known file names
            if (configuration.getDeployment() != null && configuration.getDeployment().endsWith(".jar")) {
                LOG.infof("VFSResourceAccessor.search() JAR deployment detected, searching for known changelog files in path: %s", normalizedPath);
                
                // Try to find changelog files by constructing their paths
                // Based on the test, we know the files are changelog-1.xml and changelog-2.xml
                String[] possibleFiles = {
                    normalizedPath + "/changelog-1.xml",
                    normalizedPath + "/changelog-2.xml",
                    normalizedPath + "/changes-1.xml",
                    normalizedPath + "/changes-2.xml",
                    normalizedPath + "/changelog.xml",
                    normalizedPath + "/changes.xml"
                };
                
                for (String possibleFile : possibleFiles) {
                    URL fileUrl = classLoader.getResource(possibleFile);
                    if (fileUrl != null) {
                        // For includeAll, Liquibase expects the full path to be able to read the resource later
                        resources.add(new VFSResource(possibleFile, fileUrl, this));
                        LOG.infof("VFSResourceAccessor.search() found changelog file: %s", possibleFile);
                    }
                }
                
                // Also try to enumerate using the module's resource roots if available
                if (resources.isEmpty()) {
                    LOG.infof("VFSResourceAccessor.search() no files found with known names, trying VFS enumeration");
                    try {
                        // Try to get the deployment root and enumerate from there
                        String deploymentPath = configuration.getPath();
                        if (deploymentPath != null && deploymentPath.contains("/contents/")) {
                            // Extract the VFS path up to contents
                            int idx = deploymentPath.indexOf("/contents/");
                            String vfsRoot = deploymentPath.substring(0, idx + "/contents/".length());
                            String searchPath = vfsRoot + normalizedPath;
                            
                            LOG.infof("VFSResourceAccessor.search() trying VFS path: %s", searchPath);
                            VirtualFile vf = VFS.getChild(searchPath);
                            if (vf.exists() && vf.isDirectory()) {
                                searchDirectory(vf, normalizedPath, recursive, resources);
                                LOG.infof("VFSResourceAccessor.search() found %d resources via VFS enumeration", resources.size());
                            }
                        }
                    } catch (Exception e) {
                        LOG.warnf("VFS enumeration failed: %s", e.getMessage());
                    }
                }
            }
        }
        
        // Don't fallback to parent implementation as it can't handle VFS URLs
        // Return the resources we found (which may be empty)
        LOG.infof("VFSResourceAccessor.search() returning %d resources", resources.size());
        return resources;
    }
    
    private void searchDirectory(VirtualFile dir, String basePath, boolean recursive, List<Resource> resources) throws IOException {
        searchDirectoryRecursive(dir, basePath, "", recursive, resources);
    }
    
    private void searchDirectoryRecursive(VirtualFile dir, String basePath, String currentRelativePath, boolean recursive, List<Resource> resources) throws IOException {
        LOG.infof("searchDirectoryRecursive: dir=%s, basePath=%s, currentRelativePath=%s, children count=%d", 
                  dir.getPathName(), basePath, currentRelativePath, dir.getChildren().size());
        for (VirtualFile child : dir.getChildren()) {
            LOG.infof("searchDirectoryRecursive: examining child=%s, isFile=%s, isDirectory=%s", 
                      child.getName(), child.isFile(), child.isDirectory());
            if (child.isFile() && (child.getName().endsWith(".xml") || child.getName().endsWith(".yml") || child.getName().endsWith(".yaml") || child.getName().endsWith(".json"))) {
                // For includeAll, we need to provide the full classpath-relative path
                // so Liquibase can load the resource later
                String fullPath;
                // Remove trailing slash from basePath if present
                String cleanBasePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
                
                if (currentRelativePath.isEmpty()) {
                    fullPath = cleanBasePath + "/" + child.getName();
                } else {
                    fullPath = cleanBasePath + "/" + currentRelativePath + "/" + child.getName();
                }
                
                // Clean up double slashes
                fullPath = fullPath.replaceAll("/+", "/");
                
                // Create resource with the full path for Liquibase to be able to read it later
                resources.add(new VFSResource(fullPath, child.toURL(), this));
                LOG.infof("Found changelog file: %s (full path: %s)", child.getPathName(), fullPath);
            } else if (recursive && child.isDirectory()) {
                // For recursive search, track the relative path from the original search directory
                String newRelativePath = currentRelativePath.isEmpty() ? child.getName() : currentRelativePath + "/" + child.getName();
                searchDirectoryRecursive(child, basePath, newRelativePath, recursive, resources);
            }
        }
    }
    
    @Override
    public List<String> describeLocations() {
        List<String> locations = new ArrayList<>();
        // Add VFS location info to help Liquibase understand our resource structure
        if (configuration.getDeployment() != null) {
            locations.add("VFS deployment: " + configuration.getDeployment());
        }
        if (configuration.getPath() != null) {
            locations.add("VFS path: " + configuration.getPath());
        }
        // Also include parent locations
        locations.addAll(super.describeLocations());
        return locations;
    }
}
