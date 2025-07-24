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

import com.github.jamesnetherton.extension.liquibase.service.ChangeLogModelService;
import com.github.jamesnetherton.extension.liquibase.service.SubsystemServices;
import org.jboss.as.controller.AbstractWriteAttributeHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants; // MIGRATION NOTE: Added for OP, OP_ADDR, NAME, VALUE constants
import org.jboss.dmr.ModelNode;

// MIGRATION NOTE: This handler also relies on ServiceHelper and ChangeLogModelService.
// Minimal changes expected if ChangeLogModelService is correctly registered and discoverable.
final class ChangeLogWrite extends AbstractWriteAttributeHandler<Object> {

    static final ChangeLogWrite INSTANCE = new ChangeLogWrite();

    private ChangeLogWrite() {
        super(
            ChangeLogResource.CONTEXTS,
            ChangeLogResource.DATASOURCE,
            ChangeLogResource.FAIL_ON_ERROR,
            ChangeLogResource.HOST_EXCLUDES,
            ChangeLogResource.HOST_INCLUDES,
            ChangeLogResource.LABELS,
            ChangeLogResource.VALUE
        );
    }

    @Override
    protected boolean applyUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode resolvedValue, ModelNode currentValue,
            HandbackHolder<Object> handbackHolder) throws OperationFailedException {
        // MIGRATION NOTE: Assumes ServiceHelper.getChangeLogModelUpdateService(context) works.
        // MIGRATION NOTE: The 'operation' model node contains the full operation details including address and new value.
        updateRuntime(context, operation); // Pass the original operation
        return false; // Indicates that a reload/restart is not required solely due to this handler. Resource definitions determine restart requirements.
    }

    @Override
    protected void revertUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode valueToRestore,
            ModelNode valueToRevert, Object handback) throws OperationFailedException {
        // MIGRATION NOTE: Constructing the revert operation ModelNode directly.
        // This creates a new "write-attribute" operation to set the attribute back to its original value.
        ModelNode revertOperation = new ModelNode();
        revertOperation.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION); // Standard operation name

        // MIGRATION NOTE: Correctly convert PathAddress to ModelNode for OP_ADDR
        PathAddress currentAddress = context.getCurrentAddress();
        revertOperation.get(ModelDescriptionConstants.OP_ADDR).set(currentAddress.toModelNode()); // Address of the resource being modified

        revertOperation.get(ModelDescriptionConstants.NAME).set(attributeName); // The specific attribute that was changed
        revertOperation.get(ModelDescriptionConstants.VALUE).set(valueToRestore); // The original value to restore

        updateRuntime(context, revertOperation);
    }

    // MIGRATION NOTE: Simplified updateRuntime to take only context and the operation to execute.
    private void updateRuntime(OperationContext context, ModelNode operationToExecute) throws OperationFailedException {
        ChangeLogModelService service = SubsystemServices.getChangeLogModelService();
        if (service == null) {
            throw new OperationFailedException("ChangeLogModelService not available at path " + ChangeLogModelService.getServiceName());
        }
        // This operation is now either the original "write-attribute" or the constructed "revert-write-attribute".
        service.updateChangeLogModel(context, operationToExecute);
    }
}
