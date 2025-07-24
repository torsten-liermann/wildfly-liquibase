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
package com.github.jamesnetherton.extension.liquibase;

import com.github.jamesnetherton.extension.liquibase.deployment.LiquibaseCdiAnnotationProcessor;
import com.github.jamesnetherton.extension.liquibase.deployment.LiquibaseChangeLogExecutionProcessor;
import com.github.jamesnetherton.extension.liquibase.deployment.LiquibaseChangeLogParseProcessor;
import com.github.jamesnetherton.extension.liquibase.deployment.LiquibaseDependenciesProcessor;
import com.github.jamesnetherton.extension.liquibase.deployment.LiquibaseJBossAllParser;
import com.github.jamesnetherton.extension.liquibase.scope.WildFlyScopeManager;
import com.github.jamesnetherton.extension.liquibase.service.ChangeLogConfigurationRegistryService;
import com.github.jamesnetherton.extension.liquibase.service.ChangeLogModelService;
import com.github.jamesnetherton.extension.liquibase.service.SubsystemServices;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import liquibase.Liquibase;
import liquibase.Scope;
import org.jboss.as.controller.AbstractBoottimeAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.server.AbstractDeploymentChainStep;
import org.jboss.as.server.DeploymentProcessorTarget;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.deployment.jbossallxml.JBossAllXmlParserRegisteringProcessor;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;

class LiquibaseSubsystemAdd extends AbstractBoottimeAddStepHandler {

    private static final int STRUCTURE_LIQUIBASE_JBOSS_ALL = Phase.STRUCTURE_PARSE_JBOSS_ALL_XML - 0x01;
    private static final int PARSE_LIQUIBASE_CDI_ANNOTATIONS = Phase.PARSE_COMPOSITE_ANNOTATION_INDEX + 0x01;
    private static final int DEPENDENCIES_LIQUIBASE = Phase.DEPENDENCIES_SINGLETON_DEPLOYMENT + 0x01;
    private static final int INSTALL_LIQUIBASE_CHANGE_LOG = Phase.INSTALL_MDB_DELIVERY_DEPENDENCIES + 0x01;
    private static final int INSTALL_LIQUIBASE_MIGRATION_EXECUTION = INSTALL_LIQUIBASE_CHANGE_LOG + 0x01;

    @Override
    protected void performBoottime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        LiquibaseLogger.ROOT_LOGGER.info("Activating Liquibase Subsystem");

        ServiceTarget serviceTarget = context.getServiceTarget();

        // MIGRATION NOTE: Using modern functional service pattern for WildFly 28
        final ChangeLogConfigurationRegistryService registryServiceInstance = new ChangeLogConfigurationRegistryService();
        ServiceBuilder<?> registryBuilder = serviceTarget.addService(ChangeLogConfigurationRegistryService.SERVICE_NAME);
        Consumer<ChangeLogConfigurationRegistryService> registryConsumer = registryBuilder.provides(ChangeLogConfigurationRegistryService.SERVICE_NAME);
        registryBuilder.setInstance(Service.newInstance(registryConsumer, registryServiceInstance));
        registryBuilder.setInitialMode(ServiceController.Mode.LAZY);
        registryBuilder.install();

        // MIGRATION NOTE: Store registry service instance for access via SubsystemServices
        SubsystemServices.setRegistryService(registryServiceInstance);

        // MIGRATION NOTE: Installing ChangeLogModelService using modern functional pattern
        ServiceName modelUpdateServiceName = ChangeLogModelService.getServiceName();
        ServiceBuilder<?> modelServiceBuilder = serviceTarget.addService(modelUpdateServiceName);
        Consumer<ChangeLogModelService> modelConsumer = modelServiceBuilder.provides(modelUpdateServiceName);
        
        // MIGRATION NOTE: Create ChangeLogModelService with direct reference to registry
        ChangeLogModelService modelServiceInstance = new ChangeLogModelService(() -> registryServiceInstance);
        modelServiceBuilder.setInstance(Service.newInstance(modelConsumer, modelServiceInstance));
        modelServiceBuilder.setInitialMode(ServiceController.Mode.ACTIVE);
        modelServiceBuilder.install();
        
        // MIGRATION NOTE: Store model service instance for access via SubsystemServices
        SubsystemServices.setChangeLogModelService(modelServiceInstance);
        // MIGRATION NOTE: Replaced ServiceHelper.installService with direct ServiceBuilder usage above.

        final ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(Liquibase.class.getClassLoader());
            WildFlyScopeManager scopeManager = new WildFlyScopeManager();
            Scope.setScopeManager(scopeManager);
            
            // In Liquibase 4.33.0, we need to manually create the root scope
            // In older versions, this was done automatically by Liquibase
            Map<String, Object> rootScopeObjects = new HashMap<>();
            // Don't set a ResourceAccessor here - it will be set properly during changelog execution
            // Setting ClassLoaderResourceAccessor here causes VFS URL parsing issues
            
            // Create root scope using reflection since constructor is protected
            try {
                java.lang.reflect.Constructor<Scope> constructor = Scope.class.getDeclaredConstructor(Scope.class, Map.class);
                constructor.setAccessible(true);
                Scope rootScope = constructor.newInstance(null, rootScopeObjects);
                
                // Set it as the current scope in our manager
                scopeManager.setScope(rootScope);
            } catch (Exception e) {
                LiquibaseLogger.ROOT_LOGGER.warn("Failed to initialize root scope: " + e.getMessage());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldTCCL);
        }

        context.addStep(new AbstractDeploymentChainStep() {
            public void execute(DeploymentProcessorTarget processorTarget) {
                // MIGRATION NOTE: DUP registration logic remains structurally similar.
                // Pass the supplier directly - WildFly 28 doesn't allow getValue() during operation phase

                DeploymentUnitProcessor parser = new JBossAllXmlParserRegisteringProcessor<>(LiquibaseJBossAllParser.ROOT_ELEMENT,
                        LiquibaseConstants.LIQUIBASE_CHANGELOG_BUILDERS, new LiquibaseJBossAllParser());
                processorTarget.addDeploymentProcessor(LiquibaseExtension.SUBSYSTEM_NAME, Phase.STRUCTURE, STRUCTURE_LIQUIBASE_JBOSS_ALL, parser);
                processorTarget.addDeploymentProcessor(LiquibaseExtension.SUBSYSTEM_NAME, Phase.PARSE, PARSE_LIQUIBASE_CDI_ANNOTATIONS, new LiquibaseCdiAnnotationProcessor());
                processorTarget.addDeploymentProcessor(LiquibaseExtension.SUBSYSTEM_NAME, Phase.DEPENDENCIES, DEPENDENCIES_LIQUIBASE, new LiquibaseDependenciesProcessor());
                processorTarget.addDeploymentProcessor(LiquibaseExtension.SUBSYSTEM_NAME, Phase.INSTALL, INSTALL_LIQUIBASE_CHANGE_LOG, new LiquibaseChangeLogParseProcessor());
                // MIGRATION NOTE: Pass the service instance directly since we have it available
                processorTarget.addDeploymentProcessor(LiquibaseExtension.SUBSYSTEM_NAME, Phase.INSTALL, INSTALL_LIQUIBASE_MIGRATION_EXECUTION, new LiquibaseChangeLogExecutionProcessor(() -> registryServiceInstance));
            }
        }, OperationContext.Stage.RUNTIME);
    }
}
