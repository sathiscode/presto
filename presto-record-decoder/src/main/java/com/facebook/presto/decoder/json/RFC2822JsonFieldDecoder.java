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
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * RFC 2822 date format decoder.
 * <p/>
 * Uses hardcoded UTC timezone and english locale.
 */
public class RFC2822JsonFieldDecoder
        implements JsonFieldDecoder
{
    /**
     * Todo - configurable time zones and locales.
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormat.forPattern("EEE MMM dd HH:mm:ss Z yyyy").withLocale(Locale.ENGLISH).withZoneUTC();

    private final DecoderColumnHandle columnHandle;

    public RFC2822JsonFieldDecoder(DecoderColumnHandle columnHandle)
    {
        this.columnHandle = requireNonNull(columnHandle, "columnHandle is null");
    }

    @Override
    public FieldValueProvider decode(JsonNode value)
    {
        return new RFC2822JsonValueProvider(value, columnHandle);
    }

    public static class RFC2822JsonValueProvider
            extends AbstractDateTimeJsonValueProvider
    {
        public RFC2822JsonValueProvider(JsonNode value, DecoderColumnHandle columnHandle)
        {
            super(value, columnHandle);
        }

        @Override
        protected long getMillis()
        {
            if (value.canConvertToLong()) {
                return value.asLong();
            }

            String textValue = value.isValueNode() ? value.asText() : value.toString();
            return FORMATTER.parseMillis(textValue);
        }
    }
}
