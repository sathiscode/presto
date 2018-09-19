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

package com.facebook.presto.hive.statistics;

import com.facebook.presto.hive.HiveBasicStatistics;
import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HivePartition;
import com.facebook.presto.hive.HiveTableHandle;
import com.facebook.presto.hive.PartitionStatistics;
import com.facebook.presto.hive.metastore.HiveColumnStatistics;
import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore;
import com.facebook.presto.hive.util.Statistics.Range;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.statistics.ColumnStatistics;
import com.facebook.presto.spi.statistics.DoubleRange;
import com.facebook.presto.spi.statistics.Estimate;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashFunction;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import org.joda.time.DateTimeZone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.function.Function;
import java.util.stream.DoubleStream;

import static com.facebook.presto.hive.HiveSessionProperties.getPartitionStatisticsSampleSize;
import static com.facebook.presto.hive.HiveSessionProperties.isStatisticsEnabled;
import static com.facebook.presto.hive.util.Statistics.getMinMaxAsPrestoNativeValues;
import static com.facebook.presto.spi.predicate.Utils.nativeValueToBlock;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.Decimals.isLongDecimal;
import static com.facebook.presto.spi.type.Decimals.isShortDecimal;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.StandardTypes.CHAR;
import static com.facebook.presto.spi.type.StandardTypes.VARCHAR;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.hash.Hashing.murmur3_128;
import static java.lang.Double.parseDouble;
import static java.lang.Float.intBitsToFloat;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

