/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.plan;

import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.concurrent.Immutable;

import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

@Immutable
public final class ValuesNode
        extends PlanNode
{
    private final List<VariableReferenceExpression> outputVariables;
    private final List<List<RowExpression>> rows;

    @JsonCreator
    public ValuesNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("outputVariables") List<VariableReferenceExpression> outputVariables,
            @JsonProperty("rows") List<List<RowExpression>> rows)
    {
        super(id);
        this.outputVariables = unmodifiableList(new ArrayList<>(outputVariables));
        this.rows = unmodifiableList(rows);

        for (List<RowExpression> row : rows) {
            if (!(row.size() == outputVariables.size() || row.size() == 0)) {
                throw new IllegalArgumentException(format("Expected row to have %s values, but row has %s values", outputVariables.size(), row.size()));
            }
        }
    }

    @Override
    @JsonProperty
    public List<VariableReferenceExpression> getOutputVariables()
    {
        return outputVariables;
    }

    @JsonProperty
    public List<List<RowExpression>> getRows()
    {
        return rows;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return unmodifiableList(emptyList());
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitValues(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        if (!newChildren.isEmpty()) {
            throw new IllegalArgumentException("newChildren is not empty");
        }
        return this;
    }
}
