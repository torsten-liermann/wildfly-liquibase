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
package com.github.jamesnetherton.extension.liquibase.test.jpa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.jamesnetherton.extension.liquibase.test.jpa.model.JpaOrderPerson;
import com.github.jamesnetherton.extension.liquibase.test.jpa.startup.JpaOrderTableLogger;
import com.github.jamesnetherton.liquibase.arquillian.LiquibaseTestSupport;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.UserTransaction;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class LiquibaseJPAOrderingTest extends LiquibaseTestSupport {

    @PersistenceContext
    EntityManager em;
    
    @Resource
    UserTransaction userTransaction;

    @Deployment
    public static Archive<?> deployment() {
        return ShrinkWrap.create(JavaArchive.class, "liquibase-jpa-ordering-test.jar")
            .addClasses(JpaOrderPerson.class, JpaOrderTableLogger.class)
            .addAsResource("jpa/ordering/persistence.xml", "META-INF/persistence.xml")
            .addAsResource("configs/jpa-ordering/changelog.xml", "changelog.xml")
            .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Test
    public void testLiquibaseRunsBeforeJPAValidation() throws Exception {
        // If we get here, it means:
        // 1. Liquibase created the table
        // 2. Liquibase migrated data into the table
        // 3. JPA can read the migrated data
        // 4. The startup bean logged the table content
        
        // Verify the table was created by Liquibase
        assertTableModified("jpa_order_test");
        
        // Read the data that Liquibase migrated
        JpaOrderPerson john = em.find(JpaOrderPerson.class, 1);
        assertNotNull("Should find John Doe migrated by Liquibase", john);
        assertEquals("John", john.getFirstName());
        assertEquals("Doe", john.getLastName());
        assertEquals("CA", john.getState());
        assertEquals("jdoe", john.getUsername());
        
        JpaOrderPerson jane = em.find(JpaOrderPerson.class, 2);
        assertNotNull("Should find Jane Smith migrated by Liquibase", jane);
        assertEquals("Jane", jane.getFirstName());
        assertEquals("Smith", jane.getLastName());
        assertEquals("NY", jane.getState());
        assertEquals("jsmith", jane.getUsername());
        
        // Count total records to ensure only Liquibase data exists
        Long count = em.createQuery("SELECT COUNT(p) FROM JpaOrderPerson p", Long.class).getSingleResult();
        assertEquals("Should have exactly 2 records migrated by Liquibase", Long.valueOf(2), count);
    }
}
