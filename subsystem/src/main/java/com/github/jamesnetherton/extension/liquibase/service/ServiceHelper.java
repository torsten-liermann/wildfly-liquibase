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
import liquibase.util.NetUtil;
import org.jboss.as.controller.OperationContext;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;

public final class ServiceHelper {

    public static void installService(ServiceName serviceName, ServiceTarget serviceTarget, Service<?> service) {
        ServiceBuilder<?> builder = serviceTarget.addService(serviceName, service);
        builder.install();
    }

    @SuppressWarnings("unchecked")
    public static <T> T getService(OperationContext context, ServiceName serviceName, Class<T> clazz) {
        ServiceController<?> controller = context.getServiceRegistry(false).getService(serviceName);
        if (controller == null) {
            throw new IllegalStateException("Service not found: " + serviceName);
        }
        // Use getService() to get the actual Service instance, not getValue()
        // This is needed for Service<Void> implementations like ChangeLogModelService
        Service<?> service = controller.getService();
        if (clazz.isInstance(service)) {
            return clazz.cast(service);
        }
        // Fallback for value-based services
        Object value = controller.getValue();
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        throw new IllegalStateException("Service " + serviceName + " is not of type " + clazz.getName());
    }

    public static ChangeLogModelService getChangeLogModelUpdateService(OperationContext context) {
        ServiceName serviceName = ChangeLogModelService.getServiceName();
        return getService(context, serviceName, ChangeLogModelService.class);
    }

    public static boolean isChangeLogExecutable(ChangeLogConfiguration configuration) {
        final String hostExcludes = configuration.getHostExcludes();
        final String hostIncludes = configuration.getHostIncludes();

        if ((hostExcludes == null || hostExcludes.isEmpty()) && (hostIncludes == null || hostIncludes.isEmpty())) {
            return true;
        }

        String hostName = NetUtil.getLocalHostName();

        if (hostIncludes != null && !hostIncludes.isEmpty()) {
            for (String host : hostIncludes.split(",")) {
                host = host.trim();
                if (hostName.equalsIgnoreCase(host)) {
                    return true;
                }
            }
        } else if (hostExcludes != null && !hostExcludes.isEmpty()) {
            for (String host : hostExcludes.split(",")) {
                host = host.trim();
                if (hostName.equalsIgnoreCase(host)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }
}
