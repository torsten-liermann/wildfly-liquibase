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

import static com.github.jamesnetherton.extension.liquibase.LiquibaseLogger.MESSAGE_DUPLICATE_DATASOURCE;

import com.github.jamesnetherton.extension.liquibase.ChangeLogConfiguration;
import com.github.jamesnetherton.extension.liquibase.LiquibaseConstants;
import com.github.jamesnetherton.extension.liquibase.LiquibaseLogger;
import com.github.jamesnetherton.extension.liquibase.scope.WildFlyScopeManager;
import com.github.jamesnetherton.extension.liquibase.service.ChangeLogConfigurationRegistryService;
import com.github.jamesnetherton.extension.liquibase.service.ChangeLogExecutionService;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.modules.Module;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * {@link DeploymentUnitProcessor} which adds a {@link ChangeLogExecutionService} service dependency for
 * the deployment unit.
 */
public class LiquibaseChangeLogExecutionProcessor implements DeploymentUnitProcessor {

    private final ChangeLogConfigurationRegistryService registryService;

    public LiquibaseChangeLogExecutionProcessor(ChangeLogConfigurationRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();

        List<ChangeLogConfiguration> configurations = deploymentUnit.getAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS);
        if (configurations.isEmpty()) {
            return;
        }

        for (ChangeLogConfiguration configuration : configurations) {
            String dataSource = configuration.getDataSource();

            if (registryService.containsDatasource(dataSource)) {
                throw new DeploymentUnitProcessingException(String.format(MESSAGE_DUPLICATE_DATASOURCE, configuration.getDataSource()));
            }

            ServiceName serviceName = ChangeLogExecutionService.createServiceName(configuration.getName());

            // Get the datasource service name using bind info
            ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(dataSource);
            ServiceName dataSourceServiceName = bindInfo.getBinderServiceName();

            LiquibaseLogger.ROOT_LOGGER.info("Using datasource service name: {} for JNDI name: {}",
                    dataSourceServiceName, dataSource);

            // Create wrapper service for WildFly 35 compatibility
            final ChangeLogExecutionService[] serviceHolder = new ChangeLogExecutionService[1];
            Service<Void> wrapperService = new Service<Void>() {
                @Override
                public void start(StartContext context) throws StartException {
                    serviceHolder[0].start(context);
                }

                @Override
                public void stop(StopContext context) {
                    if (serviceHolder[0] != null) {
                        serviceHolder[0].stop(context);
                    }
                }

                @Override
                public Void getValue() throws IllegalStateException, IllegalArgumentException {
                    return null;
                }
            };

            // Build the service
            ServiceBuilder<?> builder = phaseContext.getServiceTarget().addService(serviceName, wrapperService);

            // Create suppliers and consumers
            Consumer<ChangeLogExecutionService> serviceConsumer = service -> {
                serviceHolder[0] = service;
            };

            // Add a dependency on the datasource's reference factory service
            Supplier<ManagedReferenceFactory> dataSourceRefSupplier = builder.requires(dataSourceServiceName);

            // Create a wrapper supplier that extracts the DataSource from the reference
            Supplier<DataSource> dataSourceSupplier = () -> {
                try {
                    ManagedReferenceFactory factory = dataSourceRefSupplier.get();
                    if (factory != null) {
                        Object reference = factory.getReference().getInstance();
                        if (reference instanceof DataSource) {
                            return (DataSource) reference;
                        }
                    }
                    throw new RuntimeException("Failed to obtain DataSource from reference factory");
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get DataSource reference", e);
                }
            };

            // Create the service with the datasource supplier
            ChangeLogExecutionService service = new ChangeLogExecutionService(configuration, serviceConsumer, dataSourceSupplier);

            // Set initial reference
            serviceHolder[0] = service;

            // Install the service
            builder.install();

            registryService.addConfiguration(getConfigurationKey(deploymentUnit, configuration), configuration);
        }
    }

    @Override
    public void undeploy(DeploymentUnit deploymentUnit) {
        Boolean activated = deploymentUnit.getAttachment(LiquibaseConstants.LIQUIBASE_SUBSYTEM_ACTIVATED);
        if (activated != null && activated) {
            List<ChangeLogConfiguration> configurations = deploymentUnit.getAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS);
            if (!configurations.isEmpty()) {
                for (ChangeLogConfiguration configuration : configurations) {
                    registryService.removeConfiguration(getConfigurationKey(deploymentUnit, configuration));
                }
            }

            Module module = deploymentUnit.getAttachment(Attachments.MODULE);
            WildFlyScopeManager.removeCurrentScope(module.getClassLoader());
        }
    }

    private String getConfigurationKey(DeploymentUnit deploymentUnit, ChangeLogConfiguration configuration) {
        return String.format("%s.%s", configuration.getName(), deploymentUnit.getName());
    }
}
