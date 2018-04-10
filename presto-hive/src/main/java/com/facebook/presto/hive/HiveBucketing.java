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
package com.facebook.presto.hive;

import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.predicate.ValueSet;
import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import org.apache.hadoop.hive.ql.io.DefaultHivePartitioner;
import org.apache.hadoop.hive.ql.io.HiveKey;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFHash;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.IntObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.HiveColumnHandle.BUCKET_COLUMN_NAME;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static com.facebook.presto.hive.HiveUtil.getRegularColumnHandles;
import static com.facebook.presto.hive.HiveUtil.getTableStructFields;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.collect.Sets.immutableEnumSet;
import static java.lang.Double.doubleToLongBits;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Map.Entry;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredJavaObject;
import static org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import static org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaShortObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;

final class HiveBucketing
{
    private static final Logger log = Logger.get(HiveBucketing.class);

    private static final Set<PrimitiveCategory> SUPPORTED_TYPES = immutableEnumSet(
            PrimitiveCategory.BYTE,
            PrimitiveCategory.SHORT,
            PrimitiveCategory.INT,
            PrimitiveCategory.LONG,
            PrimitiveCategory.BOOLEAN,
            PrimitiveCategory.STRING);

    private HiveBucketing() {}

    public static int getHiveBucket(List<TypeInfo> types, Page page, int position, int bucketCount)
    {
        return (getBucketHashCode(types, page, position) & Integer.MAX_VALUE) % bucketCount;
    }

    private static int getBucketHashCode(List<TypeInfo> types, Page page, int position)
    {
        int result = 0;
        for (int i = 0; i < page.getChannelCount(); i++) {
            int fieldHash = hash(types.get(i), page.getBlock(i), position);
            result = result * 31 + fieldHash;
        }
        return result;
    }

    private static int hash(TypeInfo type, Block block, int position)
    {
        // This function mirrors the behavior of function hashCode in
        // HIVE-12025 ba83fd7bff serde/src/java/org/apache/hadoop/hive/serde2/objectinspector/ObjectInspectorUtils.java
        // https://github.com/apache/hive/blob/ba83fd7bff/serde/src/java/org/apache/hadoop/hive/serde2/objectinspector/ObjectInspectorUtils.java

        // HIVE-7148 proposed change to bucketing hash algorithms. If that gets implemented, this function will need to change significantly.

        if (block.isNull(position)) {
            return 0;
        }

        switch (type.getCategory()) {
            case PRIMITIVE: {
                PrimitiveTypeInfo typeInfo = (PrimitiveTypeInfo) type;
                PrimitiveCategory primitiveCategory = typeInfo.getPrimitiveCategory();
                Type prestoType = requireNonNull(HiveType.getPrimitiveType(typeInfo));
                switch (primitiveCategory) {
                    case BOOLEAN:
                        return prestoType.getBoolean(block, position) ? 1 : 0;
                    case BYTE:
                        return SignedBytes.checkedCast(prestoType.getLong(block, position));
                    case SHORT:
                        return Shorts.checkedCast(prestoType.getLong(block, position));
                    case INT:
                        return toIntExact(prestoType.getLong(block, position));
                    case LONG:
                        long bigintValue = prestoType.getLong(block, position);
                        return (int) ((bigintValue >>> 32) ^ bigintValue);
                    case FLOAT:
                        return (int) prestoType.getLong(block, position);
                    case DOUBLE:
                        long doubleValue = doubleToLongBits(prestoType.getDouble(block, position));
                        return (int) ((doubleValue >>> 32) ^ doubleValue);
                    case STRING:
                        return hashBytes(0, prestoType.getSlice(block, position));
                    case VARCHAR:
                        return hashBytes(1, prestoType.getSlice(block, position));
                    case DATE:
                        // day offset from 1970-01-01
                        long days = prestoType.getLong(block, position);
                        return toIntExact(days);
                    case TIMESTAMP:
                        long millisSinceEpoch = prestoType.getLong(block, position);
                        // seconds << 30 + nanoseconds
                        long secondsAndNanos = (Math.floorDiv(millisSinceEpoch, 1000L) << 30) + Math.floorMod(millisSinceEpoch, 1000);
                        return (int) ((secondsAndNanos >>> 32) ^ secondsAndNanos);
                    default:
                        throw new UnsupportedOperationException("Computation of Hive bucket hashCode is not supported for Hive primitive category: " + primitiveCategory.toString() + ".");
                }
            }
            case LIST: {
                TypeInfo elementTypeInfo = ((ListTypeInfo) type).getListElementTypeInfo();
                Block elementsBlock = block.getObject(position, Block.class);
                int result = 0;
                for (int i = 0; i < elementsBlock.getPositionCount(); i++) {
                    result = result * 31 + hash(elementTypeInfo, elementsBlock, i);
                }
                return result;
            }
            case MAP: {
                MapTypeInfo mapTypeInfo = (MapTypeInfo) type;
                TypeInfo keyTypeInfo = mapTypeInfo.getMapKeyTypeInfo();
                TypeInfo valueTypeInfo = mapTypeInfo.getMapValueTypeInfo();
                Block elementsBlock = block.getObject(position, Block.class);
                int result = 0;
                for (int i = 0; i < elementsBlock.getPositionCount(); i += 2) {
                    result += hash(keyTypeInfo, elementsBlock, i) ^ hash(valueTypeInfo, elementsBlock, i + 1);
                }
                return result;
            }
            default:
                // TODO: support more types, e.g. ROW
                throw new UnsupportedOperationException("Computation of Hive bucket hashCode is not supported for Hive category: " + type.getCategory().toString() + ".");
        }
    }

