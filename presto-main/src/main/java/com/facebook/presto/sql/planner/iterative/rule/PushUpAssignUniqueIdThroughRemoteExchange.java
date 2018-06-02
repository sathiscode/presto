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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.matching.Capture;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.sql.planner.PartitioningScheme;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static com.facebook.presto.sql.planner.plan.Patterns.assignUniqueId;
import static com.facebook.presto.sql.planner.plan.Patterns.exchange;
import static com.facebook.presto.sql.planner.plan.Patterns.source;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;

/**
 * Pushes AssignUniqueId node up through the remote exchange to avoid
 * loosing partitioned_on(unique) and grouped(unique) stream properties.
 */
public final class PushUpAssignUniqueIdThroughRemoteExchange
        implements Rule<ExchangeNode>
{
    private static final Capture<AssignUniqueId> ASSIGN_UNIQUE_ID = newCapture();
    private static final Pattern<ExchangeNode> PATTERN = exchange()
            .matching(exchange -> exchange.getScope() == REMOTE)
            .with(source().matching(assignUniqueId().capturedAs(ASSIGN_UNIQUE_ID)));

    @Override
    public Pattern<ExchangeNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ExchangeNode node, Captures captures, Context context)
    {
        AssignUniqueId assignUniqueId = captures.get(ASSIGN_UNIQUE_ID);
        PartitioningScheme partitioningScheme = node.getPartitioningScheme();
        if (partitioningScheme.getPartitioning().getColumns().contains(assignUniqueId.getIdColumn())) {
            // The column produced by the AssignUniqueId is used in the partitioning scheme of the exchange.
            // Hence, AssignUniqueId node has to stay below the exchange node.
            return Result.empty();
        }

        return Result.ofPlanNode(new AssignUniqueId(
                assignUniqueId.getId(),
                new ExchangeNode(
                        node.getId(),
                        node.getType(),
                        node.getScope(),
                        new PartitioningScheme(
                                partitioningScheme.getPartitioning(),
                                removeSymbol(partitioningScheme.getOutputLayout(), assignUniqueId.getIdColumn()),
                                partitioningScheme.getHashColumn(),
                                partitioningScheme.isReplicateNullsAndAny(),
                                partitioningScheme.getBucketToPartition()),
                        ImmutableList.of(assignUniqueId.getSource()),
                        ImmutableList.of(removeSymbol(getOnlyElement(node.getInputs()), assignUniqueId.getIdColumn()))),
                assignUniqueId.getIdColumn()));
    }

    private static List<Symbol> removeSymbol(List<Symbol> symbols, Symbol symbolToRemove)
    {
        return symbols.stream()
                .filter(symbol -> !symbolToRemove.equals(symbol))
                .collect(toImmutableList());
    }
}
