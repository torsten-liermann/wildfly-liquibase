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
            // MIGRATION NOTE: getValue() is deprecated - no direct replacement exists.
            // Services must explicitly provide values via Consumer pattern in modern MSC API.
            return (T) controller.getValue();
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
        ServiceName serviceName = ChangeLogModelService.getServiceName();
        return getService(context, serviceName, ChangeLogModelService.class);
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

            if (hostIncludes != null && !hostIncludes.isEmpty()) {
                for (String host : hostIncludes.split(",")) {
                    host = host.trim();
                    if (hostName.equalsIgnoreCase(host)) {
                        return true;
                    }
                }
                // If includes are specified, and we didn't match, then it's not executable unless excludes also match (which is unlikely logic)
                // Typically, if includes are present, only matching hosts execute.
                return false;
            }

            // This part is executed only if hostIncludes is null or empty.
            if (hostExcludes!= null &&!hostExcludes.isEmpty()) {
                for (String host : hostExcludes.split(",")) {
                    host = host.trim();
                    if (hostName.equalsIgnoreCase(host)) {
                        return false; // Excluded
                    }
                }
                return true; // Not in excludes list
            }
        } catch (Throwable e) {
            LiquibaseLogger.ROOT_LOGGER.warn("Unable to process host-excludes or host-includes. Failed looking up hostname", e);
            // Default to not executable in case of error to be safe, or based on specific policy.
            // Original code returned false here implicitly. Let's make it explicit.
        return false;
    }
        // Should only be reached if hostIncludes is empty/null AND hostExcludes is empty/null, which is handled at the top.
        // Or if hostIncludes is not empty but no match, and hostExcludes is empty/null.
        return true; // Default if no specific include/exclude rules applied against this host.
}
}