    private static int hashBytes(int initialValue, Slice bytes)
    {
        int result = initialValue;
        for (int i = 0; i < bytes.length(); i++) {
            result = result * 31 + bytes.getByte(i);
        }
        return result;
    }

    public static Optional<HiveBucketHandle> getHiveBucketHandle(Table table)
    {
        Optional<HiveBucketProperty> hiveBucketProperty = table.getStorage().getBucketProperty();
        if (!hiveBucketProperty.isPresent()) {
            return Optional.empty();
        }

        Map<String, HiveColumnHandle> map = getRegularColumnHandles(table).stream()
                .collect(Collectors.toMap(HiveColumnHandle::getName, identity()));

        ImmutableList.Builder<HiveColumnHandle> bucketColumns = ImmutableList.builder();
        for (String bucketColumnName : hiveBucketProperty.get().getBucketedBy()) {
            HiveColumnHandle bucketColumnHandle = map.get(bucketColumnName);
            if (bucketColumnHandle == null) {
                throw new PrestoException(
                        HIVE_INVALID_METADATA,
                        format("Table '%s.%s' is bucketed on non-existent column '%s'", table.getDatabaseName(), table.getTableName(), bucketColumnName));
            }
            bucketColumns.add(bucketColumnHandle);
        }

        return Optional.of(new HiveBucketHandle(bucketColumns.build(), hiveBucketProperty.get().getBucketCount()));
    }

    public static Optional<HiveBucketFilter> getHiveBucketFilter(Table table, TupleDomain<ColumnHandle> effectivePredicate)
    {
        if (!table.getStorage().getBucketProperty().isPresent()) {
            return Optional.empty();
        }

        Optional<Map<ColumnHandle, NullableValue>> bindings = TupleDomain.extractFixedValues(effectivePredicate);
        if (!bindings.isPresent()) {
            return Optional.empty();
        }
        Optional<HiveBucketFilter> singleBucket = getHiveBucket(table, bindings.get());
        if (singleBucket.isPresent()) {
            return singleBucket;
        }

        if (!effectivePredicate.getDomains().isPresent()) {
            return Optional.empty();
        }
        Optional<Domain> domain = effectivePredicate.getDomains().get().entrySet().stream()
                .filter(entry -> ((HiveColumnHandle) entry.getKey()).getName().equals(BUCKET_COLUMN_NAME))
                .findFirst()
                .map(Entry::getValue);
        if (!domain.isPresent()) {
            return Optional.empty();
        }
        ValueSet values = domain.get().getValues();
        ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
        int bucketCount = table.getStorage().getBucketProperty().get().getBucketCount();
        for (int i = 0; i < bucketCount; i++) {
            if (values.containsValue((long) i)) {
                builder.add(i);
            }
        }
        return Optional.of(new HiveBucketFilter(builder.build()));
    }

