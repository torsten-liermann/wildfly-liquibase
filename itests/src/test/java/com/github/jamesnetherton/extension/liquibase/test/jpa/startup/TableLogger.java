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
package com.github.jamesnetherton.extension.liquibase.test.jpa.startup;

import com.github.jamesnetherton.extension.liquibase.test.jpa.model.Person;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.logging.Logger;

@Singleton
@Startup
public class TableLogger {
    
    private static final Logger LOG = Logger.getLogger(TableLogger.class.getName());
    
    @PersistenceContext
    private EntityManager em;
    
    @PostConstruct
    public void logTableContent() {
        LOG.info("=== TableLogger startup bean initializing ===");
        
        try {
            // First, check if the ear_test table exists using native query
            Query tableCheck = em.createNativeQuery("SELECT COUNT(*) FROM ear_test");
            Object count = tableCheck.getSingleResult();
            LOG.info("=== Table ear_test exists with " + count + " rows ===");
            
            // Now check table structure
            Query structureQuery = em.createNativeQuery(
                "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_NAME = 'EAR_TEST' ORDER BY ORDINAL_POSITION"
            );
            List<?> columns = structureQuery.getResultList();
            LOG.info("=== Table ear_test has " + columns.size() + " columns ===");
            for (Object col : columns) {
                Object[] row = (Object[]) col;
                LOG.info("Column: " + row[0] + " (" + row[1] + ")");
            }
            
            // Log all persons in the table using JPA
            Query query = em.createQuery("SELECT p FROM Person p");
            List<?> results = query.getResultList();
            
            LOG.info("=== Found " + results.size() + " person(s) migrated by Liquibase ===");
            for (Object result : results) {
                Person person = (Person) result;
                LOG.info("Migrated Person: " + person);
            }
            
            LOG.info("=== TableLogger successfully verified Liquibase ran before JPA ===");
        } catch (Exception e) {
            LOG.severe("=== TableLogger failed: " + e.getMessage() + " ===");
            throw new RuntimeException("Failed to access JPA entities", e);
        }
    }
}