/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.cli.Terminal;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;

public class TsvLogFileStructureFactory implements LogFileStructureFactory {

    private final Terminal terminal;

    public TsvLogFileStructureFactory(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * Rules are:
     * - The file must be valid TSV
     * - It must contain at least two complete records
     * - There must be at least two fields per record (otherwise files with no commas could be treated as TSV!)
     * - Every TSV record except the last must have the same number of fields
     * The reason the last record is allowed to have fewer fields than the others is that
     * it could have been truncated when the file was sampled.
     */
    public boolean canCreateFromSample(String sample) {
        return SeparatedValuesLogFileStructure.canCreateFromSample(terminal, sample, CsvPreference.TAB_PREFERENCE, "TSV");
    }

    public LogFileStructure createFromSample(String sampleFileName, String indexName, String typeName, String sample) throws IOException {
        return new SeparatedValuesLogFileStructure(terminal, sampleFileName, indexName, typeName, sample, CsvPreference.TAB_PREFERENCE);
    }
}
