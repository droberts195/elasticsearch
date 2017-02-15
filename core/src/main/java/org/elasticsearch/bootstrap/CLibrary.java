/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import org.apache.lucene.util.Constants;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.io.PathUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detect non-standard C libraries.
 * Currently only works on Linux (and probably only ever needs to).
 */
public final class CLibrary {

    private static final String DEFAULT_C_LIBRARY = Constants.LINUX ? checkDirectories("/lib64", "/lib") : "";

    private CLibrary() {
    }

    /**
     * @return an empty string if the C library is the default; otherwise the name of the library.
     */
    public static String getDefaultCLibrary() {
        return DEFAULT_C_LIBRARY;
    }

    /**
     * Check the supplied directories for evidence of a non-standard default C library.
     * @return an empty string if the C library is the default; otherwise the name of the library.
     */
    @SuppressForbidden(reason = "Need to read OS library directories")
    private static String checkDirectories(String... directories) {
        for (String directory : directories) {
            String cLibrary = checkDirectory(PathUtils.get(directory));
            if (cLibrary.isEmpty() == false) {
                return cLibrary;
            }
        }

        // Assume default C library
        return "";
    }

    /**
     * Check the supplied directory for evidence of a non-standard default C library.
     * On Linux "standard" is taken to mean glibc.
     * @return an empty string if the C library is the default; otherwise the name of the library.
     */
    static String checkDirectory(Path directory) {
        // Search for glibc's dynamic linker - do this first as it's the most standard and hence preferred
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "ld-linux*.so*")) {
            for (Path library : stream) {
                // This is the default, so return an empty string
                return "";
            }
        } catch (IOException e) {
            // Ignore it
        }

        // Search for musl's dynamic linker
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "ld-musl*.so*")) {
            for (Path library : stream) {
                return "musl";
            }
        } catch (IOException e) {
            // Ignore it
        }

        // Search for uClibc's dynamic linker
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "ld-uClibc*.so*")) {
            for (Path library : stream) {
                return "uclibc";
            }
        } catch (IOException e) {
            // Ignore it
        }

        // Assume default C library
        return "";
    }
}
