/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.Terminal.Verbosity;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.grok.Grok;
import org.elasticsearch.xpack.ml.configcreator.TimestampFormatFinder.TimestampMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractStructuredLogFileStructure extends AbstractLogFileStructure {

    private static final String LOGSTASH_DATE_COPY_TEMPLATE = "  mutate {\n" +
        "    copy => {\n" +
        "      \"" + DEFAULT_TIMESTAMP_FIELD + "\" => %s%s%s \n" +
        "    }\n" +
        "  }\n";
    private static final String LOGSTASH_DATE_FILTER_TEMPLATE = "  date {\n" +
        "    match => [ %s%s%s, \"%s\" ]\n" +
        "  }\n" +
        "%s";

    // NUMBER Grok pattern doesn't support scientific notation, so we extend it
    private static final Grok NUMBER_GROK = new Grok(Grok.getBuiltinPatterns(), "^%{NUMBER}(?:[eE][+-]?[0-3]?[0-9]{1,2})?$");
    private static final Grok IP_GROK = new Grok(Grok.getBuiltinPatterns(), "^%{IP}$");
    private static final int KEYWORD_MAX_LEN = 256;
    private static final int KEYWORD_MAX_SPACES = 5;

    protected AbstractStructuredLogFileStructure(Terminal terminal, String sampleFileName, String indexName, String typeName) {
        super(terminal, sampleFileName, indexName, typeName);
    }

    protected Tuple<String, TimestampMatch> guessTimestampField(List<Map<String, ?>> sampleRecords) {
        if (sampleRecords == null || sampleRecords.isEmpty()) {
            return null;
        }

        List<Tuple<String, TimestampMatch>> firstSampleMatches = new ArrayList<>();

        // Get candidate timestamps from the first sample record
        for (Map.Entry<String, ?> entry : sampleRecords.get(0).entrySet()) {
            Object value = entry.getValue();
            if (value != null) {
                TimestampMatch match = TimestampFormatFinder.findFirstMatch(value.toString());
                if (match != null) {
                    Tuple<String, TimestampMatch> firstSampleMatch = new Tuple<>(entry.getKey(), match);
                    // If there's only one sample then the first match is the time field
                    if (sampleRecords.size() == 1) {
                        return firstSampleMatch;
                    }
                    firstSampleMatches.add(firstSampleMatch);
                    terminal.println(Verbosity.VERBOSE, "First sample timestamp match [" + firstSampleMatch + "]");
                }
            }
        }

        // Accept the first match from the first sample that is compatible with all the other samples
        for (Tuple<String, TimestampMatch> firstSampleMatch : firstSampleMatches) {

            boolean allGood = true;
            for (Map<String, ?> sampleRecord : sampleRecords.subList(1, sampleRecords.size())) {
                Object fieldValue = sampleRecord.get(firstSampleMatch.v1());
                if (fieldValue == null) {
                    terminal.println(Verbosity.VERBOSE, "First sample match [" + firstSampleMatch.v1() + "] ruled out because record [" +
                        sampleRecord + "] doesn't have field");
                    allGood = false;
                    break;
                }

                TimestampMatch match = TimestampFormatFinder.findFirstFullMatch(fieldValue.toString());
                if (match == null || match.candidateIndex != firstSampleMatch.v2().candidateIndex) {
                    terminal.println(Verbosity.VERBOSE, "First sample match [" + firstSampleMatch.v1() + "] ruled out because record [" +
                        sampleRecord + "] matches differently: [" + match + "]");
                    allGood = false;
                    break;
                }
            }

            if (allGood) {
                return firstSampleMatch;
            }
        }

        return null;
    }

    protected String makeLogstashDateFilter(String timeFieldName, String dateFormat) {

        String fieldQuote = bestLogstashQuoteFor(timeFieldName);
        String copyFilter = DEFAULT_TIMESTAMP_FIELD.equals(timeFieldName) ? "" :
            String.format(Locale.ROOT, LOGSTASH_DATE_COPY_TEMPLATE, fieldQuote, timeFieldName, fieldQuote);
        return String.format(Locale.ROOT, LOGSTASH_DATE_FILTER_TEMPLATE, fieldQuote, timeFieldName, fieldQuote, dateFormat, copyFilter);
    }

    protected SortedMap<String, String> guessMappings(List<Map<String, ?>> sampleRecords) {

        SortedMap<String, String> mappings = new TreeMap<>();

        if (sampleRecords != null) {

            for (Map<String, ?> sampleRecord : sampleRecords) {
                for (String fieldName : sampleRecord.keySet()) {
                    mappings.computeIfAbsent(fieldName, key -> guessMapping(fieldName, sampleRecords.stream().flatMap(record -> {
                        Object fieldValue = record.get(fieldName);
                        return (fieldValue == null) ? Stream.empty() : Stream.of(fieldValue);
                    }).collect(Collectors.toList())));
                }
            }
        }

        mappings.put(DEFAULT_TIMESTAMP_FIELD, "date");
        return mappings;
    }

    String guessMapping(String fieldName, List<Object> fieldValues) {

        assert fieldValues != null && fieldValues.isEmpty() == false;

        if (fieldValues.stream().anyMatch(value -> value instanceof Map)) {
            if (fieldValues.stream().allMatch(value -> value instanceof Map)) {
                return "object";
            }
            throw new RuntimeException("Field [" + fieldName +
                "] has both object and non-object values - this won't work with Elasticsearch");
        }

        if (fieldValues.stream().anyMatch(value -> value instanceof List || value instanceof Object[])) {
            // Elasticsearch fields can be either arrays or single values, but array values must all have the same type
            return guessMapping(fieldName,
                fieldValues.stream().flatMap(AbstractStructuredLogFileStructure::flatten).collect(Collectors.toList()));
        }

        if (fieldValues.stream().allMatch(value -> "true".equals(value.toString()) || "false".equals(value.toString()))) {
            return "boolean";
        }

        Set<TimestampMatch> timestampMatches =
            fieldValues.stream().map(value -> TimestampFormatFinder.findFirstMatch(value.toString())).collect(Collectors.toSet());
        if (timestampMatches.size() == 1 && timestampMatches.iterator().next() != null) {
            return "date";
        }

        if (fieldValues.stream().allMatch(value -> NUMBER_GROK.match(value.toString()))) {
            try {
                fieldValues.forEach(value -> Long.parseLong(value.toString()));
                return "long";
            } catch (NumberFormatException e) {
                terminal.println(Verbosity.VERBOSE,
                    "Rejecting type 'long' for field [" + fieldName + "] due to parse failure: [" + e.getMessage() + "]");
            }
            try {
                fieldValues.forEach(value -> Double.parseDouble(value.toString()));
                return "double";
            } catch (NumberFormatException e) {
                terminal.println(Verbosity.VERBOSE,
                    "Rejecting type 'double' for field [" + fieldName + "] due to parse failure: [" + e.getMessage() + "]");
            }
        }

        else if (fieldValues.stream().allMatch(value -> IP_GROK.match(value.toString()))) {
            return "ip";
        }

        if (fieldValues.stream().anyMatch(value -> isMoreLikelyTextThanKeyword(value.toString()))) {
            return "text";
        }

        return "keyword";
    }

    boolean isMoreLikelyTextThanKeyword(String str) {
        int length = str.length();
        return length > KEYWORD_MAX_LEN || length - str.replaceAll("\\s", "").length() > KEYWORD_MAX_SPACES;
    }

    private static Stream<Object> flatten(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> objectList = (List<Object>) value;
            return objectList.stream();
        } else if (value instanceof Object[]) {
            return Arrays.stream((Object[]) value);
        } else {
            return Stream.of(value);
        }
    }
}
