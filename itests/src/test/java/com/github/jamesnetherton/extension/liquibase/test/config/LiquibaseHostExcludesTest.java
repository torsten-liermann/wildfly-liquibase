/*-
 * #%L
 * wildfly-liquibase-itests
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
package com.github.jamesnetherton.extension.liquibase.test.config;

import com.github.jamesnetherton.liquibase.arquillian.ChangeLogDefinition;
import com.github.jamesnetherton.liquibase.arquillian.LiquibaseTestSupport;
import com.github.jamesnetherton.liquibase.arquillian.ResourceLocation;
import java.util.Collections;
import liquibase.util.NetUtil;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ArquillianExtension.class)
public class LiquibaseHostExcludesTest extends LiquibaseTestSupport {

    @ChangeLogDefinition(name = "changelog.xml", fileName = "changelog.xml", resourceLocation = ResourceLocation.CLASSPATH)
    private String tableName;

    @Deployment
    public static Archive<?> deployment() {
        // Generate jboss-all.xml dynamically with actual hostname
        String hostName = NetUtil.getLocalHostName();
        String jbossAllXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<jboss xmlns=\"urn:jboss:1.0\">\n" +
                "    <liquibase xmlns=\"urn:com.github.jamesnetherton.liquibase:1.0\" changelog=\"changelog.xml\">\n" +
                "        <host-excludes>" + hostName + "</host-excludes>\n" +
                "    </liquibase>\n" +
                "</jboss>\n";

        return ShrinkWrap.create(WebArchive.class, "liquibase-host-excludes-test.war")
                .addAsManifestResource(new StringAsset(jbossAllXml), "jboss-all.xml");
    }

    @Test
    public void testHostExcludes() throws Exception {
        assertTableModified(tableName, Collections.emptyList());
    }
}
