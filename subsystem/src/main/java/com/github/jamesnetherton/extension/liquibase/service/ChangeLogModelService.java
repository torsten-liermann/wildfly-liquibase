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

//... all imports...
import static com.github.jamesnetherton.extension.liquibase.LiquibaseLogger.MESSAGE_DUPLICATE_DATASOURCE;

import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration;
import com.github.jamesnetherton.extension.liquibase.ChangeLogFormat;
import com.github.jamesnetherton.extension.liquibase.ChangeLogResource;
import com.github.jamesnetherton.extension.liquibase.ModelConstants;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import liquibase.Liquibase;
import org.jboss.as.connector.subsystems.datasources.DataSourceReferenceFactoryService; // MIGRATION NOTE: WildFly 28+ uses DataSourceReferenceFactoryService
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;

/**
 * Service which handles updates to the Liquibase subsystem DMR model.
 * // MIGRATION NOTE: Removed 'extends AbstractService<ChangeLogModelService>'
 */
public class ChangeLogModelService {

    // MIGRATION NOTE: ServiceName for this service, to be used for registration and lookup.
    private static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("liquibase", "changelog", "model", "update");
    private static final AtomicInteger EXECUTION_COUNTER = new AtomicInteger(); // For unique execution service names

    private final Supplier<ChangeLogConfigurationRegistryService> registryService;

    // MIGRATION NOTE: Constructor now accepts a Supplier for the registry service.
    public ChangeLogModelService(Supplier<ChangeLogConfigurationRegistryService> registryService) {
        this.registryService = registryService;
    }

    // MIGRATION NOTE: Removed getValue() method as it's no longer idiomatic for this service type.
    // The service instance is obtained via supplier if needed by other modern services, or via registry for legacy access.

    public void createChangeLogModel(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        String changeLogName = operation.get(ModelDescriptionConstants.OP_ADDR).asObject().get(ModelConstants.DATABASE_CHANGELOG).asString();
        String changeLogDefinition = ChangeLogResource.VALUE.resolveModelAttribute(context, model).asStringOrNull();
        String contextsStr = ChangeLogResource.CONTEXTS.resolveModelAttribute(context, model).asString("");
        String dataSourceJndiName = ChangeLogResource.DATASOURCE.resolveModelAttribute(context, model).asString();
        boolean failOnError = ChangeLogResource.FAIL_ON_ERROR.resolveModelAttribute(context, model).asBoolean(true);
        String hostExcludes = ChangeLogResource.HOST_EXCLUDES.resolveModelAttribute(context, model).asString("");
        String hostIncludes = ChangeLogResource.HOST_INCLUDES.resolveModelAttribute(context, model).asString("");
        String labelsStr = ChangeLogResource.LABELS.resolveModelAttribute(context, model).asString("");

        ChangeLogConfiguration configuration = ChangeLogConfiguration.builder()
           .contexts(contextsStr)
            .classLoader(Liquibase.class.getClassLoader()) // MIGRATION NOTE: Consider if a deployment specific CL might be needed here in some contexts
           .dataSource(dataSourceJndiName)
            .definition(changeLogDefinition)
            .failOnError(failOnError)
            .hostExcludes(hostExcludes)
            .hostIncludes(hostIncludes)
           .labels(labelsStr)
            .name(changeLogName)
            .subsystemOrigin()
            .build();

        // Only validate format for inline changelogs
        if (changeLogDefinition != null && configuration.getFormat().equals(ChangeLogFormat.UNKNOWN)) {
            throw new OperationFailedException("Unable to determine change log format for inline changelog. Supported formats are JSON, SQL, YAML and XML");
        }

        ServiceTarget serviceTarget = context.getServiceTarget();
        // MIGRATION NOTE: Using a more descriptive name generation, potentially from a shared utility.
        ServiceName executionServiceName = createChangeLogExecutionServiceName(configuration.getName());

        installChangeLogExecutionService(serviceTarget, executionServiceName, configuration);
    }

