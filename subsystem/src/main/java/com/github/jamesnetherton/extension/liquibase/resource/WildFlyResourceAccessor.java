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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import liquibase.resource.InputStreamList;
import liquibase.resource.Resource;
import org.jboss.logging.Logger;

public final class WildFlyResourceAccessor extends VFSResourceAccessor {

    private static final Logger LOG = Logger.getLogger(WildFlyResourceAccessor.class);
    private static final String LIQUIBASE_ELEMENT_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\" \n" + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n"
        + "xmlns:ext=\"http://www.liquibase.org/xml/ns/dbchangelog-ext\" \n"
        + "xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\n"
        + "http://www.liquibase.org/xml/ns/dbchangelog-ext http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-ext.xsd\">\n";
    private static final String LIQUIBASE_ELEMENT_END = "</databaseChangeLog>";
    private static final String LIQUIBASE_XSD_PATH = "www.liquibase.org/xml/ns/dbchangelog";

    public WildFlyResourceAccessor(ChangeLogConfiguration configuration) {
        super(configuration);
        
        // For standalone XML deployments, pass the content to VFSResourceAccessor
        if (!configuration.isSubsystemOrigin() && configuration.getDefinition() != null 
                && "__standalone_xml_content__.xml".equals(configuration.getFileName())) {
            String definition = configuration.getDefinition();
            LOG.infof("Setting standalone XML content for deployment %s, content length: %d", 
                     configuration.getDeployment(), definition != null ? definition.length() : 0);
            if (definition != null) {
                setStandaloneXmlContent(definition);
            }
        }
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        LOG.debugf("WildFlyResourceAccessor.getAll() called with path=%s, fileName=%s, origin=%s, hasDefinition=%s, deployment=%s", 
                   path, configuration.getFileName(), configuration.getOrigin(), configuration.getDefinition() != null, configuration.getDeployment());
        
        // Special handling for standalone XML deployments
        if ("__standalone_xml_content__.xml".equals(configuration.getFileName()) && configuration.getDefinition() != null) {
            // Handle multiple path variations that Liquibase might use
            if ("changelog.xml".equals(path) || "__standalone_xml_content__.xml".equals(path)) {
                LOG.debugf("Handling standalone XML content request for path: %s", path);
                List<Resource> resources = new ArrayList<>();
                resources.add(new StandaloneXmlResource(path, configuration.getDefinition()));
                return resources;
            }
        }
        
        // First check if this is a request for the inline changelog
        if (configuration.isSubsystemOrigin() && configuration.getDefinition() != null 
                && path.equals(configuration.getFileName())) {
            List<Resource> resources = new ArrayList<>();
            String definition = configuration.getDefinition();
            
            // For XML format, ensure proper Liquibase XML structure
            if (configuration.getFormat().equals(ChangeLogFormat.XML)) {
                if (!definition.contains("http://www.liquibase.org/xml/ns/dbchangelog")) {
                    definition = LIQUIBASE_ELEMENT_START + definition;
                }
                if (!definition.contains(LIQUIBASE_ELEMENT_END)) {
                    definition += LIQUIBASE_ELEMENT_END;
                }
            }
            
            // Create an inline resource for the changelog definition
            resources.add(new InlineChangeLogResource(path, definition));
            return resources;
        }
        
        // For deployment origin (standalone changelog), delegate to parent to load from classpath
        // This allows includeAll and other resource directives to work properly
        
        // Special handling for WAR deployments - if we're looking for a file that matches our fileName
        // and we're in a WAR deployment, it might be in the root
        if (!configuration.isSubsystemOrigin() && configuration.getDeployment() != null 
                && configuration.getDeployment().endsWith(".war") && path.equals(configuration.getFileName())) {
            LOG.infof("WildFlyResourceAccessor handling WAR root resource request for: %s", path);
            
            // For WAR root deployments, check various path variations
            String[] pathVariations = {
                path,
                "/" + path,
                "WEB-INF/classes/" + path
            };
            
            for (String variation : pathVariations) {
                LOG.debugf("Trying path variation: %s", variation);
                List<Resource> resources = super.getAll(variation);
                if (resources != null && !resources.isEmpty()) {
                    LOG.infof("Found WAR root resource with path: %s", variation);
                    return resources;
                }
            }
            
            // If still not found, try using the class loader directly
            try {
                URL resourceUrl = configuration.getClassLoader().getResource(path);
                if (resourceUrl == null) {
                    resourceUrl = configuration.getClassLoader().getResource("/" + path);
                }
                if (resourceUrl == null) {
                    // Try without the .xml extension if it's there
                    String baseName = path.endsWith(".xml") ? path.substring(0, path.length() - 4) : path;
                    resourceUrl = configuration.getClassLoader().getResource(baseName);
                    if (resourceUrl == null) {
                        resourceUrl = configuration.getClassLoader().getResource("/" + baseName);
                    }
                }
                if (resourceUrl != null) {
                    LOG.infof("Found WAR root resource via classloader: %s", resourceUrl);
                    List<Resource> resources = new ArrayList<>();
                    resources.add(new VFSResource(path, resourceUrl, this));
                    return resources;
                }
            } catch (Exception e) {
                LOG.debugf("Failed to load resource via classloader: %s", e.getMessage());
            }
            
            // Last resort - try creating a FileSystemResourceAccessor for the physical file
            // This is needed for WAR root deployments where the file is in the temp VFS directory
            try {
                // First check if we have a stored physical base path from parsing
                String physicalBasePath = configuration.getPhysicalBasePath();
                if (physicalBasePath != null) {
                    LOG.debugf("Using stored physical base path: %s", physicalBasePath);
                    File baseDir = new File(physicalBasePath);
                    if (baseDir.exists()) {
                        File targetFile = new File(baseDir, path);
                        LOG.debugf("Checking for file with stored base path: %s (exists: %s)", targetFile, targetFile.exists());
                        if (targetFile.exists()) {
                            LOG.infof("Found WAR root file using stored physical path: %s", targetFile.getAbsolutePath());
                            liquibase.resource.FileSystemResourceAccessor fsAccessor = new liquibase.resource.FileSystemResourceAccessor(baseDir);
                            List<Resource> resources = fsAccessor.getAll(path);
                            if (resources != null && !resources.isEmpty()) {
                                LOG.infof("Found WAR root resource using stored physical base path: %s", path);
                                return resources;
                            }
                        }
                    }
                }
                
                // Check if the file exists in the deployment's physical location
                String deploymentPath = configuration.getPath();
                LOG.debugf("Attempting FileSystemResourceAccessor fallback for path: %s, deploymentPath: %s", path, deploymentPath);
                
                if (deploymentPath != null && deploymentPath.contains("/content/")) {
                    // Extract the VFS temp directory path
                    File tempDir = new File(System.getProperty("jboss.server.temp.dir", "/tmp"));
                    File vfsDir = new File(tempDir, "vfs");
                    LOG.debugf("Searching in VFS directory: %s (exists: %s)", vfsDir, vfsDir.exists());
                    
                    if (vfsDir.exists()) {
                        // Search for the file in VFS temp directories
                        File[] tempDirs = vfsDir.listFiles(File::isDirectory);
                        if (tempDirs != null) {
                            LOG.debugf("Found %d temp directories in VFS", tempDirs.length);
                            for (File tempSubDir : tempDirs) {
                                File[] contentDirs = tempSubDir.listFiles((dir, name) -> name.startsWith("content-"));
                                if (contentDirs != null) {
                                    LOG.debugf("Found %d content directories in %s", contentDirs.length, tempSubDir);
                                    for (File contentDir : contentDirs) {
                                        File targetFile = new File(contentDir, path);
                                        LOG.debugf("Checking for file: %s (exists: %s)", targetFile, targetFile.exists());
                                        if (targetFile.exists()) {
                                            LOG.infof("Found WAR root file in VFS temp: %s", targetFile.getAbsolutePath());
                                            liquibase.resource.FileSystemResourceAccessor fsAccessor = new liquibase.resource.FileSystemResourceAccessor(contentDir);
                                            List<Resource> resources = fsAccessor.getAll(path);
                                            if (resources != null && !resources.isEmpty()) {
                                                LOG.infof("Found WAR root resource using FileSystemResourceAccessor: %s", path);
                                                return resources;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debugf("Failed to use FileSystemResourceAccessor fallback for WAR root: %s", e.getMessage(), e);
            }
        }
        
        // Otherwise delegate to parent for file-based resources
        return super.getAll(path);
    }

    @Override
    @Deprecated
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

            if (definition != null && configuration.isSubsystemOrigin() && path.equals(configuration.getFileName())) {
                resources.add(file.toURI(), new ByteArrayInputStream(definition.getBytes(StandardCharsets.UTF_8)));
            } else {
                resources = super.openStreams(relativeTo, path);
                if (resources == null || resources.isEmpty()) {
                    // Attempt to work out the 'relative to' change log path
                    String parentPath =  configuration.getPath().replace("/content/" + configuration.getDeployment(), "");
                    parentPath = parentPath.replace(Objects.requireNonNull(configuration.getFileName()), "");
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
}
