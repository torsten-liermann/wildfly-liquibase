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

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.List;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.subsystem.test.AbstractSubsystemTest;
import org.jboss.dmr.ModelNode;
import org.junit.Assert;
import org.junit.Test;

public class SubsystemInlineParsingTestCase extends AbstractSubsystemTest {

    public SubsystemInlineParsingTestCase() {
        super(LiquibaseExtension.SUBSYSTEM_NAME, new LiquibaseExtension());
    }

    @Test
    public void testParseInlineChangeLog() throws Exception {
        //Parse the subsystem xml with inline changelog
        String subsystemXml = readResource("subsystem-inline.xml");
        List<ModelNode> operations = super.parse(subsystemXml);

        // Should have: 1 subsystem add + 2 changelog adds
        Assert.assertEquals(3, operations.size());

        // Check subsystem add
        ModelNode addSubsystem = operations.get(0);
        Assert.assertEquals(ADD, addSubsystem.get(OP).asString());
        PathAddress addr = PathAddress.pathAddress(addSubsystem.get(OP_ADDR));
        Assert.assertEquals(1, addr.size());
        PathElement element = addr.getElement(0);
        Assert.assertEquals(SUBSYSTEM, element.getKey());
        Assert.assertEquals(LiquibaseExtension.SUBSYSTEM_NAME, element.getValue());

        // Check file-based changelog (no VALUE attribute)
        ModelNode fileBasedChangelog = operations.get(1);
        Assert.assertEquals(ADD, fileBasedChangelog.get(OP).asString());
        Assert.assertEquals("java:jboss/datasources/ExampleDS", fileBasedChangelog.get(ModelConstants.DATASOURCE).asString());
        Assert.assertEquals("production", fileBasedChangelog.get(ModelConstants.CONTEXTS).asString());
        Assert.assertFalse("File-based changelog should not have VALUE attribute", 
                          fileBasedChangelog.hasDefined(ModelConstants.VALUE));
        addr = PathAddress.pathAddress(fileBasedChangelog.get(OP_ADDR));
        element = addr.getElement(1);
        Assert.assertEquals("databaseChangeLog", element.getKey());
        Assert.assertEquals("file-based-changelog", element.getValue());

        // Check inline changelog (has VALUE attribute with content)
        ModelNode inlineChangelog = operations.get(2);
        Assert.assertEquals(ADD, inlineChangelog.get(OP).asString());
        Assert.assertEquals("java:jboss/datasources/ExampleDS", inlineChangelog.get(ModelConstants.DATASOURCE).asString());
        Assert.assertEquals("test", inlineChangelog.get(ModelConstants.CONTEXTS).asString());
        Assert.assertTrue("Inline changelog should have VALUE attribute", 
                         inlineChangelog.hasDefined(ModelConstants.VALUE));
        
        String inlineContent = inlineChangelog.get(ModelConstants.VALUE).asString();
        Assert.assertTrue("Should contain changeSet element", inlineContent.contains("<changeSet"));
        Assert.assertTrue("Should contain createTable element", inlineContent.contains("<createTable"));
        Assert.assertTrue("Should contain test_table", inlineContent.contains("test_table"));
        Assert.assertTrue("Should contain second changeSet", inlineContent.contains("id=\"2\""));
        Assert.assertTrue("Should contain addColumn element", inlineContent.contains("<addColumn"));
        
        addr = PathAddress.pathAddress(inlineChangelog.get(OP_ADDR));
        element = addr.getElement(1);
        Assert.assertEquals("databaseChangeLog", element.getKey());
        Assert.assertEquals("inline-xml-changelog", element.getValue());
    }
}