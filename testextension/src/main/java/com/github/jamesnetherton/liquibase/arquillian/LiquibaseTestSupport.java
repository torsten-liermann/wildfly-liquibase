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
package com.github.jamesnetherton.liquibase.arquillian;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiquibaseTestSupport {

    protected static final Logger LOG = LoggerFactory.getLogger(LiquibaseTestSupport.class);
    protected static final List<String> DEFAULT_COLUMNS = Arrays.asList("firstname", "id", "lastname", "state", "username");
    private static final String QUERY_TABLES = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES";
    private static final String QUERY_TABLE_COLUMNS = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY COLUMN_NAME ASC";
    private static final String EXAMPLE_DS = "java:jboss/datasources/ExampleDS";

    @ArquillianResource
    private InitialContext context;

    // ManagementClient is created on-demand instead of injection
    // because @ArquillianResource injection doesn't work in-container with WildFly Arquillian 5.x
    private ManagementClient managementClient;

    @SuppressWarnings("unchecked")
    protected <T> T lookup(String name, Class<?> T) throws Exception {
        return (T) context.lookup(name);
    }

    protected void debugDatabase() throws Exception {
        DataSource dataSource = lookup(EXAMPLE_DS, DataSource.class);
        try(Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(QUERY_TABLES)) {
                ResultSet resultSet = statement.executeQuery();
                LOG.info("=========> Tables <=========");
                while (resultSet.next()) {
                    LOG.info(resultSet.getString("TABLE_NAME"));
                }
            }
        }
    }

    protected void assertTableModified(String tableName) throws Exception {
        assertTableModified(tableName, DEFAULT_COLUMNS);
    }

    protected void assertTableModified(String tableName, List<String> expectedColumns) throws Exception {
        assertTableModified(tableName, expectedColumns, EXAMPLE_DS);
    }

    protected void assertTableModified(String tableName, List<String> expectedColumns, String dsJndiName) throws Exception {
        List<String> actualColumns = new ArrayList<>();

        DataSource dataSource = lookup(dsJndiName, DataSource.class);

        try(Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(QUERY_TABLE_COLUMNS)) {
                statement.setString(1, tableName.toUpperCase());

                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    actualColumns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }

        Assertions.assertEquals(expectedColumns, actualColumns);
    }

    protected boolean removeLiquibaseDmrModel(String name) throws Exception {
        return executeDmrRemove("liquibase", "databaseChangeLog", name);
    }

    protected boolean addDataSource(String dataSourceName, String databaseName) throws Exception {
        ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("data-source", dataSourceName);

        ModelNode operation = new ModelNode();
        operation.get("operation").set("add");
        operation.get("address").set(address);
        operation.get("jndi-name").set("java:jboss/datasources/" + dataSourceName);
        operation.get("driver-name").set("h2");
        operation.get("connection-url").set("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        operation.get("user-name").set("sa");
        operation.get("password").set("sa");

        return executeDmrOperation(operation);
    }

    protected boolean removeDataSource(String dataSourceName) throws Exception {
        return executeDmrRemove("datasources", "data-source", dataSourceName);
    }

    protected boolean deploy(String deployment) throws Exception {
        // For file-based deployment, use ServerDeploymentHelper
        File deploymentFile = new File(deployment);
        if (deploymentFile.exists()) {
            ManagementClient client = getOrCreateManagementClient();
            ServerDeploymentHelper server = new ServerDeploymentHelper(client.getControllerClient());
            server.deploy(deploymentFile.getName(), deploymentFile.toURI().toURL().openStream());
            return true;
        }
        LOG.error("Deployment file not found: {}", deployment);
        return false;
    }

    protected boolean undeploy(String deployment) throws Exception {
        ManagementClient client = getOrCreateManagementClient();
        ServerDeploymentHelper server = new ServerDeploymentHelper(client.getControllerClient());
        server.undeploy(deployment);
        return true;
    }

    /**
     * Executes a CLI script file by parsing and executing DMR operations.
     * Uses ModelControllerClient API instead of spawning external CLI process.
     */
    protected boolean executeCliScript(File scriptFile) throws Exception {
        LOG.info("Executing CLI script via DMR: {}", scriptFile.getAbsolutePath());

        try (BufferedReader reader = new BufferedReader(new FileReader(scriptFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Skip empty lines and comments
                }

                if (!parseDmrCommand(line)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Resolves a test resource file path. Uses the test.resources.dir system property
     * when running in-container to get an absolute path that works from any working directory.
     * Falls back to target/test-classes for local execution.
     *
     * @param relativePath the relative path within test resources (e.g., "cli/changelog-add.cli")
     * @return a File with the correct absolute path
     */
    protected File getTestResourceFile(String relativePath) {
        String testResourcesDir = System.getProperty("test.resources.dir");
        if (testResourcesDir != null) {
            return new File(testResourcesDir, relativePath);
        }
        // Fallback for local execution
        return new File("target/test-classes", relativePath);
    }

    protected boolean executeCliCommand(String command) throws Exception {
        return parseDmrCommand(command);
    }

    /**
     * Parses a CLI command string and executes it via DMR.
     * Supports liquibase subsystem operations and common management operations.
     */
    private boolean parseDmrCommand(String command) throws Exception {
        LOG.debug("Parsing DMR command: {}", command);

        // Pattern for /subsystem=X/resource=Y/:operation(params...)
        Pattern dmrPattern = Pattern.compile("^/subsystem=([^/]+)/([^=]+)=([^/:]+)/?:([a-z-]+)\\((.*)\\)$");
        Matcher dmrMatcher = dmrPattern.matcher(command);

        if (dmrMatcher.matches()) {
            String subsystem = dmrMatcher.group(1);
            String resourceType = dmrMatcher.group(2);
            String resourceName = dmrMatcher.group(3);
            String operation = dmrMatcher.group(4);
            String params = dmrMatcher.group(5);

            return executeParsedDmrOperation(subsystem, resourceType, resourceName, operation, params);
        }

        // Pattern for simple remove: /subsystem=X/resource=Y/:remove
        Pattern removePattern = Pattern.compile("^/subsystem=([^/]+)/([^=]+)=([^/:]+)/?:remove$");
        Matcher removeMatcher = removePattern.matcher(command);

        if (removeMatcher.matches()) {
            String subsystem = removeMatcher.group(1);
            String resourceType = removeMatcher.group(2);
            String resourceName = removeMatcher.group(3);
            return executeDmrRemove(subsystem, resourceType, resourceName);
        }

        LOG.warn("Unsupported CLI command format: {}", command);
        return false;
    }

    /**
     * Executes a parsed DMR operation.
     */
    private boolean executeParsedDmrOperation(String subsystem, String resourceType, String resourceName,
                                               String operation, String params) throws Exception {
        ModelNode address = new ModelNode();
        address.add("subsystem", subsystem);
        address.add(resourceType, resourceName);

        ModelNode op = new ModelNode();
        op.get("address").set(address);

        switch (operation) {
            case "add":
                op.get("operation").set("add");
                parseAndSetParams(op, params);
                break;
            case "write-attribute":
                op.get("operation").set("write-attribute");
                parseAndSetParams(op, params);
                break;
            case "remove":
                op.get("operation").set("remove");
                break;
            default:
                LOG.warn("Unsupported DMR operation: {}", operation);
                return false;
        }

        return executeDmrOperation(op);
    }

    /**
     * Parses CLI-style parameters and sets them on a ModelNode.
     * Handles parameters like: name=value,name2=value2
     */
    private void parseAndSetParams(ModelNode operation, String params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        // Parse parameters - handle quoted values and nested content
        // Pattern: name="value" or name=value
        int i = 0;
        while (i < params.length()) {
            // Find parameter name
            int eqPos = params.indexOf('=', i);
            if (eqPos < 0) break;

            String name = params.substring(i, eqPos).trim();
            i = eqPos + 1;

            // Find parameter value
            String value;
            if (i < params.length() && params.charAt(i) == '"') {
                // Quoted value - find matching end quote
                i++; // Skip opening quote
                int endQuote = findMatchingQuote(params, i);
                value = params.substring(i, endQuote);
                i = endQuote + 1;
                // Skip comma if present
                if (i < params.length() && params.charAt(i) == ',') {
                    i++;
                }
            } else {
                // Unquoted value - find comma or end
                int commaPos = params.indexOf(',', i);
                if (commaPos < 0) {
                    value = params.substring(i).trim();
                    i = params.length();
                } else {
                    value = params.substring(i, commaPos).trim();
                    i = commaPos + 1;
                }
            }

            // Resolve WildFly expression placeholders like ${env.VAR:default}
            value = resolveExpression(value);

            LOG.debug("Setting parameter: {} = {}", name, value.length() > 100 ? value.substring(0, 100) + "..." : value);
            operation.get(name).set(value);
        }
    }

    /**
     * Finds the matching end quote in a string, handling escaped quotes.
     */
    private int findMatchingQuote(String str, int startPos) {
        for (int i = startPos; i < str.length(); i++) {
            if (str.charAt(i) == '"') {
                // Check for escaped quote
                if (i > startPos && str.charAt(i - 1) == '\\') {
                    continue;
                }
                return i;
            }
        }
        return str.length();
    }

    /**
     * Resolves WildFly expression placeholders.
     */
    private String resolveExpression(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        // Pattern: ${name:default} or ${env.NAME:default}
        Pattern exprPattern = Pattern.compile("\\$\\{([^}]+)}");
        Matcher matcher = exprPattern.matcher(value);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String expr = matcher.group(1);
            String resolved = resolveExpressionValue(expr);
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Resolves a single expression value.
     */
    private String resolveExpressionValue(String expr) {
        String defaultValue = "";
        String varName = expr;

        int colonPos = expr.indexOf(':');
        if (colonPos > 0) {
            varName = expr.substring(0, colonPos);
            defaultValue = expr.substring(colonPos + 1);
        }

        // Check system property first
        String resolved = System.getProperty(varName);
        if (resolved != null) {
            return resolved;
        }

        // Check environment variable (handle env.NAME format)
        if (varName.startsWith("env.")) {
            String envName = varName.substring(4);
            resolved = System.getenv(envName);
            if (resolved != null) {
                return resolved;
            }
        } else {
            // Try as environment variable directly
            resolved = System.getenv(varName.replace('.', '_').toUpperCase());
            if (resolved != null) {
                return resolved;
            }
        }

        return defaultValue;
    }

    /**
     * Executes a DMR remove operation.
     */
    private boolean executeDmrRemove(String subsystem, String resourceType, String resourceName) throws Exception {
        ModelNode address = new ModelNode();
        address.add("subsystem", subsystem);
        address.add(resourceType, resourceName);

        ModelNode operation = new ModelNode();
        operation.get("operation").set("remove");
        operation.get("address").set(address);

        return executeDmrOperation(operation);
    }

    /**
     * Executes a DMR operation using the ModelControllerClient.
     */
    private boolean executeDmrOperation(ModelNode operation) throws Exception {
        ManagementClient client = getOrCreateManagementClient();
        ModelNode result = client.getControllerClient().execute(operation);

        String outcome = result.get("outcome").asString();
        if ("success".equals(outcome)) {
            LOG.info("DMR operation succeeded: {}", operation.get("operation").asString());
            return true;
        } else {
            String failureDescription = result.hasDefined("failure-description")
                ? result.get("failure-description").asString()
                : "Unknown error";
            LOG.error("DMR operation failed: {} - {}", operation.get("operation").asString(), failureDescription);
            return false;
        }
    }

    protected void executeSqlScript(InputStream script, String datasourceBinding) throws Exception {
        if (script == null) {
            throw new IllegalArgumentException("Script InputStream cannot be null");
        }

        try {
            InitialContext initialContext = new InitialContext();
            DataSource dataSource = (DataSource) initialContext.lookup(datasourceBinding);

            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    String sql = TestExtensionUtils.inputStreamToString(script);
                    statement.execute(sql);
                    LOG.info("\nExecuted database initialization script\n{}", sql);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        } catch (NamingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String deployChangeLog(String originalFileName, String runtimeName) throws Exception {
        URL url = getClass().getResource("/" + originalFileName);
        ManagementClient client = getOrCreateManagementClient();
        ServerDeploymentHelper server = new ServerDeploymentHelper(client.getControllerClient());
        return server.deploy(runtimeName, url.openStream());
    }

    protected void undeployChangeLog(String runtimeName) throws Exception {
        ManagementClient client = getOrCreateManagementClient();
        ServerDeploymentHelper server = new ServerDeploymentHelper(client.getControllerClient());
        server.undeploy(runtimeName);
    }

    /**
     * Creates ManagementClient on-demand using direct instantiation.
     * This is needed because @ArquillianResource injection doesn't work
     * for ManagementClient when tests run in-container with WildFly Arquillian 5.x.
     */
    private ManagementClient getOrCreateManagementClient() throws Exception {
        if (managementClient == null) {
            // Use management address/port from system properties or defaults
            // Default port 9990 + offset 1000 = 10990 (as configured in arquillian.xml)
            String address = System.getProperty("management.address", "127.0.0.1");
            int port = Integer.getInteger("jboss.management.native.port", 10990);

            ModelControllerClient controllerClient = ModelControllerClient.Factory.create(
                InetAddress.getByName(address), port);

            managementClient = new ManagementClient(controllerClient, address, port, "remote+http");
        }
        return managementClient;
    }
}
