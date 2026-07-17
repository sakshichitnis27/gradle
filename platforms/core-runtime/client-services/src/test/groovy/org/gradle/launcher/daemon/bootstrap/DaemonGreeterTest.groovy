/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.launcher.daemon.bootstrap

import org.gradle.api.GradleException
import org.gradle.launcher.daemon.client.DaemonGreeter
import org.gradle.launcher.daemon.logging.DaemonMessages
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class DaemonGreeterTest extends Specification {

    def "parses the process output"() {
        given:
        def address = new MultiChoiceAddress(UUID.randomUUID(), 123, [])

        def outputStream = new ByteArrayOutputStream()
        def printStream = new PrintStream(outputStream)
        printStream.print("""hey joe!
another line of output...
""")

        new DaemonStartupCommunication().printDaemonStarted(printStream, 12, "uid", address, new File("12.log"))

        when:
        def daemonStartupInfo = DaemonGreeter.acknowledgeDaemon(new ByteArrayInputStream(outputStream.toByteArray()))

        then:
        daemonStartupInfo.address == address
        daemonStartupInfo.uid == "uid"
        daemonStartupInfo.pid == 12
        daemonStartupInfo.diagnostics.pid == 12
        daemonStartupInfo.diagnostics.daemonLog == new File("12.log")
    }

    def "shouts if daemon did not start"() {
        given:
        def output = """hey joe!
another line of output..."""

        when:
        DaemonGreeter.acknowledgeDaemon(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)))

        then:
        def ex = thrown(GradleException)
        ex.message.contains("Could not parse daemon handshake response.")
        ex.message.contains("hey joe!")
    }

    def "shouts if daemon broke completely"() {
        when:
        DaemonGreeter.acknowledgeDaemon(new ByteArrayInputStream(new byte[0]))

        then:
        def ex = thrown(GradleException)
        ex.message.contains("Could not parse daemon handshake response.")
    }

}
