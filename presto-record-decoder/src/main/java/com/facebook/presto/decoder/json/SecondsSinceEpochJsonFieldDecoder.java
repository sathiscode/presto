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
package com.facebook.presto.decoder.json;

import com.facebook.presto.decoder.DecoderColumnHandle;
import com.facebook.presto.decoder.FieldValueProvider;
import com.fasterxml.jackson.databind.JsonNode;
import io.airlift.slice.Slice;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.util.Locale;

import static io.airlift.slice.Slices.utf8Slice;
import static java.util.Objects.requireNonNull;

/**
 * Seconds since the epoch date format decoder.
 * <p>
 * Uses hardcoded UTC timezone and english locale.
 */
public class SecondsSinceEpochJsonFieldDecoder
        extends JsonFieldDecoder
{
    /**
     * Todo - configurable time zones and locales.
     */
    private static final DateTimeFormatter FORMATTER = ISODateTimeFormat.dateTimeNoMillis().withLocale(Locale.ENGLISH).withZoneUTC();

    @Override
    public FieldValueProvider decode(JsonNode value, DecoderColumnHandle columnHandle)
    {
        requireNonNull(columnHandle, "columnHandle is null");
        requireNonNull(value, "value is null");

        return new SecondsSinceEpochJsonValueProvider(value, columnHandle);
    }

    public static class SecondsSinceEpochJsonValueProvider
            extends AbstractDateTimeJsonValueProvider
    {
        public SecondsSinceEpochJsonValueProvider(JsonNode value, DecoderColumnHandle columnHandle)
        {
            super(value, columnHandle);
        }

        @Override
        protected long getMillis()
        {
            return value.asLong() * 1000L;
        }

        @Override
        public Slice getSlice()
        {
            return utf8Slice(FORMATTER.print(value.asLong() * 1000L));
        }
    }
}
