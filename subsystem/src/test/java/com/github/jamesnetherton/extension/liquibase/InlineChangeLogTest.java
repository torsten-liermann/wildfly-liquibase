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

import static org.junit.Assert.*;

import com.github.jamesnetherton.extension.liquibase.resource.WildFlyResourceAccessor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import liquibase.resource.Resource;
import org.junit.Test;

public class InlineChangeLogTest {

    @Test
    public void testInlineChangeLogResource() throws Exception {
        // Create a test configuration with inline XML content
        String inlineXml = "<changeSet id='1' author='test'><createTable tableName='test_table'><column name='id' type='int'/></createTable></changeSet>";
        
        ChangeLogConfiguration config = ChangeLogConfiguration.builder()
            .name("test-inline-changelog")
            .definition(inlineXml)
            .dataSource("java:jboss/datasources/ExampleDS")
            .classLoader(InlineChangeLogTest.class.getClassLoader())
            .subsystemOrigin()
            .build();
        
        // Test the resource accessor
        WildFlyResourceAccessor accessor = new WildFlyResourceAccessor(config);
        
        // Try to access the "file" using the generated filename
        String requestedPath = config.getFileName();
        List<Resource> resources = accessor.getAll(requestedPath);
        
        assertNotNull("Resources should not be null", resources);
        assertEquals("Should have exactly one resource", 1, resources.size());
        
        Resource resource = resources.get(0);
        assertNotNull("Resource should not be null", resource);
        assertTrue("Resource should exist", resource.exists());
        assertEquals("Resource path should match", requestedPath, resource.getPath());
        
        // Read the content
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            
            String actualContent = content.toString().trim();
            assertTrue("Should contain XML declaration", actualContent.contains("<?xml version"));
            assertTrue("Should contain databaseChangeLog element", actualContent.contains("<databaseChangeLog"));
            assertTrue("Should contain the inline content", actualContent.contains(inlineXml));
            assertTrue("Should contain closing databaseChangeLog element", actualContent.contains("</databaseChangeLog>"));
        }
    }
    
    @Test
    public void testInlineChangeLogWithDifferentFormats() throws Exception {
        // Test YAML format
        String yamlContent = "databaseChangeLog:\n  - changeSet:\n      id: 1\n      author: test";
        testInlineFormat("test-yaml", yamlContent, ChangeLogFormat.YAML);
        
        // Test JSON format
        String jsonContent = "{\"databaseChangeLog\": [{\"changeSet\": {\"id\": \"1\", \"author\": \"test\"}}]}";
        testInlineFormat("test-json", jsonContent, ChangeLogFormat.JSON);
        
        // Test SQL format
        String sqlContent = "--liquibase formatted sql\n--changeset test:1\nCREATE TABLE test_table (id INT);";
        testInlineFormat("test-sql", sqlContent, ChangeLogFormat.SQL);
    }
    
    private void testInlineFormat(String name, String content, ChangeLogFormat expectedFormat) throws Exception {
        ChangeLogConfiguration config = ChangeLogConfiguration.builder()
            .name(name)
            .definition(content)
            .dataSource("java:jboss/datasources/ExampleDS")
            .classLoader(InlineChangeLogTest.class.getClassLoader())
            .subsystemOrigin()
            .build();
        
        assertEquals("Format should be detected correctly", expectedFormat, config.getFormat());
        
        WildFlyResourceAccessor accessor = new WildFlyResourceAccessor(config);
        List<Resource> resources = accessor.getAll(config.getFileName());
        
        assertNotNull("Resources should not be null", resources);
        assertEquals("Should have exactly one resource", 1, resources.size());
        
        Resource resource = resources.get(0);
        assertTrue("Resource should exist", resource.exists());
        
        // For non-XML formats, content should not be wrapped
        if (!expectedFormat.equals(ChangeLogFormat.XML)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder actualContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    actualContent.append(line).append("\n");
                }
                assertEquals("Content should match exactly for non-XML formats", 
                           content.trim(), actualContent.toString().trim());
            }
        }
    }
}
