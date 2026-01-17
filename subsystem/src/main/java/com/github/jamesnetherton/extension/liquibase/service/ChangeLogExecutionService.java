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
import com.github.jamesnetherton.extension.liquibase.resource.WildFlyResourceAccessor;
import com.github.jamesnetherton.extension.liquibase.scope.WildFlyScopeManager;
import java.io.File;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * Service which executes a Liquibase change log based on the provided {@link ChangeLogConfiguration}.
 */
public final class ChangeLogExecutionService {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final ChangeLogConfiguration configuration;
    private final Consumer<ChangeLogExecutionService> serviceConsumer;
    private final Supplier<DataSource> dataSourceSupplier;

    public ChangeLogExecutionService(ChangeLogConfiguration configuration,
                                     Consumer<ChangeLogExecutionService> serviceConsumer,
                                     Supplier<DataSource> dataSourceSupplier) {
        this.configuration = configuration;
        this.serviceConsumer = serviceConsumer;
        this.dataSourceSupplier = dataSourceSupplier;
    }

    public void start(StartContext context) throws StartException {
        executeChangeLog(configuration);
        serviceConsumer.accept(this);
    }

    public void stop(StopContext context) {
        serviceConsumer.accept(null);
    }

    public void executeChangeLog(ChangeLogConfiguration configuration) {
        if (!ServiceHelper.isChangeLogExecutable(configuration)) {
            LiquibaseLogger.ROOT_LOGGER.info("Not executing changelog {} as host-excludes or host-includes rules did not apply to this server host", configuration.getFileName());
            return;
        }

        JdbcConnection connection = null;
        Liquibase liquibase = null;

        // Set TCCL BEFORE any Liquibase classes are loaded to ensure ServiceLoader finds log services
        final ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(configuration.getClassLoader());
        try {
            // Determine if we need FileSystemResourceAccessor for WEB-INF files
            // WEB-INF files (not in classes or lib/*.jar) are not on classpath
            boolean needsFileSystemAccessor = configuration.getBasePath() != null
                && configuration.getPath() != null
                && configuration.getPath().contains("/WEB-INF/")
                && !configuration.getPath().contains("/WEB-INF/classes/")
                && !configuration.getPath().contains(".jar/");

            ResourceAccessor resourceAccessor;
            if (needsFileSystemAccessor) {
                File[] basePaths = new File[] { new File(configuration.getBasePath()) };
                resourceAccessor = new CompositeResourceAccessor(new FileSystemResourceAccessor(basePaths), new WildFlyResourceAccessor(configuration));
            } else {
                resourceAccessor = new WildFlyResourceAccessor(configuration);
            }

            DataSource datasource = dataSourceSupplier.get();
            if (datasource == null) {
                throw new IllegalStateException("DataSource is not available for changelog: " + configuration.getFileName());
            }
            connection = new JdbcConnection(datasource.getConnection());

            Contexts contexts = new Contexts(configuration.getContexts());
            LabelExpression labelExpression = new LabelExpression(configuration.getLabels());

            // Use appropriate path for changelog lookup:
            // - For subsystem origin or standalone deployments: use fileName (has correct extension)
            // - For WAR/JAR deployments: use classpath path for proper relative include resolution
            String changeLogPath;
            if (configuration.isSubsystemOrigin()) {
                // Subsystem changelogs have their definition in memory, use fileName
                changeLogPath = configuration.getFileName();
            } else if (needsFileSystemAccessor) {
                // WEB-INF files (not on classpath) use fileName with FileSystemResourceAccessor
                changeLogPath = configuration.getFileName();
            } else if (configuration.getPath() != null && configuration.getPath().contains("/data/content/")) {
                // Standalone changelog deployments stored in content repository
                // Use fileName which has the correct extension
                changeLogPath = configuration.getFileName();
            } else {
                // Standard classpath deployments: use classpath path for proper relative includes
                changeLogPath = configuration.getClasspathPath();
            }
            LiquibaseLogger.ROOT_LOGGER.info(String.format("Starting execution of %s changelog %s (path: %s)", configuration.getOrigin(), configuration.getFileName(), changeLogPath));
            liquibase = new Liquibase(changeLogPath, resourceAccessor, connection);
            liquibase.update(contexts, labelExpression);
        } catch (LiquibaseException | SQLException e) {
            if (configuration.isFailOnError()) {
                throw new IllegalStateException(e);
            } else {
                LiquibaseLogger.ROOT_LOGGER.warn("Liquibase changelog execution failed:", e);
                LiquibaseLogger.ROOT_LOGGER.warn("Continuing deployment after changelog execution failure of {} as fail-on-error is false", configuration.getDeployment());
            }
        } finally {
            if (liquibase != null && liquibase.getDatabase() != null) {
                try {
                    LiquibaseLogger.ROOT_LOGGER.info("Closing Liquibase database");
                    liquibase.getDatabase().close();
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
