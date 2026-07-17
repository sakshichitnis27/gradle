/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.launcher.daemon.client;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.serialize.FlushableEncoder;
import org.gradle.internal.serialize.kryo.KryoBackedEncoder;
import org.gradle.internal.stream.EncodedStream;
import org.gradle.launcher.daemon.bootstrap.DaemonStartupCommunication;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.daemon.diagnostics.DaemonStartupInfo;
import org.gradle.launcher.daemon.registry.DaemonDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Scanner;

/**
 * Handles the bootstrap protocol between the client and the daemon. Performs an
 * initial handshake with a new daemon, exchanging startup information and receiving
 * just enough resulting data to open a proper messaging channel with the daemon.
 */
public class DaemonGreeter {

    private final static Logger LOGGER = Logging.getLogger(DaemonGreeter.class);

    /**
     * Welcome the daemon into this world. Graciously greet it by serializing the given parameters
     * and writing them to the given output stream.
     */
    public static void greetDaemon(
        OutputStream os,
        DaemonDir daemonDir,
        DaemonParameters daemonParameters,
        boolean singleUse,
        String daemonUid,
        Collection<String> daemonOpts
    ) {
        FlushableEncoder encoder = new KryoBackedEncoder(new EncodedStream.EncodedOutput(os));
        try {
            encoder.writeString(daemonParameters.getGradleUserHomeDir().getAbsolutePath());
            encoder.writeString(daemonDir.getBaseDir().getAbsolutePath());
            encoder.writeSmallInt(daemonParameters.getIdleTimeout());
            encoder.writeSmallInt(daemonParameters.getPeriodicCheckInterval());
            encoder.writeBoolean(singleUse);
            encoder.writeSmallInt(daemonParameters.getNativeServicesMode().ordinal());
            encoder.writeString(daemonUid);
            encoder.writeSmallInt(daemonParameters.getPriority().ordinal());
            encoder.writeSmallInt(daemonOpts.size());
            for (String daemonOpt : daemonOpts) {
                encoder.writeString(daemonOpt);
            }
            encoder.flush();
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    /**
     * Bear witness the daemon's first words. Humbly acknowledge their presence by deserializing
     * the contents of the given input stream into a {@link DaemonStartupInfo} object.
     */
    public static DaemonStartupInfo acknowledgeDaemon(InputStream is) {
        // Wait for the process' stdout to indicate that the process has been started successfully
        String greeting = null;
        ArrayList<String> lines = new ArrayList<>();
        try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                LOGGER.debug("Daemon output: {}", line);
                lines.add(line);
                if (DaemonStartupCommunication.containsDebugMessage(line)) {
                    LOGGER.lifecycle(line);
                }
                if (line.contains(DaemonStartupCommunication.daemonGreeting())) {
                    greeting = line;
                    break;
                }
            }
        }

        if (greeting == null) {
            throw new GradleException(
                "Could not parse daemon handshake response.\n" +
                "Please read the following process output to find out more:\n" +
                "-----------------------\n" +
                String.join("\n", lines)
            );
        }

        return DaemonStartupCommunication.readDiagnostics(greeting);
    }

}
