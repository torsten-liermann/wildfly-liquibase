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
import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.dmr.ModelNode;

// MIGRATION NOTE: Similar to ChangeLogAdd, relies on ServiceHelper and ChangeLogModelService.
// Minimal changes expected if ChangeLogModelService is correctly registered and discoverable.
final class ChangeLogRemove extends AbstractRemoveStepHandler {

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        // MIGRATION NOTE: Assumes ServiceHelper.getChangeLogModelUpdateService(context) correctly retrieves the refactored ChangeLogModelService.
        // MIGRATION NOTE: 'model' here refers to the state of the resource *before* removal.
        ChangeLogModelService service = SubsystemServices.getChangeLogModelService();
        if (service == null) {
            throw new OperationFailedException("ChangeLogModelService not available");
        }
        // MIGRATION NOTE: Pass the 'model' (which represents the resource being removed) to the service method.
        // The original code passed 'model', which is correct for remove operations as it holds the state of the resource to be removed.
        service.removeChangeLogModel(context, model);
    }

    // MIGRATION NOTE: If a rollback of remove is needed (i.e., re-adding the resource), it would be implemented in recoverRuntime.
    // The default AbstractRemoveStepHandler.recoverRuntime typically re-runs the add operation.
    // Given this DUP only removes services, custom recovery logic might involve re-creating the model and calling createChangeLogModel.
    // For this migration, we assume the default rollback (re-add) behavior is sufficient if not overridden.
}
