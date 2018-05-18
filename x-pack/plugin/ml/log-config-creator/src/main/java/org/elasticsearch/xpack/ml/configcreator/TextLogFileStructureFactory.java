/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.Terminal.Verbosity;

import java.io.IOException;

public class TextLogFileStructureFactory implements LogFileStructureFactory {

    private final Terminal terminal;
    private final BeatsModuleStore beatsModuleStore;

    public TextLogFileStructureFactory(Terminal terminal, BeatsModuleStore beatsModuleStore) throws IOException {
        this.terminal = terminal;
        this.beatsModuleStore = beatsModuleStore;
    }

    /**
     * This format matches if the sample contains at least one newline and at least one non-newline character
     */
    public boolean canCreateFromSample(String sample) {
        if (sample.indexOf('\n') < 0) {
            terminal.println(Verbosity.VERBOSE, "Not text because sample contains no newlines");
            return false;
        }
        if (sample.replace("\n", "").isEmpty()) {
            terminal.println(Verbosity.VERBOSE, "Not text because sample contains only newlines");
            return false;
        }
        return true;
    }

    public LogFileStructure createFromSample(String sampleFileName, String indexName, String typeName, String sample) {
        return new TextLogFileStructure(terminal, beatsModuleStore, sampleFileName, indexName, typeName, sample);
    }
}