    private static Optional<HiveBucketFilter> getHiveBucket(Table table, Map<ColumnHandle, NullableValue> bindings)
    {
        if (bindings.isEmpty()) {
            return Optional.empty();
        }

        List<String> bucketColumns = table.getStorage().getBucketProperty().get().getBucketedBy();
        Map<String, ObjectInspector> objectInspectors = new HashMap<>();

        // Get column name to object inspector mapping
        for (StructField field : getTableStructFields(table)) {
            objectInspectors.put(field.getFieldName(), field.getFieldObjectInspector());
        }

        // Verify the bucket column types are supported
        for (String column : bucketColumns) {
            ObjectInspector inspector = objectInspectors.get(column);
            if ((inspector == null) || (inspector.getCategory() != Category.PRIMITIVE)) {
                return Optional.empty();
            }
            if (!SUPPORTED_TYPES.contains(((PrimitiveObjectInspector) inspector).getPrimitiveCategory())) {
                return Optional.empty();
            }
        }

        // Get bindings for bucket columns
        Map<String, Object> bucketBindings = new HashMap<>();
        for (Entry<ColumnHandle, NullableValue> entry : bindings.entrySet()) {
            HiveColumnHandle colHandle = (HiveColumnHandle) entry.getKey();
            if (!entry.getValue().isNull() && bucketColumns.contains(colHandle.getName())) {
                bucketBindings.put(colHandle.getName(), entry.getValue().getValue());
            }
        }

        // Check that we have bindings for all bucket columns
        if (bucketBindings.size() != bucketColumns.size()) {
            return Optional.empty();
        }

        // Get bindings of bucket columns
        ImmutableList.Builder<Entry<ObjectInspector, Object>> columnBindings = ImmutableList.builder();
        for (String column : bucketColumns) {
            columnBindings.add(immutableEntry(objectInspectors.get(column), bucketBindings.get(column)));
        }

        return getHiveBucket(columnBindings.build(), table.getStorage().getBucketProperty().get().getBucketCount());
    }

    @VisibleForTesting
    static Optional<HiveBucketFilter> getHiveBucket(List<Entry<ObjectInspector, Object>> columnBindings, int bucketCount)
    {
        try {
            @SuppressWarnings("resource")
            GenericUDFHash udf = new GenericUDFHash();
            ObjectInspector[] objectInspectors = new ObjectInspector[columnBindings.size()];
            DeferredObject[] deferredObjects = new DeferredObject[columnBindings.size()];

            int i = 0;
            for (Entry<ObjectInspector, Object> entry : columnBindings) {
                objectInspectors[i] = getJavaObjectInspector(entry.getKey());
                deferredObjects[i] = getJavaDeferredObject(entry.getValue(), entry.getKey());
                i++;
            }

            ObjectInspector udfInspector = udf.initialize(objectInspectors);
            IntObjectInspector inspector = (IntObjectInspector) udfInspector;

            Object result = udf.evaluate(deferredObjects);
            HiveKey hiveKey = new HiveKey();
            hiveKey.setHashCode(inspector.get(result));

            int bucketNumber = new DefaultHivePartitioner<>().getBucket(hiveKey, null, bucketCount);

            return Optional.of(new HiveBucketFilter(ImmutableSet.of(bucketNumber)));
        }
        catch (HiveException e) {
            log.debug(e, "Error evaluating bucket number");
            return Optional.empty();
        }
    }

    private static ObjectInspector getJavaObjectInspector(ObjectInspector objectInspector)
    {
        checkArgument(objectInspector.getCategory() == Category.PRIMITIVE, "Unsupported object inspector category %s", objectInspector.getCategory());
        PrimitiveObjectInspector poi = ((PrimitiveObjectInspector) objectInspector);
        switch (poi.getPrimitiveCategory()) {
            case BOOLEAN:
                return javaBooleanObjectInspector;
            case BYTE:
                return javaByteObjectInspector;
            case SHORT:
                return javaShortObjectInspector;
            case INT:
                return javaIntObjectInspector;
            case LONG:
                return javaLongObjectInspector;
            case STRING:
                return javaStringObjectInspector;
        }
        throw new RuntimeException("Unsupported type: " + poi.getPrimitiveCategory());
    }

    private static DeferredObject getJavaDeferredObject(Object object, ObjectInspector objectInspector)
    {
        checkArgument(objectInspector.getCategory() == Category.PRIMITIVE, "Unsupported object inspector category %s", objectInspector.getCategory());
        PrimitiveObjectInspector poi = ((PrimitiveObjectInspector) objectInspector);
        switch (poi.getPrimitiveCategory()) {
            case BOOLEAN:
                return new DeferredJavaObject(object);
            case BYTE:
                return new DeferredJavaObject(((Long) object).byteValue());
            case SHORT:
                return new DeferredJavaObject(((Long) object).shortValue());
            case INT:
                return new DeferredJavaObject(((Long) object).intValue());
            case LONG:
                return new DeferredJavaObject(object);
            case STRING:
                return new DeferredJavaObject(((Slice) object).toStringUtf8());
        }
        throw new RuntimeException("Unsupported type: " + poi.getPrimitiveCategory());
    }

    public static class HiveBucketFilter
    {
        private final Set<Integer> bucketsToKeep;

        public HiveBucketFilter(@JsonProperty("bucketsToKeep") Set<Integer> bucketsToKeep)
        {
            this.bucketsToKeep = bucketsToKeep;
        }

        @JsonProperty
        public Set<Integer> getBucketsToKeep()
        {
            return bucketsToKeep;
        }
    }
}
