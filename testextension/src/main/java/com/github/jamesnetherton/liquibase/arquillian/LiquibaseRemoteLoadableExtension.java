/*-
 * #%L
 * wildfly-liquibase-testextension
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
package com.github.jamesnetherton.liquibase.arquillian;

import org.jboss.arquillian.container.test.spi.RemoteLoadableExtension;
import org.jboss.arquillian.test.spi.TestEnricher;

/**
 * Remote extension for Liquibase Arquillian integration.
 *
 * Note: Module dependencies (org.liquibase.core, org.liquibase.cdi) are added
 * automatically by {@code LiquibaseDependenciesProcessor} in the subsystem.
 * The deprecated DependenciesProvider interface was removed in WildFly Arquillian 5.x.
 */
public final class LiquibaseRemoteLoadableExtension implements RemoteLoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(TestEnricher.class, ChangeLogDefinitionEnricher.class);
    }
}
