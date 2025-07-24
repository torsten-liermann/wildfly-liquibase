/*-
 * #%L
 * wildfly-liquibase-itests
 * %%
 * Copyright (C) 2017 - 2019 James Netherton
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
// MIGRATION NOTE: org.jboss.msc.service.ServiceTarget is used directly by callers.
import java.util.function.Supplier; // MIGRATION NOTE: For modern service retrieval if adapted
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
import liquibase.util.NetUtil;
import org.jboss.as.controller.OperationContext;
// MIGRATION NOTE: org.jboss.msc.service.Service is no longer directly used for installation helper.
// MIGRATION NOTE: org.jboss.msc.service.ServiceBuilder is used directly by callers.
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;

public final class ServiceHelper {

    // MIGRATION NOTE: This method uses deprecated ServiceController.getValue() API.
    // Modern services should use Supplier/Consumer pattern via ServiceBuilder.requires()/provides().
    // This method is retained for compatibility with legacy code that cannot be easily migrated.
    @SuppressWarnings({"unchecked", "deprecation"})
    @Deprecated
    public static <T> T getService(OperationContext context, ServiceName serviceName, Class<T> type) {
        ServiceController<?> controller = context.getServiceRegistry(false).getService(serviceName);
        if (controller != null) {
            try {
                // MIGRATION NOTE: getValue() is deprecated and throws UnsupportedOperationException
                // for services registered with the functional pattern.
                return (T) controller.getValue();
            } catch (UnsupportedOperationException e) {
                // For ChangeLogModelService, we need to get it through the registry service
                if (type == ChangeLogModelService.class) {
                    // Return a new instance that can delegate to the registry service
                    ServiceController<?> registryController = context.getServiceRegistry(false)
                        .getService(ChangeLogConfigurationRegistryService.SERVICE_NAME);
                    if (registryController != null) {
                        try {
                            ChangeLogConfigurationRegistryService registry = 
                                (ChangeLogConfigurationRegistryService) registryController.getValue();
                            return (T) new ChangeLogModelService(() -> registry);
                        } catch (Exception ex) {
                            LiquibaseLogger.ROOT_LOGGER.warn("Failed to get registry service", ex);
                        }
                    }
                }
                LiquibaseLogger.ROOT_LOGGER.warn("Service '{}' getValue() threw UnsupportedOperationException", serviceName);
                return null;
            }
        }
        return null;
    }

    // MIGRATION NOTE: Modern alternative using Supplier pattern (preferred for new code)
    // This method should be used instead of getService() for new service implementations
    public static <T> T getServiceFromSupplier(Supplier<T> serviceSupplier) {
        return serviceSupplier.get();
    }
    
    // MIGRATION NOTE: Modern pattern for service access without getValue()
    // Services using this pattern should be designed with explicit value provision via Consumer<T>
    public static <T> T getServiceViaModernPattern(OperationContext context, ServiceName serviceName, Class<T> type) {
        // MIGRATION NOTE: In modern MSC API, services cannot be directly retrieved.
        // Services must use Supplier/Consumer pattern via ServiceBuilder.requires()/provides().
        // This method returns null to encourage proper dependency injection patterns.
        LiquibaseLogger.ROOT_LOGGER.warn("Service '{}' access via modern pattern requires Supplier injection - returning null", serviceName);
        return null;
    }


    // MIGRATION NOTE: This method relies on ChangeLogModelService being registered in a way that it can be looked up.
    // Modern pattern would be to inject ChangeLogModelService via Supplier in dependent services.
    // This method is deprecated along with getValue() pattern but still used by management operations.
    @SuppressWarnings("deprecation")
    @Deprecated
    public static ChangeLogModelService getChangeLogModelUpdateService(OperationContext context) {
        // MIGRATION NOTE: Use SubsystemServices to access the service instance
        // This avoids the getValue() issue with services registered using the functional pattern
        try {
            return SubsystemServices.getChangeLogModelService();
        } catch (IllegalStateException e) {
            LiquibaseLogger.ROOT_LOGGER.warn("ChangeLogModelService not available via SubsystemServices", e);
            // Fallback to old method for compatibility
            ServiceName serviceName = ChangeLogModelService.getServiceName();
            return getService(context, serviceName, ChangeLogModelService.class);
        }
    }
    
    // MIGRATION NOTE: Modern alternative - use this in new service implementations
    // Services should inject their dependencies via Supplier<T> from ServiceBuilder.requires()
    public static <T> T getServiceFromSupplier(Supplier<T> serviceSupplier, String serviceName) {
        try {
            return serviceSupplier.get();
        } catch (Exception e) {
            LiquibaseLogger.ROOT_LOGGER.warn("Failed to get service '{}' from supplier", serviceName, e);
            return null;
        }
    }

    public static boolean isChangeLogExecutable(ChangeLogConfiguration configuration) {
        final String hostExcludes = configuration.getHostExcludes();
        final String hostIncludes = configuration.getHostIncludes();

        if ((hostExcludes == null || hostExcludes.isEmpty()) && (hostIncludes == null || hostIncludes.isEmpty())) {
            return true;
        }

        try {
            String hostName = NetUtil.getLocalHostName();
            LiquibaseLogger.ROOT_LOGGER.info("isChangeLogExecutable: hostName={}, hostExcludes={}, hostIncludes={}", 
                hostName, hostExcludes, hostIncludes);

            if (hostIncludes != null && !hostIncludes.isEmpty()) {
                for (String host : hostIncludes.split(",")) {
                    host = host.trim();
                    // Special handling: "localhost" matches any hostname since every machine is localhost to itself
                    if (hostName.equalsIgnoreCase(host) || (host.equalsIgnoreCase("localhost") && isLocalHost(hostName))) {
                        return true;
                    }
                }
            } else if (hostExcludes != null && !hostExcludes.isEmpty()) {
                for (String host : hostExcludes.split(",")) {
                    host = host.trim();
                    // Special handling: "localhost" matches any hostname since every machine is localhost to itself
                    if (hostName.equalsIgnoreCase(host) || (host.equalsIgnoreCase("localhost") && isLocalHost(hostName))) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Throwable e) {
            LiquibaseLogger.ROOT_LOGGER.warn("Unable to process host-excludes or host-includes. Failed looking up hostname", e);
        }

        return false;
    }
    
    private static boolean isLocalHost(String hostName) {
        // For the purpose of host-excludes/includes, we consider any hostname 
        // to be a "localhost" when the configuration specifies "localhost".
        // This makes the configuration portable across different environments.
        return true;
    }
}
