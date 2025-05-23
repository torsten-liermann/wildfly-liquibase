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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional; // MIGRATION NOTE: Added for better return type
import org.jboss.msc.service.ServiceName; // MIGRATION NOTE: Added for SERVICE_NAME

public class ChangeLogConfigurationRegistryService {

    // MIGRATION NOTE: Public static ServiceName for consistent registration and lookup.
    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("liquibase", "changelog", "registry");

    private final Map<String, ChangeLogConfiguration> configurationMap = new HashMap<>();

    // MIGRATION NOTE: Default constructor for.setInstance(ChangeLogConfigurationRegistryService::new)
    public ChangeLogConfigurationRegistryService() {
    }

    public void addConfiguration(String runtimeName, ChangeLogConfiguration configuration) {
        synchronized (configurationMap) {
            configurationMap.put(runtimeName, configuration);
        }
    }

    public ChangeLogConfiguration removeConfiguration(String runtimeName) {
        synchronized (configurationMap) {
            return configurationMap.remove(runtimeName);
        }
    }

    public boolean containsDatasource(String dataSource) {
        synchronized (configurationMap) {
            return configurationMap.values()
                .stream()
                .anyMatch((configuration -> configuration.getDataSource().equals(dataSource)));
        }
    }

    // MIGRATION NOTE: Added a method to get a configuration by datasource, useful for duplicate checks.
    public Optional<ChangeLogConfiguration> getConfigurationByDataSource(String dataSource) {
        synchronized (configurationMap) {
            return configurationMap.values()
               .stream()
               .filter(configuration -> configuration.getDataSource().equals(dataSource))
               .findFirst();
        }
    }

    // MIGRATION NOTE: Added a method to get a configuration by name.
    public Optional<ChangeLogConfiguration> getConfigurationByName(String name) {
        synchronized (configurationMap) {
            return Optional.ofNullable(configurationMap.get(name));
        }
}

    // MIGRATION NOTE: Added a method to retrieve all configurations, could be useful for management/inspection.
    public Map<String, ChangeLogConfiguration> getAllConfigurations() {
        synchronized (configurationMap) {
            return new HashMap<>(configurationMap); // Return a copy
        }
    }
}
