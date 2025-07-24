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
import com.github.jamesnetherton.extension.liquibase.scope.WildFlyScopeManager;
import com.github.jamesnetherton.extension.liquibase.service.ChangeLogConfigurationRegistryService;
import com.github.jamesnetherton.extension.liquibase.service.ExecutionTaskService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jboss.as.connector.subsystems.datasources.DataSourceReferenceFactoryService;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;

/**
 * {@link DeploymentUnitProcessor} which adds a service for changelog execution
 * for the deployment unit.
 */
public class LiquibaseChangeLogExecutionProcessor implements DeploymentUnitProcessor{

    private final Supplier<ChangeLogConfigurationRegistryService> registryServiceSupplier;
    // MIGRATION NOTE: Moved counter for unique service names here or to a shared utility.
    private static final AtomicInteger DEPLOYMENT_EXECUTION_COUNTER = new AtomicInteger();


    public LiquibaseChangeLogExecutionProcessor(Supplier<ChangeLogConfigurationRegistryService> registryServiceSupplier) {
        this.registryServiceSupplier = registryServiceSupplier;
    }

    // MIGRATION NOTE: Helper method to create unique service names for deployment-specific changelogs.
    private static ServiceName createDeploymentChangeLogExecutionServiceName(String changeLogName) {
        String suffix = String.format("%s.deployment.%d", changeLogName, DEPLOYMENT_EXECUTION_COUNTER.incrementAndGet());
        return ServiceName.JBOSS.append("liquibase", "changelog", "execution", suffix);
    }

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();

        List<ChangeLogConfiguration> configurations = deploymentUnit.getAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS);
        if (configurations.isEmpty()) {
            return;
        }

        for (ChangeLogConfiguration configuration : configurations) {
            String dataSourceJndiName = configuration.getDataSource();
            // MIGRATION NOTE: Check against registryService instance provided in constructor
            if (registryServiceSupplier.get().containsDatasource(dataSourceJndiName) && registryServiceSupplier.get().getConfigurationByDataSource(dataSourceJndiName).map(c ->!c.getName().equals(configuration.getName())).orElse(false)) {
                throw new DeploymentUnitProcessingException(String.format(MESSAGE_DUPLICATE_DATASOURCE, configuration.getDataSource()));
            }

            // MIGRATION NOTE: Refactored to use modern ServiceBuilder for changelog execution logic.
            ServiceName serviceName = createDeploymentChangeLogExecutionServiceName(configuration.getName());
            // MIGRATION NOTE: Service type is Void as it performs an action.
            ServiceBuilder<?> builder = phaseContext.getServiceTarget().addService(serviceName);
            
            // MIGRATION NOTE: Injecting DataSource dependency using.requires() and Supplier.
            // Using DataSourceReferenceFactoryService for JNDI lookup capability.
            ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(dataSourceJndiName);
            Supplier<DataSourceReferenceFactoryService> dataSourceService = builder.requires(bindInfo.getBinderServiceName());

            // MIGRATION NOTE: Configuration is effectively final for the lambda.
            // MIGRATION NOTE: Use proper service implementation
            builder.setInstance(ExecutionTaskService.createServiceTask(configuration, dataSourceService));
            // MIGRATION NOTE: No explicit stop action was in ChangeLogExecutionService, cleanup was in finally.
            builder.setInitialMode(ServiceController.Mode.ACTIVE); // Ensure it runs on deployment.
            builder.install();

            registryServiceSupplier.get().addConfiguration(getConfigurationKey(deploymentUnit, configuration), configuration);
        }
    }

    @Override
    public void undeploy(DeploymentUnit deploymentUnit) {
        Boolean activated = deploymentUnit.getAttachment(LiquibaseConstants.LIQUIBASE_SUBSYTEM_ACTIVATED);
        if (activated != null && activated) {
            List<ChangeLogConfiguration> configurations = deploymentUnit.getAttachmentList(LiquibaseConstants.LIQUIBASE_CHANGELOGS);
            if (!configurations.isEmpty()) {
                for (ChangeLogConfiguration configuration : configurations) {
                    registryServiceSupplier.get().removeConfiguration(getConfigurationKey(deploymentUnit, configuration));
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
