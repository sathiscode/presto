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
package com.facebook.presto.cost;

import com.facebook.presto.Session;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.PlanNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

public class AggregationStatsRule
        extends SimpleStatsRule
{
    private static final Pattern<AggregationNode> PATTERN = Pattern.typeOf(AggregationNode.class);

    public AggregationStatsRule(StatsNormalizer normalizer)
    {
        super(normalizer);
    }

    @Override
    public Pattern<AggregationNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    protected Optional<PlanNodeStatsEstimate> doCalculate(PlanNode node, StatsProvider statsProvider, Lookup lookup, Session session, Map<Symbol, Type> types)
    {
        AggregationNode aggregationNode = (AggregationNode) node;

        if (aggregationNode.getGroupingSets().size() != 1) {
            return Optional.empty();
        }

        PlanNodeStatsEstimate sourceStats = statsProvider.getStats(aggregationNode.getSource());

        List<Symbol> groupBySymbols = getOnlyElement(aggregationNode.getGroupingSets());

        PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.builder();
        for (Symbol groupBySymbol : groupBySymbols) {
            SymbolStatsEstimate symbolStatistics = sourceStats.getSymbolStatistics(groupBySymbol);
            result.addSymbolStatistics(groupBySymbol, symbolStatistics.mapNullsFraction(nullsFraction -> {
                if (nullsFraction == 0.0) {
                    return 0.0;
                }
                return 1.0 / (symbolStatistics.getDistinctValuesCount() + 1);
            }));
        }

        double rowsCount = 1;
        for (Symbol groupBySymbol : groupBySymbols) {
            SymbolStatsEstimate symbolStatistics = sourceStats.getSymbolStatistics(groupBySymbol);
            int nullRow = (symbolStatistics.getNullsFraction() == 0.0) ? 0 : 1;
            rowsCount *= symbolStatistics.getDistinctValuesCount() + nullRow;
        }
        result.setOutputRowCount(min(rowsCount, sourceStats.getOutputRowCount()));

        for (Map.Entry<Symbol, Aggregation> aggregationEntry : aggregationNode.getAggregations().entrySet()) {
            result.addSymbolStatistics(aggregationEntry.getKey(), estimateAggregationStats(aggregationEntry.getValue(), sourceStats));
        }

        return Optional.of(result.build());
    }

    private SymbolStatsEstimate estimateAggregationStats(Aggregation aggregation, PlanNodeStatsEstimate sourceStats)
    {
        requireNonNull(aggregation, "aggregation is null");
        requireNonNull(sourceStats, "sourceStats is null");

        // TODO implement simple aggregations like: min, max, count, sum
        return SymbolStatsEstimate.UNKNOWN_STATS;
    }
}
