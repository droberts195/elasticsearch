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

import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uses a temporary directory to simulate the Linux system library directory.
 */
public class CLibraryTests extends ESTestCase {

    static byte[] dummyContent = new byte[1];

    public void testGlibc() throws IOException {
        Path libDir = createTempDir().resolve("lib");
        Files.createDirectories(libDir);
        Files.write(libDir.resolve("ld-linux-x86_64.so.2"), dummyContent);

        assertEquals("", CLibrary.checkDirectory(libDir));
    }

    public void testMusl() throws IOException {
        Path libDir = createTempDir().resolve("lib");
        Files.createDirectories(libDir);
        Files.write(libDir.resolve("ld-musl-x86_64.so.1"), dummyContent);

        assertEquals("musl", CLibrary.checkDirectory(libDir));
    }

    public void testUclibc() throws IOException {
        Path libDir = createTempDir().resolve("lib");
        Files.createDirectories(libDir);
        Files.write(libDir.resolve("ld-uClibc-0.9.28.so"), dummyContent);

        assertEquals("uclibc", CLibrary.checkDirectory(libDir));
    }

    public void testGlibcPrecedence() throws IOException {
        Path libDir = createTempDir().resolve("lib");
        Files.createDirectories(libDir);
        Files.write(libDir.resolve("ld-uClibc-0.9.28.so"), dummyContent);
        Files.write(libDir.resolve("ld-linux-x86_64.so.2"), dummyContent);
        Files.write(libDir.resolve("ld-musl-x86_64.so.1"), dummyContent);

        assertEquals("", CLibrary.checkDirectory(libDir));
    }

    public void testEmptyDir() throws IOException {
        Path libDir = createTempDir().resolve("lib");
        Files.createDirectories(libDir);

        assertEquals("", CLibrary.checkDirectory(libDir));
    }

    public void testWrongDir() throws IOException {
        Path libDir = createTempDir().resolve("lib");

        assertEquals("", CLibrary.checkDirectory(libDir));
    }
}
