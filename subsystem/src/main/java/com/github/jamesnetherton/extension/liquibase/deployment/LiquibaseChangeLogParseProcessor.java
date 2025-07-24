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
package com.github.jamesnetherton.extension.liquibase.deployment;

import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration;
import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration.Builder;
import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration.BuilderCollection;
import com.github.jamesnetherton.extension.liquibase.ChangeLogParserFactory;
import com.github.jamesnetherton.extension.liquibase.LiquibaseConstants;
import com.github.jamesnetherton.extension.liquibase.LiquibaseLogger;
import com.github.jamesnetherton.extension.liquibase.ModelConstants;
import com.github.jamesnetherton.extension.liquibase.resource.VFSResourceAccessor;
import com.github.jamesnetherton.extension.liquibase.resource.WildFlyCompositeResourceAccessor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.ChangeLogParseException;
import liquibase.parser.ChangeLogParser;
import liquibase.resource.FileSystemResourceAccessor;
import org.jboss.as.ee.structure.DeploymentType;
import org.jboss.as.ee.structure.DeploymentTypeMarker;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.web.common.WarMetaData;
import org.jboss.metadata.javaee.spec.ParamValueMetaData;
import org.jboss.metadata.web.spec.WebMetaData;
import org.jboss.modules.Module;
import org.jboss.vfs.VirtualFile;
import org.jboss.vfs.VirtualFileFilter;

/**
 * {@link DeploymentUnitProcessor} which discovers Liquibase change log files within the deployment, reads their contents
 * and adds a {@link ChangeLogConfiguration} to the current deployment unit attachment list.
 */
public class LiquibaseChangeLogParseProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        if (DeploymentTypeMarker.isType(DeploymentType.EAR, deploymentUnit)) {
            return;
        }

        Optional<String> changeLogContextParam = Optional.empty();
        WarMetaData warMetaData = deploymentUnit.getAttachment(WarMetaData.ATTACHMENT_KEY);
        if (warMetaData != null) {
            WebMetaData webMetaData = warMetaData.getWebMetaData();
            if (webMetaData != null && webMetaData.getContextParams() != null) {
                changeLogContextParam = webMetaData.getContextParams()
                    .stream()
                    .filter(paramValueMetaData -> paramValueMetaData.getParamName().equals("liquibase.changelog"))
                    .map(ParamValueMetaData::getParamValue)
                    .findFirst();
            }
        }

        Module module = deploymentUnit.getAttachment(Attachments.MODULE);
        List<VirtualFile> changeLogFiles = new ArrayList<>();

        try {
            if (deploymentUnit.getName().matches(LiquibaseConstants.LIQUIBASE_CHANGELOG_PATTERN)) {
                VirtualFile virtualFile = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_CONTENTS);
                LiquibaseLogger.ROOT_LOGGER.info("Found Liquibase changelog: {}", virtualFile.getName());
                changeLogFiles.add(virtualFile);
            } else {
                String contextParam = changeLogContextParam.orElse("/");
                VirtualFileFilter filter = file -> file.isFile() && file.getName().matches(LiquibaseConstants.LIQUIBASE_CHANGELOG_PATTERN) && !file.getPathName().endsWith(contextParam);
                VirtualFile rootFile = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
                for (VirtualFile virtualFile : rootFile.getChildrenRecursively(filter)) {
                    LiquibaseLogger.ROOT_LOGGER.info("Found Liquibase changelog: {}", virtualFile.getName());
                    changeLogFiles.add(virtualFile);
                }
            }

            BuilderCollection builderCollection = deploymentUnit.getAttachment(LiquibaseConstants.LIQUIBASE_CHANGELOG_BUILDERS);
            
            // Debug logging to understand what's in the builder collection
            if (builderCollection != null) {
                LiquibaseLogger.ROOT_LOGGER.info("BuilderCollection found with {} builders", builderCollection.getBuilders().size());
                for (Builder b : builderCollection.getBuilders()) {
                    try {
                        java.lang.reflect.Field nameField = b.getClass().getDeclaredField("name");
                        nameField.setAccessible(true);
                        String name = (String) nameField.get(b);
                        
                        java.lang.reflect.Field hostExcludesField = b.getClass().getDeclaredField("hostExcludes");
                        hostExcludesField.setAccessible(true);
                        String hostExcludes = (String) hostExcludesField.get(b);
                        
                        LiquibaseLogger.ROOT_LOGGER.info("Builder: name={}, hostExcludes={}", name, hostExcludes);
                    } catch (Exception e) {
                        LiquibaseLogger.ROOT_LOGGER.debug("Failed to inspect builder", e);
                    }
                }
            }

            for (VirtualFile virtualFile : changeLogFiles) {
                File file = virtualFile.getPhysicalFile();
                
                // For standalone XML deployments, we need to handle parsing differently
                boolean isStandaloneXmlDeployment = deploymentUnit.getName().matches(LiquibaseConstants.LIQUIBASE_CHANGELOG_PATTERN) 
                        && deploymentUnit.getName().endsWith(".xml");
                
                String dataSource;
                String changelogName;
                String xmlContent = null;
                
                if (isStandaloneXmlDeployment) {
                    // For standalone XML, read the content and parse datasource from it
                    try (InputStream is = virtualFile.openStream()) {
                        byte[] bytes = is.readAllBytes();
                        xmlContent = new String(bytes, StandardCharsets.UTF_8);
                        LiquibaseLogger.ROOT_LOGGER.info("Read standalone XML deployment content, {} bytes", bytes.length);
                    }
                    
                    // Parse datasource from the XML content directly
                    dataSource = parseDataSourceFromXmlContent(xmlContent, deploymentUnit.getName());
                    // Use .xml extension as Liquibase expects it
                    changelogName = "__standalone_xml_content__.xml";
                } else {
                    // Regular deployments
                    dataSource = parseDataSource(virtualFile, deploymentUnit.getName(), module.getClassLoader());
                    changelogName = file.getName();
                }

                Builder builder;
                if (builderCollection == null) {
                    builder = ChangeLogConfiguration.builder();
                } else {
                    builder = builderCollection.getOrCreateBuilder(file.getName());
                }
                
                // For WAR deployments, calculate and store the physical base path
                String physicalBasePath = null;
                if (deploymentUnit.getName().endsWith(".war")) {
                    try {
                        File physicalFile = virtualFile.getPhysicalFile();
                        if (physicalFile != null && physicalFile.exists()) {
                            physicalBasePath = physicalFile.getParentFile().getAbsolutePath();
                            LiquibaseLogger.ROOT_LOGGER.info("WAR deployment physical base path for auto-discovered file: {}", physicalBasePath);
                        }
                    } catch (Exception e) {
                        LiquibaseLogger.ROOT_LOGGER.debug("Failed to get physical base path for WAR deployment", e);
                    }
                }
                
                ChangeLogConfiguration configuration = builder.name(changelogName)
                    // For standalone XML, don't use the physical path since it won't be parseable
                    .path(isStandaloneXmlDeployment ? "changelog.xml" : virtualFile.getPathName())
                    .physicalBasePath(physicalBasePath)
                    .deployment(deploymentUnit.getName())
                    // For standalone XML, store the content as definition
                    .definition(xmlContent)
                    .dataSource(dataSource)
                    .classLoader(module.getClassLoader())
                    .deploymentOrigin()
                    .build();

                deploymentUnit.addToAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS, configuration);
            }
            
            // Handle jboss-all.xml configured changelogs without auto-discovered files
            if (builderCollection != null && changeLogFiles.isEmpty()) {
                for (Builder builder : builderCollection.getBuilders()) {
                    // The builder has the changelog name set by the JBoss descriptor parser
                    // We need to be careful not to call build() yet as it might fail without datasource
                    try {
                        // Use reflection to get the name field from the builder
                        java.lang.reflect.Field nameField = builder.getClass().getDeclaredField("name");
                        nameField.setAccessible(true);
                        String changelogName = (String) nameField.get(builder);
                        
                        if (changelogName != null) {
                            LiquibaseLogger.ROOT_LOGGER.info("Found Liquibase changelog from jboss-all.xml: {}", changelogName);
                            
                            // The changelog name from jboss-all.xml is the full path within the JAR
                            // We need to extract just the filename for the configuration name
                            String configName = changelogName;
                            if (changelogName.contains("/")) {
                                configName = changelogName.substring(changelogName.lastIndexOf("/") + 1);
                            }
                            
                            // For jboss-all.xml configured changelogs, we need to parse the datasource
                            // from the changelog file itself
                            String dataSource = parseDataSourceFromClasspath(changelogName, deploymentUnit.getName(), module.getClassLoader());
                            
                            // Build configuration with the correct file name
                            // For jboss-all.xml configured changelogs, we use just the filename, not the full path
                            
                            // For WAR deployments, calculate and store the physical base path
                            String physicalBasePath = null;
                            if (deploymentUnit.getName().endsWith(".war")) {
                                try {
                                    VirtualFile root = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
                                    File physicalFile = root.getPhysicalFile();
                                    if (physicalFile != null && physicalFile.exists()) {
                                        physicalBasePath = physicalFile.getAbsolutePath();
                                        LiquibaseLogger.ROOT_LOGGER.info("WAR deployment physical base path: {}", physicalBasePath);
                                    }
                                } catch (Exception e) {
                                    LiquibaseLogger.ROOT_LOGGER.debug("Failed to get physical base path for WAR deployment", e);
                                }
                            }
                            
                            ChangeLogConfiguration configuration = builder
                                .name(configName)  // Use just the filename for the configuration name
                                .path(changelogName)  // But keep the full path for loading
                                .physicalBasePath(physicalBasePath)
                                .deployment(deploymentUnit.getName())
                                .dataSource(dataSource)
                                .classLoader(module.getClassLoader())
                                .deploymentOrigin()
                                .build();

                            deploymentUnit.addToAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS, configuration);
                        }
                    } catch (Exception e) {
                        LiquibaseLogger.ROOT_LOGGER.debug("Failed to process jboss-all.xml builder", e);
                    }
                }
            }

            if (!deploymentUnit.hasAttachment(LiquibaseConstants.LIQUIBASE_SUBSYTEM_ACTIVATED)) {
                boolean activated = !changeLogFiles.isEmpty() || changeLogContextParam.isPresent() || 
                    (builderCollection != null && !builderCollection.getBuilders().isEmpty());
                deploymentUnit.putAttachment(LiquibaseConstants.LIQUIBASE_SUBSYTEM_ACTIVATED, activated);
            }
        } catch (IOException e) {
            throw new DeploymentUnitProcessingException(e);
        }
    }

    @Override
    public void undeploy(DeploymentUnit deploymentUnit) {
    }

    private String parseDataSource(VirtualFile file, String runtimeName, ClassLoader classLoader) throws DeploymentUnitProcessingException {
        ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);

            ChangeLogConfiguration configuration = new ChangeLogConfiguration();
            configuration.setName(file.getName());
            configuration.setPath(file.getPathName());
            configuration.setDeployment(runtimeName);
            configuration.setClassLoader(classLoader);
            
            LiquibaseLogger.ROOT_LOGGER.info("parseDataSource: file.getName()={}, file.getPathName()={}, runtimeName={}", 
                    file.getName(), file.getPathName(), runtimeName);
            
            // Create resource accessors
            VFSResourceAccessor vfsResourceAccessor = new VFSResourceAccessor(configuration);
            
            // Always include FileSystemResourceAccessor for backward compatibility
            // This matches the behavior in version 2.2.0
            File physicalFile = file.getPhysicalFile();
            File[] basePaths = new File[] { physicalFile.getParentFile() };
            LiquibaseLogger.ROOT_LOGGER.info("Creating FileSystemResourceAccessor with basePath: {}", physicalFile.getParentFile().getAbsolutePath());
            FileSystemResourceAccessor fileSystemResourceAccessor = new FileSystemResourceAccessor(basePaths);
            WildFlyCompositeResourceAccessor compositeResourceAccessor = new WildFlyCompositeResourceAccessor(fileSystemResourceAccessor, vfsResourceAccessor);
            
            // Add extra logging to understand what's happening
            LiquibaseLogger.ROOT_LOGGER.info("Created VFSResourceAccessor with configuration: name={}, path={}, deployment={}", 
                    configuration.getName(), configuration.getPath(), configuration.getDeployment());

            ChangeLogParser parser = ChangeLogParserFactory.createParser(file.getName());
            if (parser == null) {
                parser = ChangeLogParserFactory.createParser(runtimeName);
            }

            if (parser == null) {
                throw new DeploymentUnitProcessingException("Unable to find a suitable change log parser for " + file.getName());
            }

            String changeLogLocation;
            DatabaseChangeLog changeLog;
            
            if (runtimeName.endsWith(".war")) {
                // For WAR deployments, we need to use just the filename for the resource accessor to find it
                changeLogLocation = file.getName();
                LiquibaseLogger.ROOT_LOGGER.info("parseDataSource: WAR deployment, using filename: {}, file.getPathName(): {}, physical file path: {}", 
                        changeLogLocation, file.getPathName(), file.getPhysicalFile().getAbsolutePath());
                
                // Log which resource accessor finds the file
                if (compositeResourceAccessor.getAll(changeLogLocation) != null && !compositeResourceAccessor.getAll(changeLogLocation).isEmpty()) {
                    LiquibaseLogger.ROOT_LOGGER.info("Resource found by composite accessor for: {}", changeLogLocation);
                } else {
                    LiquibaseLogger.ROOT_LOGGER.warn("Resource NOT found by composite accessor for: {}", changeLogLocation);
                }
                
                changeLog = parser.parse(changeLogLocation, new ChangeLogParameters(), compositeResourceAccessor);
            } else if (runtimeName.endsWith(".xml")) {
                // For standalone XML deployments, parse directly from the VFS input stream
                try (InputStream is = file.openStream()) {
                    changeLog = parser.parse(runtimeName, new ChangeLogParameters(), compositeResourceAccessor);
                }
            } else {
                changeLogLocation = file.getPhysicalFile().getAbsolutePath();
                changeLog = parser.parse(changeLogLocation, new ChangeLogParameters(), compositeResourceAccessor);
            }
            Object dataSource = changeLog.getChangeLogParameters().getValue(ModelConstants.DATASOURCE, changeLog);
            if (dataSource == null) {
                throw new DeploymentUnitProcessingException("Change log is missing a datasource-ref property");
            }
            return (String) dataSource;
        } catch (ChangeLogParseException | IOException e) {
            throw new DeploymentUnitProcessingException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldTCCL);
        }
    }
    
    private String parseDataSourceFromClasspath(String changelogPath, String runtimeName, ClassLoader classLoader) throws DeploymentUnitProcessingException {
        ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);

            ChangeLogConfiguration configuration = new ChangeLogConfiguration();
            configuration.setName(changelogPath);
            configuration.setPath(changelogPath);
            configuration.setDeployment(runtimeName);
            configuration.setClassLoader(classLoader);
            
            // Use only VFS resource accessor to avoid duplicate file issues
            VFSResourceAccessor vfsResourceAccessor = new VFSResourceAccessor(configuration);
            
            // Create a composite accessor with only the VFS accessor
            WildFlyCompositeResourceAccessor compositeResourceAccessor = new WildFlyCompositeResourceAccessor(vfsResourceAccessor);

            ChangeLogParser parser = ChangeLogParserFactory.createParser(changelogPath);
            if (parser == null) {
                throw new DeploymentUnitProcessingException("Unable to find a suitable change log parser for " + changelogPath);
            }

            DatabaseChangeLog changeLog = parser.parse(changelogPath, new ChangeLogParameters(), compositeResourceAccessor);
            Object dataSource = changeLog.getChangeLogParameters().getValue(ModelConstants.DATASOURCE, changeLog);
            if (dataSource == null) {
                throw new DeploymentUnitProcessingException("Change log is missing a datasource-ref property");
            }
            return (String) dataSource;
        } catch (ChangeLogParseException e) {
            throw new DeploymentUnitProcessingException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldTCCL);
        }
    }
    
    private String parseDataSourceFromXmlContent(String xmlContent, String runtimeName) throws DeploymentUnitProcessingException {
        try {
            // Quick XML parsing to extract datasource property
            // Look for <property name="datasource" value="..."/>
            int startIndex = xmlContent.indexOf("name=\"datasource\"");
            if (startIndex > 0) {
                int valueIndex = xmlContent.indexOf("value=\"", startIndex);
                if (valueIndex > 0) {
                    valueIndex += 7; // length of "value=\""
                    int endIndex = xmlContent.indexOf("\"", valueIndex);
                    if (endIndex > 0) {
                        return xmlContent.substring(valueIndex, endIndex);
                    }
                }
            }
            throw new DeploymentUnitProcessingException("Change log is missing a datasource-ref property");
        } catch (Exception e) {
            throw new DeploymentUnitProcessingException("Failed to parse datasource from standalone XML content", e);
        }
    }
}
