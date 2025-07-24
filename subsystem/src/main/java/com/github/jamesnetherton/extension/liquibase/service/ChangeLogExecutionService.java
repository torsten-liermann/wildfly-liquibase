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
package com.github.jamesnetherton.extension.liquibase.service;

import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration;
import com.github.jamesnetherton.extension.liquibase.LiquibaseLogger;
import com.github.jamesnetherton.extension.liquibase.resource.WildFlyCompositeResourceAccessor;
import com.github.jamesnetherton.extension.liquibase.resource.WildFlyResourceAccessor;
import com.github.jamesnetherton.extension.liquibase.scope.WildFlyScopeManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ResourceAccessor;
import org.jboss.msc.service.ServiceName;

/**
 * Utility class for Liquibase change log execution.
 * MIGRATION NOTE: Completely removed Service interface - now uses lambda-based service registration
 */
public final class ChangeLogExecutionService {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    // MIGRATION NOTE: Private constructor - this is now a utility class
    private ChangeLogExecutionService() {
        // Utility class
    }

    // MIGRATION NOTE: Keep static method for direct usage and instance method for service
    public static void executeChangeLog(ChangeLogConfiguration configuration) {
        // Debug log to see what configuration we receive
        LiquibaseLogger.ROOT_LOGGER.info("executeChangeLog called for {} with hostExcludes={}, hostIncludes={}", 
            configuration.getFileName(), configuration.getHostExcludes(), configuration.getHostIncludes());
            
        if (!ServiceHelper.isChangeLogExecutable(configuration)) {
            LiquibaseLogger.ROOT_LOGGER.info("Not executing changelog {} as host-excludes or host-includes rules did not apply to this server host", configuration.getFileName());
            return;
        }

        JdbcConnection connection = null;
        final Liquibase[] liquibaseHolder = new Liquibase[1];

        final ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
        try {
            // Build resource accessor chain - order is important!
            // Use only WildFlyResourceAccessor (which extends VFSResourceAccessor) to match deployment parsing
            // This avoids VFS URL parsing issues with includeAll
            ResourceAccessor resourceAccessor = new WildFlyCompositeResourceAccessor(
                new WildFlyResourceAccessor(configuration)
            );

            InitialContext initialContext = new InitialContext();
            DataSource datasource = (DataSource) initialContext.lookup(configuration.getDataSource());
            connection = new JdbcConnection(datasource.getConnection());

            Contexts contexts = new Contexts(configuration.getContexts());
            LabelExpression labelExpression = new LabelExpression(configuration.getLabels());

            LiquibaseLogger.ROOT_LOGGER.info("Starting execution of {} changelog {}", configuration.getOrigin(), configuration.getFileName());
            Thread.currentThread().setContextClassLoader(configuration.getClassLoader());
            
            // Debug resource resolution
            LiquibaseLogger.ROOT_LOGGER.info("Executing changelog with fileName={}, path={}, deployment={}, origin={}", 
                configuration.getFileName(), configuration.getPath(), configuration.getDeployment(), configuration.getOrigin());
            
            // For deployment origin changelogs, determine the correct path
            String changeLogPath;
            if (!configuration.isSubsystemOrigin() && configuration.getPath() != null 
                    && configuration.getPath().contains("/content/") && configuration.getPath().contains(".jar/")) {
                // For VFS paths from jboss-all.xml, extract the classpath resource path
                // Example: /content/liquibase-config-include-test.jar/com/github/jamesnetherton/liquibase/include-changelog.xml
                // becomes: com/github/jamesnetherton/liquibase/include-changelog.xml
                int idx = configuration.getPath().indexOf(".jar/");
                changeLogPath = configuration.getPath().substring(idx + 5);
                LiquibaseLogger.ROOT_LOGGER.debug("Extracted classpath from VFS path: {} -> {}", configuration.getPath(), changeLogPath);
            } else if (!configuration.isSubsystemOrigin() && configuration.getPath() != null 
                    && configuration.getPath().contains("/") && !configuration.getPath().startsWith("/content/")) {
                // For jboss-all.xml configured changelogs with direct classpath paths
                changeLogPath = configuration.getPath();
            } else if ("__standalone_xml_content__.xml".equals(configuration.getFileName())) {
                // For standalone XML deployments, use the full name with extension
                changeLogPath = configuration.getFileName();
                LiquibaseLogger.ROOT_LOGGER.debug("Using synthetic path for standalone XML deployment: {}", changeLogPath);
            } else {
                // For other changelogs, use the filename
                changeLogPath = configuration.getFileName();
            }
            
            // Enable debug logging for Liquibase
            java.util.logging.Logger.getLogger("liquibase").setLevel(java.util.logging.Level.FINE);
            
            liquibaseHolder[0] = new Liquibase(changeLogPath, resourceAccessor, connection);
            
            // Force changelog parsing to ensure includeAll directives are processed
            liquibaseHolder[0].getDatabaseChangeLog();
            
            // Debug: Log the database changelog to see what changesets are registered
            LiquibaseLogger.ROOT_LOGGER.info("About to execute update for changelog: {}", changeLogPath);
            LiquibaseLogger.ROOT_LOGGER.info("Database changelog has {} changesets after parsing", 
                liquibaseHolder[0].getDatabaseChangeLog().getChangeSets().size());
            
            // Log individual changesets for debugging
            if (liquibaseHolder[0].getDatabaseChangeLog().getChangeSets().isEmpty()) {
                LiquibaseLogger.ROOT_LOGGER.warn("No changesets found in changelog {}. This may indicate an issue with includeAll processing.", changeLogPath);
            } else {
                for (liquibase.changelog.ChangeSet cs : liquibaseHolder[0].getDatabaseChangeLog().getChangeSets()) {
                    LiquibaseLogger.ROOT_LOGGER.debug("Found changeset: {}", cs.getId());
                }
            }
            
            liquibaseHolder[0].update(contexts, labelExpression);
        } catch (NamingException | LiquibaseException | SQLException e) {
            if (configuration.isFailOnError()) {
                throw new IllegalStateException(e);
            } else {
                LiquibaseLogger.ROOT_LOGGER.warn("Liquibase changelog execution failed:", e);
                LiquibaseLogger.ROOT_LOGGER.warn("Continuing deployment after changelog execution failure of {} as fail-on-error is false", configuration.getDeployment());
            }
        } catch (Exception e) {
            // Catch any other unexpected exceptions
            if (configuration.isFailOnError()) {
                throw new IllegalStateException("Unexpected error during changelog execution", e);
            } else {
                LiquibaseLogger.ROOT_LOGGER.warn("Unexpected error during changelog execution:", e);
                LiquibaseLogger.ROOT_LOGGER.warn("Continuing deployment after changelog execution failure of {} as fail-on-error is false", configuration.getDeployment());
            }
        } finally {
            if (liquibaseHolder[0] != null && liquibaseHolder[0].getDatabase() != null) {
                try {
                    LiquibaseLogger.ROOT_LOGGER.info("Closing Liquibase database");
                    liquibaseHolder[0].getDatabase().close();
                } catch (DatabaseException e) {
                    LiquibaseLogger.ROOT_LOGGER.warn("Failed to close Liquibase database", e);
                }
            } else if (connection != null) {
                try {
                    LiquibaseLogger.ROOT_LOGGER.info("Closing database connection");
                    connection.close();
                } catch (DatabaseException e) {
                    LiquibaseLogger.ROOT_LOGGER.warn("Failed to close database connection", e);
                }
            }

            WildFlyScopeManager.removeCurrentScope();

            Thread.currentThread().setContextClassLoader(oldTCCL);
        }
    }

    public static ServiceName createServiceName(String changeLogName) {
        String suffix = String.format("%s.%d", changeLogName, COUNTER.incrementAndGet());
        return ServiceName.JBOSS.append("liquibase", "changelog", "execution", suffix);
    }
}
