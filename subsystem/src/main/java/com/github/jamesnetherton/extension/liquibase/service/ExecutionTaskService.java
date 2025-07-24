/*-
 * #%L
 * wildfly-liquibase-subsystem
 * %%
 * Copyright (C) 2017 - 2025 James Netherton
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
import java.util.function.Supplier;
import org.jboss.as.connector.subsystems.datasources.DataSourceReferenceFactoryService;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * Service implementation for executing Liquibase change logs in WildFly 28.
 * This class provides a modern MSC Service implementation using the lambda pattern.
 * 
 * MIGRATION NOTE: This replaces the old AbstractService-based ChangeLogExecutionService
 * with a modern lambda-compatible service for WildFly 28's MSC API.
 */
public final class ExecutionTaskService {

    /**
     * Creates a service task for executing a Liquibase change log.
     * This uses the modern lambda-based service pattern required by WildFly 28.
     * 
     * @param configuration The change log configuration to execute
     * @return A Service instance that executes the change log on start
     */
    public static Service createServiceTask(ChangeLogConfiguration configuration) {
        return new Service() {
            @Override
            public void start(StartContext context) throws StartException {
                // Execute the change log using the static utility method
                ChangeLogExecutionService.executeChangeLog(configuration);
            }

            @Override
            public void stop(StopContext context) {
                // No cleanup needed for change log execution
            }
        };
    }

    /**
     * Creates a service task for executing a Liquibase change log with datasource dependency.
     * This uses the modern lambda-based service pattern required by WildFly 28.
     * 
     * @param configuration The change log configuration to execute
     * @param dataSourceService The datasource service supplier (dependency injection)
     * @return A Service instance that executes the change log on start
     */
    public static Service createServiceTask(ChangeLogConfiguration configuration, 
                                          Supplier<DataSourceReferenceFactoryService> dataSourceService) {
        return new Service() {
            @Override
            public void start(StartContext context) throws StartException {
                // The datasource service is injected but not directly used here
                // as the ChangeLogExecutionService handles datasource lookup via JNDI
                // This dependency ensures the datasource is available before execution
                ChangeLogExecutionService.executeChangeLog(configuration);
            }

            @Override
            public void stop(StopContext context) {
                // No cleanup needed for change log execution
            }
        };
    }

    /**
     * Creates a service name for a change log execution service.
     * 
     * @param deploymentUnit The deployment unit name
     * @param changeLogName The change log file name
     * @return The service name for the execution service
     */
    public static ServiceName createServiceName(String deploymentUnit, String changeLogName) {
        return ServiceName.JBOSS.append("liquibase", "changelog", deploymentUnit, changeLogName, "execution");
    }

    // Private constructor to prevent instantiation
    private ExecutionTaskService() {
        // Utility class
    }
}
