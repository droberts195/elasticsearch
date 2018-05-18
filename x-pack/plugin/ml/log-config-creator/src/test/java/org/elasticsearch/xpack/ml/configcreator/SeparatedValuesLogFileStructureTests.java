/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.common.collect.Tuple;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.elasticsearch.xpack.ml.configcreator.SeparatedValuesLogFileStructure.levenshteinDistance;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;

public class SeparatedValuesLogFileStructureTests extends LogConfigCreatorTestCase {

    private LogFileStructureFactory factory = new CsvLogFileStructureFactory(TEST_TERMINAL);

    public void testCreateConfigsGivenCompleteCsv() throws Exception {
        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(factory.canCreateFromSample(sample));
        SeparatedValuesLogFileStructure structure = (SeparatedValuesLogFileStructure) factory.createFromSample(TEST_FILE_NAME,
            TEST_INDEX_NAME, "time_message", sample);
        structure.createConfigs();
        assertThat(structure.getFilebeatToLogstashConfig(), containsString("exclude_lines: ['^\"?time\"?,\"?message\"?']\n"));
        assertThat(structure.getFilebeatToLogstashConfig(),
            containsString("multiline.pattern: '^\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}'\n"));
        assertThat(structure.getLogstashFromFilebeatConfig(), containsString("match => [ \"time\", \"ISO8601\" ]\n"));
        assertThat(structure.getLogstashFromFilebeatConfig(), containsString("columns => [ \"time\", \"message\" ]\n"));
        assertThat(structure.getLogstashFromStdinConfig(), containsString("match => [ \"time\", \"ISO8601\" ]\n"));
    }

    public void testCreateConfigsGivenCsvWithIncompleteLastRecord() throws Exception {
        String sample = "message,time\n" +
            "\"hello\n" +
            "world\",2018-05-17T13:41:23\n" +
            "\"hello again\n"; // note that this last record is truncated
        assertTrue(factory.canCreateFromSample(sample));
        SeparatedValuesLogFileStructure structure = (SeparatedValuesLogFileStructure) factory.createFromSample(TEST_FILE_NAME,
            TEST_INDEX_NAME, "message_time", sample);
        structure.createConfigs();
        assertThat(structure.getFilebeatToLogstashConfig(), containsString("exclude_lines: ['^\"?message\"?,\"?time\"?']\n"));
        assertThat(structure.getFilebeatToLogstashConfig(),
            containsString("multiline.pattern: '^.*?,\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}'\n"));
        assertThat(structure.getLogstashFromFilebeatConfig(), containsString("match => [ \"time\", \"ISO8601\" ]\n"));
        assertThat(structure.getLogstashFromFilebeatConfig(), containsString("columns => [ \"message\", \"time\" ]\n"));
        assertThat(structure.getLogstashFromStdinConfig(), containsString("match => [ \"time\", \"ISO8601\" ]\n"));
    }

    public void testFindHeaderFromSampleGivenHeaderInSample() throws IOException {
        String withHeader = "time,airline,responsetime,sourcetype\n" +
            "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = SeparatedValuesLogFileStructure.findHeaderFromSample(TEST_TERMINAL, withHeader,
            CsvPreference.EXCEL_PREFERENCE);

        assertTrue(header.v1());
        assertThat(header.v2(), arrayContaining("time", "airline", "responsetime", "sourcetype"));
    }

    public void testFindHeaderFromSampleGivenHeaderNotInSample() throws IOException {
        String withoutHeader = "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = SeparatedValuesLogFileStructure.findHeaderFromSample(TEST_TERMINAL, withoutHeader,
            CsvPreference.EXCEL_PREFERENCE);

        assertFalse(header.v1());
        assertThat(header.v2(), arrayContaining("column1", "column2", "column3", "column4"));
    }

    public void testLevenshteinDistance() {

        assertEquals(0, levenshteinDistance("cat", "cat"));
        assertEquals(3, levenshteinDistance("cat", "dog"));
        assertEquals(5, levenshteinDistance("cat", "mouse"));
        assertEquals(3, levenshteinDistance("cat", ""));

        assertEquals(3, levenshteinDistance("dog", "cat"));
        assertEquals(0, levenshteinDistance("dog", "dog"));
        assertEquals(4, levenshteinDistance("dog", "mouse"));
        assertEquals(3, levenshteinDistance("dog", ""));

        assertEquals(5, levenshteinDistance("mouse", "cat"));
        assertEquals(4, levenshteinDistance("mouse", "dog"));
        assertEquals(0, levenshteinDistance("mouse", "mouse"));
        assertEquals(5, levenshteinDistance("mouse", ""));

        assertEquals(3, levenshteinDistance("", "cat"));
        assertEquals(3, levenshteinDistance("", "dog"));
        assertEquals(5, levenshteinDistance("", "mouse"));
        assertEquals(0, levenshteinDistance("", ""));
    }

    public void testMakeColumnConversions() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("f1", "long");
        mappings.put("f2", "date");
        mappings.put("f3", "text");
        mappings.put("f4", "keyword");
        mappings.put("f5", "double");
        mappings.put("f6", "long");
        mappings.put("f7", "boolean");
        mappings.put("f8", "keyword");
        String conversions = SeparatedValuesLogFileStructure.makeColumnConversions(mappings);
        assertEquals("    convert => {\n" +
            "      \"f1\" => \"integer\"\n" +
            "      \"f5\" => \"float\"\n" +
            "      \"f6\" => \"integer\"\n" +
            "      \"f7\" => \"boolean\"\n" +
            "    }\n", conversions);
    }
}
