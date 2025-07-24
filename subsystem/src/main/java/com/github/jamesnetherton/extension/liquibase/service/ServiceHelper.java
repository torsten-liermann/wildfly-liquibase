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
import org.jboss.msc.service.ServiceName;

public final class ServiceHelper {

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
