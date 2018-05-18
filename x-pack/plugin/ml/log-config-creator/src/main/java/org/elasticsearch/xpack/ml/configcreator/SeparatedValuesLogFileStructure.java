/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.Terminal.Verbosity;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.xpack.ml.configcreator.TimestampFormatFinder.TimestampMatch;
import org.supercsv.exception.SuperCsvException;
import org.supercsv.io.CsvListReader;
import org.supercsv.io.CsvMapReader;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SeparatedValuesLogFileStructure extends AbstractStructuredLogFileStructure implements LogFileStructure {

    private static final int MAX_LEVENSHTEIN_COMPARISONS = 100;

    private static final String FILEBEAT_EXCLUDE_LINES_TEMPLATE = "  exclude_lines: ['^%s']\n";
    private static final String FILEBEAT_MULTILINE_CONFIG_TEMPLATE = "  multiline.pattern: '%s'\n" +
        "  multiline.negate: true\n" +
        "  multiline.match: after\n";
    private static final String FILEBEAT_TO_LOGSTASH_TEMPLATE = "filebeat.inputs:\n" +
        "- type: log\n" +
        "  paths:\n" +
        "   - '%s'\n" +
        "%s" +
        "%s" +
        "\n" +
        "output.logstash:\n" +
        "  hosts: [\"localhost:5044\"]\n";
    private static final String SEPARATOR_TEMPLATE = "    separator => \"%c\"\n";
    private static final String LOGSTASH_CONVERSIONS_TEMPLATE = "    convert => {\n" +
        "%s" +
        "    }\n";
    private static final String LOGSTASH_FROM_FILEBEAT_TEMPLATE = "input {\n" +
        "  beats {\n" +
        "    port => 5044\n" +
        "    host => \"0.0.0.0\"\n" +
        "  }\n" +
        "}\n" +
        "\n" +
        "filter {\n" +
        "  csv {\n" +
        "%s" +
        "    columns => [ %s ]\n" +
        "%s" +
        "    remove_field => [ \"message\" ]\n" +
        "  }\n" +
        "%s" +
        "}\n" +
        "\n" +
        "output {\n" +
        "  elasticsearch {\n" +
        "    hosts => localhost\n" +
        "    manage_template => false\n" +
        "    index => \"%%{[@metadata][beat]}-%%{[@metadata][version]}-%%{+YYYY.MM.dd}\"\n" +
        "  }\n" +
        "}\n";
    private static final String LOGSTASH_FROM_STDIN_TEMPLATE = "input {\n" +
        "  stdin {}\n" +
        "}\n" +
        "\n" +
        "filter {\n" +
        "  csv {\n" +
        "%s" +
        "    columns => [ %s ]\n" +
        "%s" +
        "%s" +
        "    remove_field => [ \"message\" ]\n" +
        "  }\n" +
        "%s" +
        "}\n" +
        "\n" +
        "output {\n" +
        "  elasticsearch {\n" +
        "    hosts => localhost\n" +
        "    manage_template => false\n" +
        "    index => \"%s\"\n" +
        "    document_type => \"_doc\"\n" +
        "  }\n" +
        "}\n";

    private final CsvPreference csvPreference;
    private final boolean isCsvHeaderInFile;
    private final String[] csvHeader;
    private final List<Map<String, ?>> sampleRecords;
    private SortedMap<String, String> mappings;
    private String filebeatToLogstashConfig;
    private String logstashFromFilebeatConfig;
    private String logstashFromStdinConfig;

    SeparatedValuesLogFileStructure(Terminal terminal, String sampleFileName, String indexName, String typeName, String sample,
                                    CsvPreference csvPreference) throws IOException {
        super(terminal, sampleFileName, indexName, typeName);
        this.csvPreference = Objects.requireNonNull(csvPreference);
        Tuple<Boolean, String[]> headerInfo = findHeaderFromSample(terminal, sample, csvPreference);
        isCsvHeaderInFile = headerInfo.v1();
        csvHeader = headerInfo.v2();
        try (CsvMapReader csvReader = new CsvMapReader(new StringReader(sample), csvPreference)) {
            if (isCsvHeaderInFile) {
                csvReader.getHeader(false);
            }
            sampleRecords = new ArrayList<>();
            try {
                Map<String, String> sampleRecord;
                while ((sampleRecord = csvReader.read(csvHeader)) != null) {
                    sampleRecords.add(sampleRecord);
                }
            } catch (SuperCsvException e) {
                // Tolerate an incomplete last record as long as we have one complete record
                if (sampleRecords.isEmpty() || notUnexpectedEndOfFile(e)) {
                    throw e;
                }
            }
        }
    }

    static Tuple<Boolean, String[]> findHeaderFromSample(Terminal terminal, String sample, CsvPreference csvPreference) throws IOException {

        boolean isCsvHeaderInFile = true;
        List<List<String>> rows = new ArrayList<>();
        try (CsvListReader csvReader = new CsvListReader(new StringReader(sample), csvPreference)) {

            try {
                List<String> row;
                while ((row = csvReader.read()) != null) {
                    rows.add(row);
                }
            } catch (SuperCsvException e) {
                // Tolerate an incomplete last row
                if (notUnexpectedEndOfFile(e)) {
                    throw e;
                }
            }
        }

        List<String> firstRow = rows.get(0);
        if (firstRow.size() != rows.get(rows.size() - 1).size()) {
            rows.remove(rows.size() - 1);
        }

        if (rows.size() < 3) {
            terminal.println(Verbosity.VERBOSE, "Too little data to accurately assess whether header is in sample - guessing it is");
        } else {
            isCsvHeaderInFile = isFirstRowUnusual(terminal, rows);
        }

        if (isCsvHeaderInFile) {
            return new Tuple<>(true, firstRow.toArray(new String[firstRow.size()]));
        } else {
            return new Tuple<>(false, IntStream.rangeClosed(1, firstRow.size()).mapToObj(num -> "column" + num).toArray(String[]::new));
        }
    }

    private static boolean isFirstRowUnusual(Terminal terminal, List<List<String>> rows) {

        assert rows.size() >= 3;

        String firstRow = String.join("", rows.get(0));
        List<String> otherRows = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            otherRows.add(String.join("", row));
        }

        // Check lengths

        double firstRowLength = firstRow.length();
        DoubleSummaryStatistics otherRowStats = otherRows.stream().mapToDouble(otherRow -> (double) otherRow.length())
            .collect(DoubleSummaryStatistics::new, DoubleSummaryStatistics::accept, DoubleSummaryStatistics::combine);

        double otherLengthRange = otherRowStats.getMax() - otherRowStats.getMin();
        if (firstRowLength < otherRowStats.getMin() - otherLengthRange / 10.0 ||
            firstRowLength > otherRowStats.getMax() + otherLengthRange / 10.0) {
            terminal.println(Verbosity.VERBOSE, "First row is unusual based on length test: [" + firstRowLength + "] and [" +
                toNiceString(otherRowStats) + "]");
            return true;
        }

        terminal.println(Verbosity.VERBOSE, "First row is not unusual based on length test: [" + firstRowLength + "] and [" +
            toNiceString(otherRowStats) + "]");

        // Check edit distances

        DoubleSummaryStatistics firstRowStats = otherRows.stream().limit(MAX_LEVENSHTEIN_COMPARISONS)
            .mapToDouble(otherRow -> (double) levenshteinDistance(firstRow, otherRow))
            .collect(DoubleSummaryStatistics::new, DoubleSummaryStatistics::accept, DoubleSummaryStatistics::combine);

        otherRowStats = new DoubleSummaryStatistics();
        int numComparisons = 0;
        for (int i = 0; numComparisons < MAX_LEVENSHTEIN_COMPARISONS && i < otherRows.size(); ++i) {
            for (int j = i + 1; numComparisons < MAX_LEVENSHTEIN_COMPARISONS && j < otherRows.size(); ++j) {
                otherRowStats.accept((double) levenshteinDistance(otherRows.get(i), otherRows.get(j)));
                ++numComparisons;
            }
        }

        if (firstRowStats.getAverage() > otherRowStats.getAverage() * 1.2) {
            terminal.println(Verbosity.VERBOSE, "First row is unusual based on Levenshtein test [" + toNiceString(firstRowStats) +
                "] and [" + toNiceString(otherRowStats) + "]");
            return true;
        }

        terminal.println(Verbosity.VERBOSE, "First row is not unusual based on Levenshtein test [" + toNiceString(firstRowStats) +
            "] and [" + toNiceString(otherRowStats) + "]");

        return false;
    }

    private static String toNiceString(DoubleSummaryStatistics stats) {
        return String.format(Locale.ROOT, "count=%d, min=%f, average=%f, max=%f", stats.getCount(), stats.getMin(), stats.getAverage(),
            stats.getMax());
    }

    /**
     * This method implements the simple algorithm for calculating Levenshtein distance.
     */
    static int levenshteinDistance(String first, String second) {

        // There are some examples with pretty pictures of the matrix on Wikipedia here:
        // http://en.wikipedia.org/wiki/Levenshtein_distance

        int firstLen = first.length();
        int secondLen = second.length();
        if (firstLen == 0) {
            return secondLen;
        }
        if (secondLen == 0) {
            return firstLen;
        }

        int[] currentCol = new int[secondLen + 1];
        int[] prevCol = new int[secondLen + 1];

        // Populate the left column
        for (int down = 0; down <= secondLen; ++down) {
            currentCol[down] = down;
        }

        // Calculate the other entries in the matrix
        for (int across = 1; across <= firstLen; ++across) {
            int[] tmp = prevCol;
            prevCol = currentCol;
            // We could allocate a new array for currentCol here, but it's more efficient to reuse the one that's now redundant
            currentCol = tmp;

            currentCol[0] = across;

            for (int down = 1; down <= secondLen; ++down) {

                // Do the strings differ at the point we've reached?
                if (first.charAt(across - 1) == second.charAt(down - 1)) {

                    // No, they're the same => no extra cost
                    currentCol[down] = prevCol[down - 1];
                } else {
                    // Yes, they differ, so there are 3 options:

                    // 1) Deletion => cell to the left's value plus 1
                    int option1 = prevCol[down];

                    // 2) Insertion => cell above's value plus 1
                    int option2 = currentCol[down - 1];

                    // 3) Substitution => cell above left's value plus 1
                    int option3 = prevCol[down - 1];

                    // Take the cheapest option of the 3
                    currentCol[down] = Math.min(Math.min(option1, option2), option3) + 1;
                }
            }
        }

        // Result is the value in the bottom right hand corner of the matrix
        return currentCol[secondLen];
    }

    static boolean canCreateFromSample(Terminal terminal, String sample, CsvPreference csvPreference, String formatName) {
        try (CsvListReader csvReader = new CsvListReader(new StringReader(sample), csvPreference)) {

            int fieldsInFirstRow = -1;
            int fieldsInLastRow = -1;

            int numberOfRows = 0;
            try {
                List<String> row;
                while ((row = csvReader.read()) != null) {

                    ++numberOfRows;
                    if (fieldsInFirstRow < 0) {
                        fieldsInFirstRow = row.size();
                        if (fieldsInFirstRow <= 1) {
                            terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because the first row has fewer than 2 fields: [" +
                                fieldsInFirstRow + "]");
                            return false;
                        }
                        fieldsInLastRow = fieldsInFirstRow;
                        continue;
                    }

                    if (fieldsInLastRow != fieldsInFirstRow) {
                        terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because row [" + (numberOfRows - 1) +
                            "] has a different number of fields to the first row: [" + fieldsInFirstRow + "] and [" +
                            fieldsInLastRow + "]");
                        return false;
                    }

                    fieldsInLastRow = row.size();
                }

                if (fieldsInLastRow > fieldsInFirstRow) {
                    terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because last row has more fields than first row: [" +
                        fieldsInFirstRow + "] and [" + fieldsInLastRow + "]");
                    return false;
                }
                if (fieldsInLastRow < fieldsInFirstRow) {
                    --numberOfRows;
                }
            } catch (SuperCsvException e) {
                // Tolerate an incomplete last row
                if (notUnexpectedEndOfFile(e)) {
                    terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because there was a parsing exception: [" +
                        e.getMessage() + "]");
                    return false;
                }
            }
            if (numberOfRows <= 1) {
                terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because fewer than 2 complete records in sample: [" +
                    numberOfRows + "]");
                return false;
            }
            return true;

        } catch (IOException e) {
            terminal.println(Verbosity.VERBOSE, "Not " + formatName + " because there was a parsing exception: [" + e.getMessage() + "]");
            return false;
        }
    }

    private static boolean notUnexpectedEndOfFile(SuperCsvException e) {
        return e.getMessage().startsWith("unexpected end of file while reading quoted column") == false;
    }

    void createConfigs() {
        Tuple<String, TimestampMatch> timeField = guessTimestampField(sampleRecords);
        mappings = guessMappings(sampleRecords);

        char delimiter = (char) csvPreference.getDelimiterChar();
        String timeLineRegex = null;
        if (timeField != null) {
            StringBuilder builder = new StringBuilder("^");
            for (String column : csvHeader) {
                if (timeField.v1().equals(column)) {
                    timeLineRegex = builder.append(timeField.v2().simplePattern.pattern()).toString();
                    break;
                } else {
                    builder.append(".*?");
                    if (delimiter == '\t') {
                        builder.append("\\t");
                    } else {
                        builder.append(delimiter);
                    }
                }
            }
        }

        String filebeatExcludeLinesConfig = "";
        if (isCsvHeaderInFile) {
            String filebeatColumns = Arrays.stream(csvHeader)
                .map(column -> "\"?" + column.replace("\"", "\"\"").replaceAll("([\\\\|()\\[\\]{}^$*?])", "\\\\$1") + "\"?")
                .collect(Collectors.joining(","));
            filebeatExcludeLinesConfig = String.format(Locale.ROOT, FILEBEAT_EXCLUDE_LINES_TEMPLATE, filebeatColumns);
        }
        String filebeatMultilineConfig = "";
        String logstashDateFilter = "";
        if (timeLineRegex != null) {
            filebeatMultilineConfig = String.format(Locale.ROOT, FILEBEAT_MULTILINE_CONFIG_TEMPLATE, timeLineRegex);
            logstashDateFilter = makeLogstashDateFilter(timeField.v1(), timeField.v2().dateFormat);
        }

        filebeatToLogstashConfig = String.format(Locale.ROOT, FILEBEAT_TO_LOGSTASH_TEMPLATE, sampleFileName, filebeatExcludeLinesConfig,
            filebeatMultilineConfig);
        String logstashColumns = Arrays.stream(csvHeader)
            .map(column -> (column.indexOf('"') >= 0) ? ("'" + column + "'") : ("\"" + column + "\"")).collect(Collectors.joining(", "));
        String separatorIfRequired = (delimiter == ',') ? "" : String.format(Locale.ROOT, SEPARATOR_TEMPLATE, delimiter);
        String logStashColumnConversions = makeColumnConversions(mappings);
        logstashFromFilebeatConfig = String.format(Locale.ROOT, LOGSTASH_FROM_FILEBEAT_TEMPLATE, separatorIfRequired, logstashColumns,
            logStashColumnConversions, logstashDateFilter);
        String skipHeaderIfRequired = isCsvHeaderInFile ? "    skip_header => true\n": "";
        logstashFromStdinConfig = String.format(Locale.ROOT, LOGSTASH_FROM_STDIN_TEMPLATE, separatorIfRequired, logstashColumns,
            skipHeaderIfRequired, logStashColumnConversions, logstashDateFilter, indexName);
    }

    String getFilebeatToLogstashConfig() {
        return filebeatToLogstashConfig;
    }

    String getLogstashFromFilebeatConfig() {
        return logstashFromFilebeatConfig;
    }

    String getLogstashFromStdinConfig() {
        return logstashFromStdinConfig;
    }

    @Override
    public synchronized void writeConfigs(Path directory) throws IOException {
        if (mappings == null) {
            createConfigs();
        }

        writeConfigFile(directory, "filebeat-to-logstash.yml", filebeatToLogstashConfig);
        writeConfigFile(directory, "logstash-from-filebeat.conf", logstashFromFilebeatConfig);
        writeConfigFile(directory, "logstash-from-stdin.conf", logstashFromStdinConfig);

        writeMappingsConfigs(directory, mappings);
    }

    static String makeColumnConversions(Map<String, String> mappings) {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {

            String convertTo = null;
            switch (mapping.getValue()) {

                case "boolean":
                    convertTo = "boolean";
                    break;
                case "byte":
                case "short":
                case "integer":
                case "long":
                    convertTo = "integer";
                    break;
                case "half_float":
                case "float":
                case "double":
                    convertTo = "float";
                    break;
            }

            if (convertTo != null) {
                String fieldQuote = bestLogstashQuoteFor(mapping.getKey());
                builder.append("      ").append(fieldQuote).append(mapping.getKey()).append(fieldQuote)
                    .append(" => \"").append(convertTo).append("\"\n");
            }
        }

        return (builder.length() > 0) ? String.format(Locale.ROOT, LOGSTASH_CONVERSIONS_TEMPLATE, builder.toString()) : "";
    }
}