    public void updateChangeLogModel(OperationContext context, ModelNode operation) throws OperationFailedException {
        String changeLogName = operation.get(ModelDescriptionConstants.OP_ADDR).asObject().get(ModelConstants.DATABASE_CHANGELOG).asString();
        String attributeName = operation.get(ModelDescriptionConstants.NAME).asString(); // MIGRATION NOTE: Get attribute name for specific update
        ModelNode valueNode = operation.get(ModelDescriptionConstants.VALUE); // MIGRATION NOTE: Get value ModelNode

        // MIGRATION NOTE: Accessing registry service via supplier.
        ChangeLogConfiguration configuration = registryService.get().getConfigurationByName(changeLogName)
           .orElseThrow(() -> new OperationFailedException("Unable to update change log model. Existing configuration for '" + changeLogName + "' not found."));


        String oldDataSource = configuration.getDataSource();
        String value = valueNode.isDefined()? valueNode.asString() : null;


        switch (attributeName) {
            case ModelConstants.CONTEXTS:
                configuration.setContexts(value);
                break;
            case ModelConstants.DATASOURCE:
                 if (value == null || value.trim().isEmpty()) {
                    throw new OperationFailedException(ModelConstants.DATASOURCE + " cannot be empty.");
                }
                configuration.setDataSource(value);
                break;
            case ModelConstants.FAIL_ON_ERROR:
                configuration.setFailOnError(valueNode.asBoolean());
                break;
            case ModelConstants.HOST_EXCLUDES:
                configuration.setHostExcludes(value);
                break;
            case ModelConstants.HOST_INCLUDES:
                configuration.setHostIncludes(value);
                break;
            case ModelConstants.LABELS:
                configuration.setLabels(value);
                break;
            case ModelConstants.VALUE:
                 if (value == null || value.trim().isEmpty()) {
                    throw new OperationFailedException(ModelConstants.VALUE + " (changelog definition) cannot be empty.");
                }
                configuration.setDefinition(value);
                break;
            default:
                 throw new OperationFailedException("Unknown attribute to update: " + attributeName);
        }

        // Only validate format for inline changelogs
        if (configuration.getDefinition() != null && configuration.getFormat().equals(ChangeLogFormat.UNKNOWN)) {
            throw new OperationFailedException("Unable to determine change log format after update. Supported formats are JSON, SQL, YAML and XML");
        }

        String newDataSourceJndiName = configuration.getDataSource();
        if (!oldDataSource.equals(newDataSourceJndiName)) {
             throw new OperationFailedException("Modifying the change log datasource property ('" + oldDataSource + "' to '" + newDataSourceJndiName +"') is not supported directly. Please remove and re-add the changelog with the new datasource.");
        }

        // MIGRATION NOTE: Remove old service and install new one with updated configuration
        ServiceName executionServiceName = createChangeLogExecutionServiceName(changeLogName);
        ServiceTarget serviceTarget = context.getServiceTarget();

        // MIGRATION NOTE: Before removing the service, remove the configuration from the registry to avoid issues if re-installation fails.
        // Re-add it if installation is successful.
        registryService.get().removeConfiguration(changeLogName);
        context.removeService(executionServiceName);

        installChangeLogExecutionService(serviceTarget, executionServiceName, configuration);
    }

    public void removeChangeLogModel(OperationContext context, ModelNode model) throws OperationFailedException {
        String changeLogName = context.getCurrentAddressValue();
        ServiceName executionServiceName = createChangeLogExecutionServiceName(changeLogName);

        context.removeService(executionServiceName);
        registryService.get().removeConfiguration(changeLogName);
    }

    public static ServiceName getServiceName() {
        return SERVICE_NAME;
    }

    private static ServiceName createChangeLogExecutionServiceName(String changeLogName) {
        String suffix = String.format("%s.%d", changeLogName, EXECUTION_COUNTER.incrementAndGet());
        return ServiceName.JBOSS.append("liquibase", "changelog", "execution", "subsystem", suffix); // Added "subsystem" for clarity
    }

    private void installChangeLogExecutionService(ServiceTarget serviceTarget, ServiceName executionServiceName, ChangeLogConfiguration configuration) throws OperationFailedException {
        ChangeLogConfigurationRegistryService registry = registryService.get();
        Optional<ChangeLogConfiguration> existingConfigForDS = registry.getConfigurationByDataSource(configuration.getDataSource());
        if (existingConfigForDS.isPresent() &&!existingConfigForDS.get().getName().equals(configuration.getName())) {
            throw new OperationFailedException(String.format(MESSAGE_DUPLICATE_DATASOURCE, configuration.getDataSource()));
        }

        ServiceBuilder<?> builder = serviceTarget.addService(executionServiceName);

        ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(configuration.getDataSource());
        Supplier<DataSourceReferenceFactoryService> dsFactorySupplier = builder.requires(bindInfo.getBinderServiceName());

        builder.setInstance(ExecutionTaskService.createServiceTask(configuration, dsFactorySupplier));
        // MIGRATION NOTE: setOnStop can be added here if specific cleanup is needed when the service is explicitly stopped by MSC,
        // beyond what the lambda's finally block does. In this case, the finally block is sufficient.
        builder.setInitialMode(ServiceController.Mode.ACTIVE);
        builder.install();

        registry.addConfiguration(configuration.getName(), configuration);
    }
}