public class MetastoreHiveStatisticsProvider
        implements HiveStatisticsProvider
{
    private static final Logger log = Logger.get(MetastoreHiveStatisticsProvider.class);

    private final TypeManager typeManager;
    private final SemiTransactionalHiveMetastore metastore;
    private final DateTimeZone timeZone;

    public MetastoreHiveStatisticsProvider(TypeManager typeManager, SemiTransactionalHiveMetastore metastore, DateTimeZone timeZone)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.timeZone = requireNonNull(timeZone, "timeZone is null");
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, List<HivePartition> queriedPartitions, Map<String, ColumnHandle> tableColumns)
    {
        if (!isStatisticsEnabled(session)) {
            return TableStatistics.empty();
        }

        int queriedPartitionsCount = queriedPartitions.size();
        int sampleSize = getPartitionStatisticsSampleSize(session);
        List<HivePartition> samplePartitions = getPartitionsSample(queriedPartitions, sampleSize);

        Map<String, PartitionStatistics> statisticsSample = getPartitionsStatistics((HiveTableHandle) tableHandle, samplePartitions);

        TableStatistics.Builder tableStatistics = TableStatistics.builder();
        OptionalDouble rowsPerPartition = calculateRowsPerPartition(statisticsSample);
        Estimate rowCount = calculateRowsCount(rowsPerPartition, queriedPartitionsCount);
        tableStatistics.setRowCount(rowCount);
        for (Map.Entry<String, ColumnHandle> columnEntry : tableColumns.entrySet()) {
            String columnName = columnEntry.getKey();
            HiveColumnHandle hiveColumnHandle = (HiveColumnHandle) columnEntry.getValue();

            List<Object> lowValueCandidates = ImmutableList.of();
            List<Object> highValueCandidates = ImmutableList.of();

            Type prestoType = typeManager.getType(hiveColumnHandle.getTypeSignature());
            Estimate nullsFraction;
            Estimate dataSize;
            ColumnStatistics.Builder columnStatistics = ColumnStatistics.builder();
            if (hiveColumnHandle.isPartitionKey()) {
                columnStatistics.setDistinctValuesCount(countDistinctPartitionKeys(hiveColumnHandle, queriedPartitions));
                nullsFraction = calculateNullsFractionForPartitioningKey(hiveColumnHandle, queriedPartitions, statisticsSample, rowCount, rowsPerPartition);
                if (isLowHighSupportedForType(prestoType)) {
                    lowValueCandidates = queriedPartitions.stream()
                            .map(HivePartition::getKeys)
                            .map(keys -> keys.get(hiveColumnHandle))
                            .filter(value -> !value.isNull())
                            .map(NullableValue::getValue)
                            .collect(toImmutableList());
                    highValueCandidates = lowValueCandidates;
                }
                dataSize = calculateDataSizeForPartitioningKey(hiveColumnHandle, queriedPartitions, statisticsSample, rowCount, rowsPerPartition);
            }
            else {
                columnStatistics.setDistinctValuesCount(calculateDistinctValuesCount(statisticsSample, columnName));
                nullsFraction = calculateNullsFraction(statisticsSample, queriedPartitionsCount, columnName, rowCount);

                if (isLowHighSupportedForType(prestoType)) {
                    List<Range> ranges = statisticsSample.values().stream()
                            .map(PartitionStatistics::getColumnStatistics)
                            .filter(stats -> stats.containsKey(columnName))
                            .map(stats -> stats.get(columnName))
                            .map(stats -> getMinMaxAsPrestoNativeValues(stats, prestoType, timeZone))
                            .collect(toImmutableList());

                    // TODO[lo] Maybe we do not want to expose high/low value if it is based on too small fraction of
                    //          partitions. And return unknown if most of the partitions we are working with do not have
                    //          statistics computed.
                    lowValueCandidates = ranges.stream()
                            .filter(range -> range.getMin().isPresent())
                            .map(range -> range.getMin().get())
                            .collect(toImmutableList());
                    highValueCandidates = ranges.stream()
                            .filter(range -> range.getMax().isPresent())
                            .map(range -> range.getMax().get())
                            .collect(toImmutableList());
                }
                dataSize = calculateDataSize(statisticsSample, columnName, rowCount);
            }

            Comparator<Object> comparator = (leftValue, rightValue) -> {
                Block leftBlock = nativeValueToBlock(prestoType, leftValue);
                Block rightBlock = nativeValueToBlock(prestoType, rightValue);
                return prestoType.compareTo(leftBlock, 0, rightBlock, 0);
            };
            Optional<Object> min = lowValueCandidates.stream().min(comparator);
            Optional<Object> max = highValueCandidates.stream().max(comparator);
            if (min.isPresent() && max.isPresent()) {
                columnStatistics.setRange(createPrestoRange(prestoType, min.get(), max.get()));
            }
            columnStatistics.setDataSize(dataSize);

            columnStatistics.setNullsFraction(nullsFraction);
            tableStatistics.setColumnStatistics(hiveColumnHandle, columnStatistics.build());
        }
        return tableStatistics.build();
    }

    private boolean isLowHighSupportedForType(Type type)
    {
        if (type instanceof DecimalType) {
            return true;
        }
        if (type.equals(TINYINT)
                || type.equals(SMALLINT)
                || type.equals(INTEGER)
                || type.equals(BIGINT)
                || type.equals(REAL)
                || type.equals(DOUBLE)
                || type.equals(DATE)) {
            return true;
        }
        return false;
    }

    public static DoubleRange createPrestoRange(Type type, Object min, Object max)
    {
        return new DoubleRange(convertPrestoValueToStatsRepresentation(type, min), convertPrestoValueToStatsRepresentation(type, max));
    }

    private static double convertPrestoValueToStatsRepresentation(Type type, Object value)
    {
        if (type.equals(BIGINT) || type.equals(INTEGER) || type.equals(SMALLINT) || type.equals(TINYINT)) {
            return (Long) value;
        }
        if (type.equals(DOUBLE)) {
            return (Double) value;
        }
        if (type.equals(REAL)) {
            return intBitsToFloat(((Long) value).intValue());
        }
        if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            if (isShortDecimal(decimalType)) {
                return parseDouble(Decimals.toString((Long) value, decimalType.getScale()));
            }
            if (isLongDecimal(decimalType)) {
                return parseDouble(Decimals.toString((Slice) value, decimalType.getScale()));
            }
            throw new IllegalArgumentException("Unexpected decimal type: " + decimalType);
        }
        if (type.equals(DATE)) {
            return (Long) value;
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private OptionalDouble calculateRowsPerPartition(Map<String, PartitionStatistics> statisticsSample)
    {
        return statisticsSample.values().stream()
                .map(PartitionStatistics::getBasicStatistics)
                .map(HiveBasicStatistics::getRowCount)
                .filter(OptionalLong::isPresent)
                .mapToLong(OptionalLong::getAsLong)
                .average();
    }

    private Estimate calculateRowsCount(OptionalDouble rowsPerPartition, int queriedPartitionsCount)
    {
        if (!rowsPerPartition.isPresent()) {
            return Estimate.unknown();
        }
        return Estimate.of(rowsPerPartition.getAsDouble() * queriedPartitionsCount);
    }

    private Estimate calculateDistinctValuesCount(Map<String, PartitionStatistics> statisticsSample, String column)
    {
        return summarizePartitionStatistics(
                statisticsSample.values(),
                column,
                columnStatistics -> {
                    if (columnStatistics.getDistinctValuesCount().isPresent()) {
                        return OptionalDouble.of(columnStatistics.getDistinctValuesCount().getAsLong());
                    }
                    if (columnStatistics.getBooleanStatistics().isPresent() &&
                            columnStatistics.getBooleanStatistics().get().getFalseCount().isPresent() &&
                            columnStatistics.getBooleanStatistics().get().getTrueCount().isPresent()) {
                        long falseCount = columnStatistics.getBooleanStatistics().get().getFalseCount().getAsLong();
                        long trueCount = columnStatistics.getBooleanStatistics().get().getTrueCount().getAsLong();
                        return OptionalDouble.of((falseCount > 0 ? 1 : 0) + (trueCount > 0 ? 1 : 0));
                    }
                    return OptionalDouble.empty();
                },
                DoubleStream::max);
    }

    private Estimate calculateNullsFraction(Map<String, PartitionStatistics> statisticsSample, int totalPartitionsCount, String column, Estimate rowCount)
    {
        if (rowCount.isUnknown()) {
            return Estimate.unknown();
        }
        if (rowCount.getValue() == 0.0) {
            return Estimate.zero();
        }

        Estimate totalNullsCount = summarizePartitionStatistics(
                statisticsSample.values(),
                column,
                columnStatistics -> {
                    if (!columnStatistics.getNullsCount().isPresent()) {
                        return OptionalDouble.empty();
                    }
                    return OptionalDouble.of(columnStatistics.getNullsCount().getAsLong());
                },
                nullsCountStream -> {
                    double nullsCount = 0;
                    long partitionsWithStatisticsCount = 0;
                    for (PrimitiveIterator.OfDouble nullsCountIterator = nullsCountStream.iterator(); nullsCountIterator.hasNext(); ) {
                        nullsCount += nullsCountIterator.nextDouble();
                        partitionsWithStatisticsCount++;
                    }

                    if (partitionsWithStatisticsCount == 0) {
                        return OptionalDouble.empty();
                    }
                    return OptionalDouble.of(totalPartitionsCount / partitionsWithStatisticsCount * nullsCount);
                });

        if (totalNullsCount.isUnknown()) {
            return Estimate.unknown();
        }
        return Estimate.of(totalNullsCount.getValue() / rowCount.getValue());
    }

    private Estimate calculateDataSize(Map<String, PartitionStatistics> statisticsSample, String columnName, Estimate rowCount)
    {
        if (rowCount.isUnknown()) {
            return Estimate.unknown();
        }

        int knownPartitionCount = 0;
        double knownRowCount = 0;
        double knownDataSize = 0;

        for (PartitionStatistics statistics : statisticsSample.values()) {
            if (!statistics.getBasicStatistics().getRowCount().isPresent()) {
                continue;
            }
            double partitionRowCount = statistics.getBasicStatistics().getRowCount().getAsLong();

            HiveColumnStatistics partitionColumnStatistics = statistics.getColumnStatistics().get(columnName);
            if (partitionColumnStatistics == null || !partitionColumnStatistics.getTotalSizeInBytes().isPresent()) {
                continue;
            }

            knownPartitionCount++;
            knownRowCount += partitionRowCount;
            // Note: average column length from Hive might not translate directly into internal data size
            knownDataSize += partitionColumnStatistics.getTotalSizeInBytes().getAsLong();
        }

        if (knownPartitionCount == 0) {
            return Estimate.unknown();
        }

        if (knownDataSize == 0) {
            return Estimate.zero();
        }

        verify(knownRowCount > 0);
        return Estimate.of(knownDataSize / knownRowCount * rowCount.getValue());
    }

    private Estimate countDistinctPartitionKeys(HiveColumnHandle partitionColumn, List<HivePartition> partitions)
    {
        return Estimate.of(partitions.stream()
                .map(HivePartition::getKeys)
                .map(keys -> keys.get(partitionColumn))
                .distinct()
                .count());
    }

    private Estimate calculateNullsFractionForPartitioningKey(
            HiveColumnHandle partitionColumn,
            List<HivePartition> queriedPartitions,
            Map<String, PartitionStatistics> statisticsSample,
            Estimate rowCount,
            OptionalDouble rowsPerPartition)
    {
        if (rowCount.isUnknown()) {
            return Estimate.unknown();
        }
        if (rowCount.getValue() == 0.0) {
            return Estimate.zero();
        }
        if (!rowsPerPartition.isPresent()) {
            return Estimate.unknown();
        }

        double estimatedNullsCount = queriedPartitions.stream()
                .filter(partition -> partition.getKeys().get(partitionColumn).isNull())
                .map(HivePartition::getPartitionId)
                .mapToDouble(partitionId -> orElse(statisticsSample.get(partitionId).getBasicStatistics().getRowCount(), rowsPerPartition.getAsDouble()))
                .sum();
        return Estimate.of(estimatedNullsCount / rowCount.getValue());
    }

    private Estimate calculateDataSizeForPartitioningKey(
            HiveColumnHandle partitionColumn,
            List<HivePartition> queriedPartitions,
            Map<String, PartitionStatistics> statisticsSample,
            Estimate rowCount,
            OptionalDouble rowsPerPartition)
    {
        if (rowCount.isUnknown() || !rowsPerPartition.isPresent()) {
            return Estimate.unknown();
        }

        String baseType = partitionColumn.getTypeSignature().getBase();
        if (!VARCHAR.equals(baseType) && !CHAR.equalsIgnoreCase(baseType)) {
            // TODO support VARBINARY
            return Estimate.unknown();
        }

        double knownRowCount = 0;
        double knownDataSize = 0;

        for (HivePartition partition : queriedPartitions) {
            NullableValue value = partition.getKeys().get(partitionColumn);
            int length = value.isNull() ? 0 : ((Slice) value.getValue()).length();

            double partitionRowCount = orElse(
                    Optional.ofNullable(statisticsSample.get(partition.getPartitionId()))
                            .orElseGet(PartitionStatistics::empty)
                            .getBasicStatistics()
                            .getRowCount(),
                    rowsPerPartition.getAsDouble());
            knownRowCount += partitionRowCount;
            knownDataSize += length * partitionRowCount;
        }

        if (knownRowCount == 0) {
            return Estimate.unknown();
        }

        return Estimate.of(knownDataSize / knownRowCount * rowCount.getValue());
    }

    private Estimate summarizePartitionStatistics(
            Collection<PartitionStatistics> partitionStatistics,
            String column,
            Function<HiveColumnStatistics, OptionalDouble> valueExtractFunction,
            Function<DoubleStream, OptionalDouble> valueAggregateFunction)
    {
        DoubleStream intermediateStream = partitionStatistics.stream()
                .map(PartitionStatistics::getColumnStatistics)
                .filter(stats -> stats.containsKey(column))
                .map(stats -> stats.get(column))
                .map(valueExtractFunction)
                .filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble);

        OptionalDouble statisticsValue = valueAggregateFunction.apply(intermediateStream);

        if (!statisticsValue.isPresent()) {
            return Estimate.unknown();
        }
        return Estimate.of(statisticsValue.getAsDouble());
    }

    private Map<String, PartitionStatistics> getPartitionsStatistics(HiveTableHandle tableHandle, List<HivePartition> hivePartitions)
    {
        if (hivePartitions.isEmpty()) {
            return ImmutableMap.of();
        }
        boolean unpartitioned = hivePartitions.stream().anyMatch(partition -> partition.getPartitionId().equals(HivePartition.UNPARTITIONED_ID));
        if (unpartitioned) {
            checkArgument(hivePartitions.size() == 1, "expected only one hive partition");
        }

        if (unpartitioned) {
            return ImmutableMap.of(HivePartition.UNPARTITIONED_ID, metastore.getTableStatistics(tableHandle.getSchemaName(), tableHandle.getTableName()));
        }
        else {
            return metastore.getPartitionStatistics(
                    tableHandle.getSchemaName(),
                    tableHandle.getTableName(),
                    hivePartitions.stream()
                            .map(HivePartition::getPartitionId)
                            .collect(toImmutableSet()));
        }
    }

    @VisibleForTesting
    static List<HivePartition> getPartitionsSample(List<HivePartition> partitions, int sampleSize)
    {
        checkArgument(sampleSize > 0, "sampleSize is expected to be greater than zero");

        if (partitions.size() <= sampleSize) {
            return partitions;
        }

        List<HivePartition> result = new ArrayList<>();

        int samplesLeft = sampleSize;

        HivePartition min = partitions.get(0);
        HivePartition max = partitions.get(0);
        for (HivePartition partition : partitions) {
            if (partition.getPartitionId().compareTo(min.getPartitionId()) < 0) {
                min = partition;
            }
            else if (partition.getPartitionId().compareTo(max.getPartitionId()) > 0) {
                max = partition;
            }
        }

        result.add(min);
        samplesLeft--;
        if (samplesLeft > 0) {
            result.add(max);
            samplesLeft--;
        }

        if (samplesLeft > 0) {
            HashFunction hashFunction = murmur3_128();
            Comparator<Map.Entry<HivePartition, Long>> hashComparator = Comparator
                    .<Map.Entry<HivePartition, Long>, Long>comparing(Map.Entry::getValue)
                    .thenComparing(entry -> entry.getKey().getPartitionId());
            partitions.stream()
                    .filter(partition -> !result.contains(partition))
                    .map(partition -> immutableEntry(partition, hashFunction.hashUnencodedChars(partition.getPartitionId()).asLong()))
                    .sorted(hashComparator)
                    .limit(samplesLeft)
                    .forEachOrdered(entry -> result.add(entry.getKey()));
        }

        return unmodifiableList(result);
    }

    private static double orElse(OptionalLong value, double other)
    {
        if (value.isPresent()) {
            return value.getAsLong();
        }
        return other;
    }
}
