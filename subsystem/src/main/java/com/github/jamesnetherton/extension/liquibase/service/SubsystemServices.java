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

/**
 * Holder for subsystem services to work around MSC getValue() deprecation.
 * 
 * MIGRATION NOTE: This class provides a static reference to services that were
 * previously accessed via ServiceController.getValue(). In WildFly 28+, services
 * registered with the functional pattern don't support getValue(), so we store
 * references during subsystem initialization.
 */
public final class SubsystemServices {
    
    private static volatile ChangeLogModelService changeLogModelService;
    private static volatile ChangeLogConfigurationRegistryService registryService;
    
    private SubsystemServices() {
        // Utility class
    }
    
    public static void setChangeLogModelService(ChangeLogModelService service) {
        changeLogModelService = service;
    }
    
    public static ChangeLogModelService getChangeLogModelService() {
        if (changeLogModelService == null) {
            throw new IllegalStateException("ChangeLogModelService not initialized");
        }
        return changeLogModelService;
    }
    
    public static void setRegistryService(ChangeLogConfigurationRegistryService service) {
        registryService = service;
    }
    
    public static ChangeLogConfigurationRegistryService getRegistryService() {
        if (registryService == null) {
            throw new IllegalStateException("ChangeLogConfigurationRegistryService not initialized");
        }
        return registryService;
    }
    
    public static void clear() {
        changeLogModelService = null;
        registryService = null;
    }
}