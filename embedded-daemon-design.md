# Embedded Daemon Design

Status: DRAFT — design under discussion

## Background

Gradle splits its runtime into three core process groups: **client**, **worker**, and **daemon**.
Today, client and worker code depend on daemon code at compile time. We are inverting this
dependency: any code the client references is being pulled out of daemon projects into "shared"
projects that both client and daemon may depend on. The project-splitting work itself is well
defined and mostly automated; it is out of scope here. Workers are also out of scope.

The remaining hard problem is **embedded daemons**.

In some cases, we support embedding the daemon fully within the client process.
"Daemon" here really names the group of code that runs a build — a *build instance* — whether it
runs in-process (embedded) or in a separate process (normal execution). Today, in order to run an
embedded daemon, the client *fully compiles against* the daemon: it sees the entire daemon
compilation classpath. This is unacceptable in the new model. The client must be able to
reflectively load, instantiate, and invoke the daemon subsystem with **no compile-time dependency**
on it.

The separate-process case is prioritized: client startup must stay as fast as possible, which means
the client's entire classpath stays on the JVM command line as `-cp` (required for AOT caching and
fast JVM startup). Embedded daemons may pay a classloading cost.

## Existing architecture

### Process-daemon startup

Client side (`DefaultDaemonStarter`, `platforms/core-runtime/client-services/.../daemon/client/DefaultDaemonStarter.java`):

1. Resolve the daemon JVM and the `gradle-daemon-server` module classpath via `DefaultModuleRegistry`
   (only the daemon-server jar is passed as `-cp`; its manifest `Class-Path` pulls in the rest).
2. Fork the daemon process (`GradleDaemon` main class).
3. Write a serialized `DaemonServerConfiguration` to the process' **stdin**
   (`DaemonStartupCommunication.writeDaemonServerConfiguration`).
4. Block reading the process' **stdout** until the handshake line appears
   (`DaemonOutputConsumer` → `DaemonStartupCommunication.readStartupInfoFromDaemonOutput`),
   yielding a `DaemonStartupInfo` (uid, `MultiChoiceAddress`, diagnostics).
5. All steady-state communication then happens over a TCP loopback socket via `DaemonClient` /
   `DefaultDaemonConnector`.

Daemon side (`DaemonMain`, `platforms/core-runtime/daemon-server/.../daemon/bootstrap/DaemonMain.java`):

1. Read `DaemonServerConfiguration` from **stdin**.
2. `NativeServices.initializeOnDaemon(...)` (all native features).
3. Create logging services (`LoggingServiceRegistry.newCommandLineProcessLogging()`).
4. Create `DaemonProcessState` (which wraps `BuildProcessState` + daemon/registry services).
5. Initialise logging: open the daemon log file, **redirect `System.out`/`System.err`** to it,
   replace `System.in` with an empty stream, `attachSystemOutAndErr()`, log level DEBUG, start.
6. Detach from the parent terminal (`ProcessEnvironment.maybeDetachProcess()`).
7. Configure the instrumentation agent (`AgentInitializer`).
8. `daemon.start()` — after this returns, the server address is available.
9. Write the handshake (`DaemonStartupCommunication.writeDaemonStartupInfo`) to the **original**
   stdout, then close the original stdout/stderr.
10. **Block** in `daemon.stopOnExpiration(...)` — this schedules periodic expiration checks and
    then parks in `DaemonStateCoordinator.awaitStop()` until the daemon expires. Then stop
    services; the surrounding `EntryPoint` calls `System.exit`.

The handshake protocol lives entirely in `DaemonStartupCommunication`
(`platforms/core-runtime/daemon-messaging/.../daemon/startup/DaemonStartupCommunication.java`) and is
already **stream-agnostic**: config in via any `InputStream`, startup info out via any
`PrintStream`. Nothing about it assumes a real process boundary.

### In-process execution today

`BuildActionsFactory` (`platforms/core-runtime/gradle-cli/.../launcher/cli/BuildActionsFactory.java`)
chooses between four paths:

- **`runBuildWithDaemon`** — normal path, `DaemonClient` over a socket.
- **`runBuildInSingleUseDaemon`** — forks a daemon that serves exactly one build
  (`SingleUseDaemonClient`).
- **`runBuildInProcess`** — the current single-use "embedded daemon". Constructs a
  `BuildProcessState` directly and invokes its `BuildExecutor` service in-process (`RunInProcess` +
  `ForwardStdInToThisProcess`). **No server, no socket, no handshake.**
- **`ForegroundDaemonAction`** — the current non-single-use "embedded daemon": a real daemon
  (full `DaemonProcessState`, socket server, registry entry, marked Idle) hosted by the client
  process and attached to the terminal. No handshake — other clients discover it via the registry.

The tooling API has its own equivalent of `runBuildInProcess`:
`ProviderConnection` (`platforms/core-runtime/launcher/.../tooling/internal/provider/ProviderConnection.java`)
wraps the in-process `BuildExecutor` in `RunInProcess` + `SystemPropertySetterExecuter` +
`ForwardStdInToThisProcess` when embedded mode is requested. There is no TAPI counterpart of
foreground mode.

Both `runBuildInProcess` and `ForegroundDaemonAction` (and the TAPI embedded path) compile against
daemon-server logic. **All of them are replaced by this work.**

`DaemonProcessState` *is* `BuildProcessState` plus `DaemonServices` + `DaemonRegistryServices`. We
already know instantiating either inside a client process works (foreground mode proves it for
`DaemonProcessState`).

## Goals

1. The client starts an embedded daemon by **reflectively** loading a well-known class from a
   well-known classpath, with no compile-time dependency on daemon projects.
2. The embedded daemon is a **real daemon**: real `Daemon` server, real socket, real handshake,
   real registry entry. After startup, the client talks to it *identically* to a separate-process
   daemon.
3. Both flavors of embedded daemon are supported, mirroring what exists today:
   - **single-use embedded** (replaces `runBuildInProcess` and the TAPI in-process path), and
   - **non-single-use embedded** (replaces `--foreground` / `ForegroundDaemonAction`).
4. Share as much bootstrap code as possible with `DaemonMain`.
5. The CLI path and the TAPI provider path both migrate to the same single-use machinery.
6. `RunInProcess`, `ForwardStdInToThisProcess`, and `ForegroundDaemonAction` are eventually
   deleted.

Non-goals:

- Worker processes.
- The client/daemon project splitting itself.
- Classloader *filtering* between client and daemon (possible later hardening, see below).
- Changing what embedded daemons are *for*. Single-use embedded execution is primarily a testing
  feature, but `--foreground` sees real production use — for example, a tooling vendor uses the
  client in foreground mode to start a daemon on a remote machine and connect to it remotely.
  Notably, that use case does not actually require an *embedded* daemon: foreground could in the
  future be served by a separate-process daemon kept in the foreground. We are not making that
  change now, but this design keeps the option open.
- Fixing existing embedded-daemon limitations (e.g. degraded native services) — existing behavior
  is preserved.
- Migrating the integration-test executers (`InProcessGradleExecuter` does **not** go through
  `BuildActionsFactory`; updating the embedded executers to use this codepath is a desirable
  follow-up, out of scope for this change).

## Design

### Classloader model

```
                 System/App classloader (-cp: client + shared jars)
                                   │  (client runs here; AOT-friendly)
                                   ▼
                 VisitableURLClassLoader (daemon-server classpath)
                                   │  (embedded daemon runs here)
```

- The daemon classloader is a **child of the client classloader**, created via
  `VisitableURLClassLoader.fromClassPath(...)` over the `gradle-daemon-server` module
  implementation classpath, resolved exactly as `DefaultDaemonStarter` does:
  `new DefaultModuleRegistry(CurrentGradleInstallation.get()).getModule("gradle-daemon-server").getImplementationClasspath()`.
- Parent-first delegation means all **shared** classes (logging, messaging, native integration,
  daemon protocol, ...) resolve from the client's classloader — a single copy per JVM.
- **Native services**: JNI libraries may only be loaded into one classloader per JVM. Because the
  `native` classes live in the shared (parent) loader, `NativeServices.getInstance()` returns the
  **same singleton** to client and embedded daemon. This is deliberate.
- **No filtering initially.** The daemon will see client classes at runtime. This leak is accepted;
  if it becomes a problem, insert a `FilteringClassLoader` between parent and child, modeled on
  `DefaultToolingImplementationLoader` (which allows only the protocol package through the
  boundary). Longer term, a *truly isolated* (non-child) daemon classloader is desirable; that
  requires separating the native-services dependency first (e.g. a dedicated shared library that
  both client and daemon load), and is out of scope here.
- **No classloader caching.** CLI clients are one-and-done processes. A TAPI client can technically
  run multiple embedded daemons sequentially from one process, but caching the classloader is out
  of scope unless it shows up as a performance problem.
- **Transition note**: until the client classpath split lands, the client's own classpath still
  contains daemon classes, so parent-first delegation resolves "daemon" classes from the *parent*
  and the child loader is effectively a pass-through. Behavior is identical either way; true
  isolation arrives for free once the split removes daemon jars from the client `-cp`.

### Entry-point contract

A well-known class in `gradle-daemon-server`:

```java
package org.gradle.launcher.daemon.bootstrap;

public class EmbeddedDaemonMain {
    public EmbeddedDaemonMain() {}  // no-arg constructor

    /**
     * Runs an embedded daemon until it expires. Blocks the calling thread for the
     * lifetime of the daemon. Never calls System.exit and never touches the global
     * System.in/out/err.
     */
    public void run(InputStream stdin, OutputStream stdout, OutputStream stderr) { ... }
}
```

- Arguments are **JDK-stdlib types only**, so no shared Gradle types are needed at the reflective
  boundary. (Client and daemon always come from the same distribution, so this is not a
  cross-version protocol — the constraint just keeps the boundary trivially safe, and keeps the
  door open for the future truly-isolated classloader.)
- The streams replay the process protocol exactly:
  - `stdin` carries the serialized `DaemonServerConfiguration` (then EOF),
  - `stdout` carries the handshake line, after which the daemon closes it,
  - `stderr` carries startup diagnostics if bootstrap fails before the handshake.
- Single-use vs. non-single-use is **not** part of the entry-point signature: it already travels in
  the serialized configuration (`DaemonServerConfiguration.isSingleUse()`), same as for a forked
  daemon. Foreground-specific behavior (see below) is likewise carried in the configuration.
- On any failure before the handshake is written, `run` must close `stdout` (in a `finally`) so a
  client blocked in `readStartupInfoFromDaemonOutput` unblocks, and must report the failure to
  `stderr` and by throwing.

### `EmbeddedDaemonStarter` (client side)

A new `DaemonStarter` implementation in `client-services`.

`startDaemon(singleUse)`:

1. Build the `DaemonServerConfiguration` as `DefaultDaemonStarter` does, except:
   - JVM options are the *current process'* immutable JVM args (as foreground mode does today),
   - the instrumentation-agent flag is `daemonParameters.shouldApplyInstrumentationAgent()`, not
     the unconditional `true` used for forked daemons (forking controls the agent on the command
     line; embedding inherits whatever the client process has). The compatibility check that
     permits embedding has already verified the requested agent status matches this process'
     actual agent status, so the requested value is used as-is.
2. Resolve the daemon-server classpath from the module registry; create the child
   `VisitableURLClassLoader`.
3. Create the streams:
   - stdin: `StreamByteBuffer` pre-filled with the serialized configuration — identical to what
     `DefaultDaemonStarter` already does before handing the buffer to the forked process;
   - stdout: `PipedOutputStream` (daemon side) / `PipedInputStream` (client side) — the handshake
     is a single line, well within default pipe buffering;
   - stderr: an in-memory buffer, included in the failure message if startup fails.
4. Reflectively instantiate `org.gradle.launcher.daemon.bootstrap.EmbeddedDaemonMain` and invoke
   `run(stdin, stdout, stderr)` as a `FutureTask` on a **new named thread** whose context
   classloader is the daemon loader. The thread outlives `startDaemon` — it is the embedded
   daemon's "process" — and the future is the handle for its completion and failure. The starter
   is only responsible for starting the daemon; the started daemon's lifetime belongs to the
   caller, who manages it through the `DaemonHandle` the starter returns (which carries the
   `DaemonStartupInfo`, and travels to the daemon client on the `DaemonClientConnection`).
   `SingleUseDaemonClient` closes its connection and then awaits the handle's termination at the
   end of every build: a single-use daemon stops itself after its one build, and embedding removes
   the process-isolation cleanup guarantee forked daemons get for free — without the wait, the
   daemon's teardown (aborting exec handles, closing connections, restoring the borrowed logging
   globals) races the client's `System.exit`, which surfaced as `MessageIOException`s and
   "Shutdown in progress" failures across `--no-daemon` test suites. Forked daemons return a stub
   handle whose termination is never tracked: they detach at startup (the `ExecHandle` completes
   at *detachment*, not process exit — there is deliberately nothing left to wait on) and are
   designed to outlive the client.
5. Block in `DaemonStartupCommunication.readStartupInfoFromDaemonOutput(pipeIn)`. When the
   daemon's output ends without a handshake (`InitializationException`), wait briefly for the
   daemon's future and fail with the daemon's own failure as the cause where available, plus the
   captured stderr.
   - TODO: consider a timeout on this wait — in *both* modes. Today a forked daemon that hangs
     before printing the handshake (without exiting) also hangs the client indefinitely; this is a
     pre-existing gap that the embedded path makes easier to hit and easier to fix.
6. Return the `DaemonStartupInfo`. From here the flow is unchanged: `DefaultDaemonConnector`
   connects to the advertised loopback address, compatibility is verified against the daemon
   context, the build request is sent, stdin forwarding happens via the existing
   client-input-forwarding over the connection (this replaces `ForwardStdInToThisProcess`), and
   log output returns via `LogToClient` events.

### `EmbeddedDaemonMain` (daemon side)

Step-by-step against `DaemonMain`. The "EmbeddedDaemonMain" column applies to **both** embedded
flavors; foreground additionally marks itself Idle in the registry, as described in the foreground
section:

| # | `DaemonMain` (forked)                                        | `EmbeddedDaemonMain`                                             |
|---|--------------------------------------------------------------|------------------------------------------------------------------|
| 1 | Read config from `System.in`                                 | **Same code**, from the provided stdin                            |
| 2 | `NativeServices.initializeOnDaemon(...)`                     | **Skip** — singleton already initialized by the client (see risks) |
| 3 | `LoggingServiceRegistry.newCommandLineProcessLogging()`      | `LoggingServiceRegistry.newEmbeddableLogging()` — no attach to system streams; system sources are captured in the environment step (row 5) |
| 4 | `new DaemonProcessState(config, loggingRegistry, manager)`   | **Same**                                                          |
| 5 | Create daemon log file; `System.setOut/setErr` → log file; `System.setIn(empty)`; `attachSystemOutAndErr()` | **No daemon log file, no local rendering destination.** `captureSystemSources()` routes `System.out/err` writes from build logic into the daemon's logging context while the daemon runs, restored on stop (see "Logging"). Build output reaches the client over the connection via `LogToClient`, like build actions; the daemon's local renderer has nothing attached |
| 6 | `ProcessEnvironment.maybeDetachProcess()`                    | **Skip** — would detach the client's process from its terminal    |
| 7 | `AgentInitializer.maybeConfigureInstrumentationAgent()`      | **Same** (config flag already reflects client-process reality)    |
| 8 | `daemon.start()`                                             | **Same**                                                          |
| 9 | Write handshake to original `System.out`, close out/err      | **Same protocol**, to the provided stdout/stderr                  |
| 10| `stopOnExpiration(...)`; stop services and logging manager; `System.exit` via `EntryPoint` | `stopOnExpiration(...)`; stop services and logging manager; **return normally** |

Why row 5/steps 3–5 differ: `System.in/out/err`, java.util.logging, and the slf4j binding are all
**JVM-global** state, and the classes that manage them live in the shared parent classloader. In
the client process, the client's own logging system has already captured the system streams and
attached the console. A forked daemon owns its whole process and is free to redirect everything to
its log file; an embedded daemon may only *borrow* the globals through the logging system's
snapshot/restore machinery (`captureSystemSources()`, the slf4j listener swap), putting everything
back the way it found it when it stops, and must never redirect them destructively
(`System.setOut` to a log file, `maybeDetachProcess`). See "Logging" below for the full analysis.

Why row 10 must not exit: `DaemonMain` extends `EntryPoint`, whose `ProcessCompleter` calls
`System.exit` when `doAction` returns. In embedded mode that would kill the client. An audit of
`System.exit`/`Runtime.halt` across the daemon-side runtime found only three sites, none reachable
from the shared bootstrap flow:
- `ProcessCompleter` (`EntryPoint` completion) — forked-only wrapper, not part of the shared code;
- `DaemonMain.invalidArgs` — command-line argument validation, forked-only;
- `ProcessBootstrap` — the forked process entry wrapper (`GradleDaemon` main).
Nothing in `Daemon`, `DaemonStateCoordinator`, the expiration strategies, or the logging stack
calls `System.exit`. `EmbeddedDaemonMain` therefore simply does not extend `EntryPoint` and returns
normally.

**Forced stops.** A forced stop (`DaemonStopState.Forced`) is reached through the same unwind, not
a separate exit path. For example, the daemon health check (`HealthExpirationStrategy`) detects GC
thrashing or excessive heap/metaspace pressure during the periodic expiration check and returns
`IMMEDIATE_EXPIRE`; `Daemon`'s expiration listener translates that into
`DaemonStateCoordinator.requestForcefulStop(...)`, which flips the state to `ForceStopped` and
makes the parked `awaitStop()` return `DaemonStopState.Forced`. `stopOnExpiration` then simply
returns — the "stop now" is still a cooperative unwind of the blocked bootstrap thread, and the
process itself dies only when `EntryPoint` subsequently calls `System.exit`. Embedded, the same
unwind ends the runner thread instead, which is exactly what we want. (In embedded mode a
GC-thrashing verdict is really about the *client's* JVM — stopping the embedded daemon releases
what it can, but it cannot save the process the way killing a forked daemon does.)

The one behavioral fork is cleanup: after a forced stop, `DaemonProcessState.close()` deliberately
*skips* stopping the services — in-flight work may still be using them — and relies on process
death for cleanup. Embedded, there is no process death, and a long-lived TAPI client must not
accumulate leaked service registries. **Embedded daemons therefore close their services
unconditionally**, including after a forced stop. What this entails: the close runs while a
non-cancellable build may still be executing, so individual services may throw or block during
teardown. Both are acceptable — the close happens on the embedded runner thread and failures
surface through its future. Implementation-wise, `DaemonProcessState` knows nothing about this:
`DaemonServerBootstrap` holds the stop state across its `finally` and decides what to stop, with
a `closeServicesOnForcedStop` parameter of `run` — forked passes false and keeps the skip,
embedded passes true and closes.

### Blocking behavior and the runner thread

`DaemonMain` today does **not** return after the server starts: step 10
(`stopOnExpiration` → `awaitExpiration` → `DaemonStateCoordinator.awaitStop()`) parks the main
thread until the daemon expires. We do not touch this logic. `EmbeddedDaemonMain.run(...)` has the
same blocking semantics, which is why the client invokes it on a dedicated thread. The thread ends
naturally when the daemon expires (for single-use: right after its one build).

### Sharing code between `DaemonMain` and `EmbeddedDaemonMain`

Extract the body of `DaemonMain.doAction` into a shared bootstrap class in
`org.gradle.launcher.daemon.bootstrap` (daemon-server project). The variation points split into
plain parameters (streams, logging services) and a small environment callback interface:

```java
class DaemonServerBootstrap {
    /**
     * Runs the daemon until it expires. Builds the daemon process state, starts the server,
     * performs the server-side startup handshake, then blocks until the daemon expires.
     * The daemon infrastructure log level (DEBUG) and the logging manager start are owned
     * here, as is the decision whether to stop the services after a forced stop.
     */
    void run(
        DaemonServerConfiguration configuration,
        PrintStream startupOut,
        PrintStream startupErr,
        ServiceRegistry loggingServices,
        boolean closeServicesOnForcedStop,
        DaemonServerEnvironment environment
    );
}

interface DaemonServerEnvironment {
    /**
     * Prepares the environment to run the daemon server. Invoked after the daemon services
     * are created and before the daemon logging manager is started. The implementations
     * document what forked and embedded daemons do here — log file creation, stream
     * redirection and terminal detach for forked; nothing process-global for embedded.
     */
    void beforeStart(LoggingManagerInternal loggingManager, File daemonLog, ServiceRegistry daemonServices);
}
```

- `DaemonMain` stays an `EntryPoint` whose `doAction` reads the configuration from `System.in`,
  initializes native services, and calls `run` with the original system streams,
  `newCommandLineProcessLogging()`, `closeServicesOnForcedStop = false` and the forked
  environment. Behavior identical to today.
- `EmbeddedDaemonMain.run(in, out, err)` reads the configuration from the given stdin and calls
  `run` with the given streams, `newEmbeddableLogging()`, `closeServicesOnForcedStop = true` and
  the embedded environment. No `EntryPoint`, no exit; the streams are closed in a `finally` so a
  client blocked reading the handshake always unblocks.

The exact factoring can flex during implementation; the point is that the shared steps exist
**once** and the variation surface is two callbacks plus three parameters.

### Client-services wiring

Today the client services come in per-flavor subclasses:
`DaemonClientServicesSupport` (abstract base) → `DaemonClientServices` (regular) and
`SingleUseDaemonClientServices` (single-use), where the only difference is which `DaemonClient` is
provided (`DaemonClient` with a compatibility spec vs. `SingleUseDaemonClient` with a match-none
spec, a lifecycle message, and `startSingleUseDaemon()` instead of `connect()`).
`DaemonClientFactory` exposes `createBuildClientServices(...)` and
`createSingleUseDaemonClientServices(...)`.

Rather than adding two more subclasses for the embedded flavors, this work **collapses the
hierarchy**: remove `SingleUseDaemonClientServices` and `SingleUseDaemonClient`, fold the base
class and its two subclasses into a single non-abstract provider — named `DaemonClientServices`,
since a concrete class is no longer "support" — parameterized with
`(boolean singleUse, boolean embedded)`:

- `singleUse` selects the client behavior currently split into `SingleUseDaemonClient`
  (match-none spec, `startSingleUseDaemon()`, the "single-use daemon will be forked" message —
  reworded to cover the embedded case) — folded into `DaemonClient` or selected inside the
  provider method.
- `embedded` selects which `DaemonStarter` the registry provides: `DefaultDaemonStarter` (fork) or
  `EmbeddedDaemonStarter` (reflective in-process start).

`DaemonClientFactory` gains the corresponding parameters (or a merged entry point) and
`BuildActionsFactory` / `ProviderConnection` select `(singleUse=true, embedded=true)` where they
currently call `runBuildInProcess`.

### `BuildActionsFactory` changes

- The `canUseCurrentProcess(...)` → `runBuildInProcess(...)` branch becomes: build client services
  with `(singleUse=true, embedded=true)` and run through `DaemonClient` like any other daemon
  build. `canUseCurrentProcess` remains the gate — it already verifies the current JVM satisfies
  the request, which is precisely the embeddability condition.
- The `--foreground` branch (`ForegroundDaemonAction`) is replaced by the **non-single-use
  embedded daemon**: same reflective entry point, `singleUse=false`. See below.
- `runBuildInProcess` today calls `System.setProperties(...)` up front. Through the daemon client,
  the effective system properties travel with the build request and the daemon applies them — in
  embedded mode that still mutates the (shared) process properties, so the net effect is unchanged.

### `ProviderConnection` (tooling API) changes

The TAPI embedded path migrates in this same work, onto the same single-use machinery as the CLI:
where `ProviderConnection` currently builds `RunInProcess` + `SystemPropertySetterExecuter` +
`ForwardStdInToThisProcess` around the in-process `BuildExecutor`, it instead obtains embedded
single-use daemon client services from `DaemonClientFactory`. There is no TAPI counterpart of
foreground mode, so `singleUse=true, embedded=true` is the only TAPI flavor.

Note: a TAPI client process may start embedded daemons **multiple times sequentially**. Everything
the embedded daemon changes about shared process state must therefore be reversible (see the
logging analysis and shutdown-hooks item).

### Foreground mode as a non-single-use embedded daemon

`ForegroundDaemonAction` today: reuses the client's logging services (output goes to the terminal
through the client's renderer), creates a `DaemonProcessState`, marks the daemon Idle in the
registry, and blocks in `stopOnExpiration`. No handshake, no daemon log file redirection.

Under the new model, `--foreground` starts an embedded daemon with `singleUse=false`. The
daemon-side bootstrap is **identical** to the single-use flavor (same streams, same
close-after-handshake, nothing attached to the local renderer, no log file) except for one
difference carried in the serialized configuration (a foreground flag alongside `isSingleUse`):
the daemon marks itself Idle in the registry on startup, preserving today's explicit mark-Idle
behavior.

The launcher-side flow:

1. Start the embedded daemon (`singleUse=false`) and consume the handshake (which also provides
   the daemon uid/address for display).
2. Connect to the daemon over the socket — like any client, using the address and auth token from
   the handshake — and send the new **`MonitorOutput`** command (see below). Received output
   events are rendered through the client's own logging output to the terminal.
3. Join the daemon thread; the monitor connection ends when the daemon stops.

**Log output thus travels over the socket, exactly like everything else** — the same
`OutputEvent`-over-connection path `LogToClient` uses for build clients — rather than through a
bespoke foreground-only channel. **No daemon log file is created** — matching today:
`DaemonLogFile` is only a path value provided by `DaemonServices`; the file on disk is created
solely by `DaemonMain.initialiseLogging`, which `ForegroundDaemonAction` never invokes; in the new
model, log-file creation lives inside the environment's `initialiseLogging` callback, and the
embedded environment doesn't create one.

### Log monitoring over the connection (new protocol capability)

Today there is no way to receive a daemon's full log output over a connection — `LogToClient` only
relays to the client of a running build. This work adds a small, general capability, composed
entirely of existing patterns:

- **`MonitorOutput extends Command`** (in `daemon-messaging`): carries the requested `LogLevel`;
  token authentication comes with `Command` like every other message.
- **`HandleMonitorOutput`** daemon action, registered with the other non-build handlers
  (`HandleStop`, `HandleReportStatus`, ...) *before* `StartBuildOrRespondWithBusy` — so monitoring
  never marks the daemon busy and does not interfere with builds or idle expiration. It registers
  an `OutputEventListener` on the daemon's `LoggingOutputInternal` (the same object `LogToClient`
  listens on), pumps matching events to the client via `connection.logEvent(...)` using a
  dispatcher extracted from `LogToClient`'s `AsynchronousLogDispatcher`, and holds the execution
  open until disconnect (each connection has its own handler thread, so blocking is fine).
- **A small monitor client** in `client-services`, modeled on `ReportDaemonStatusClient`: connect,
  send `MonitorOutput`, feed each received `OutputEvent` into the client's logging output until
  the connection closes.

Known caveat: output logged between daemon logging startup and the monitor attaching is not shown
on the foreground terminal, whereas today's foreground (sharing the client's renderer from the
first moment) shows everything. The window is small — the launcher connects immediately after the
handshake — and if it matters, the daemon can buffer output events from logging start until the
first monitor attaches when the foreground flag is set.

This capability is deliberately not foreground-embedded-specific: it works against **any** daemon.
In particular it is the missing piece for the eventual goal of serving `--foreground` with a
**forked** process kept in the foreground — that future becomes `DefaultDaemonStarter` + connect +
`MonitorOutput`, reusing this exact client code with only the starter swapped. Nothing built here
is discarded by that migration. (It also enables tailing a remote daemon's log, which is adjacent
to the known production use of foreground on remote machines.)

The important property holds: both embedded flavors share `DaemonServerBootstrap` and the same
reflective boundary, and `ForegroundDaemonAction`'s compile-time dependency on daemon-server logic
disappears.

## Logging

This is the subtlest part of the design, so the relevant machinery is spelled out here.

### How the pieces fit

- Each `LoggingServiceRegistry` owns an `OutputEventRenderer` — the hub that receives
  `OutputEvent`s and forwards them to attached destinations (console, listeners, streams).
- `DefaultLoggingManager` (created per scope from a registry's `LoggingManagerFactory`) is a
  **snapshot/restore stack**: `start()` snapshots each underlying logging system, applies this
  manager's settings, and `stop()` restores the snapshots. Nesting managers is the designed usage.
- slf4j: Gradle ships its own slf4j binding. The binding's `OutputEventListenerBackedLoggerContext`
  is **one per classloader that loads it** — with our shared parent loader, **one per JVM**. It has
  a single `outputEventListener` slot; `Slf4jLoggingConfigurer.configure` points that slot at its
  registry's renderer. All `LOGGER.*` calls anywhere in the JVM route to whichever renderer
  currently owns the slot.
- `LogToClient` (the daemon-side command decoration that ships build output to the remote client)
  does not care about any of this plumbing: it simply registers an `OutputEventListener` on the
  `LoggingManagerInternal` handed to `DaemonServices` and forwards matching events over the
  connection. It works identically whether that manager fronts a daemon-owned or a client-shared
  renderer.

### What the existing embedded routes do today

Both `runBuildInProcess` and `ForegroundDaemonAction` (and the TAPI in-process path) **reuse the
client's logging services** — there is only ever one renderer in the process. Consequences:

- Daemon/build log output renders directly to the client's console through the client's renderer
  and its already-attached console/system-stream destinations.
- There is no daemon log file.
- There is no slf4j contention: one context, one renderer, one owner.
- In foreground mode, `LogToClient` additionally forwards build output to the *remote* client of
  each build, while the local terminal also shows it — which is exactly foreground's observable
  behavior.

This is simple and proven, but it is only possible because those routes receive the client's
logging `ServiceRegistry` as an object — i.e. because of the compile-time coupling we are removing.

### What the new design does

The embedded daemon creates its **own** logging registry (`newEmbeddableLogging()`), giving it its
own renderer, distinct from the client's. What gets attached to that renderer differs per flavor,
following one rule: **preserve today's observable behavior**.

**Nothing is attached locally — in either embedded flavor.** All output leaves the daemon over
connections, as structured `OutputEvent`s: `LogToClient` relays build output to each build's
connected client, and `MonitorOutput` relays the daemon's log output to the foreground launcher.
Attaching (destinations) is distinct from capturing (sources): while the embedded daemon runs, it
*captures* the process' system sources (`captureSystemSources()` — `System.out/err` and JUL), just
like a forked daemon does, so that output printed by build logic enters the daemon's logging
context and travels to the client through `LogToClient`, grouped under its build operation and
ordered with the rest of the build output. Without the capture, a build-script `println` would
instead be swallowed by the *client's* system-stream capture and rendered immediately, ungrouped,
racing ahead of the daemon's relayed events (this was discovered as a real output-ordering
regression by `ToolingApiLoggingCrossVersionSpec`). The capture uses the same snapshot/restore
machinery as the slf4j listener swap: on daemon stop the previous streams — including the client's
capturing streams — are restored exactly.
The receiving client renders the events through its own logging system. This preserves what users
see today (output on the client console / foreground terminal, routed through the client's
logging), while changing only the *mechanism*: today the in-process build and the foreground
daemon write into the client's renderer directly; now the events take a trip over the wire, like
build actions do. Critically, **no daemon log file is created** — today's embedded execution
produces no log file, and users of embedded modes are not cleaning one up; silently starting to
write one would be a behavior change. The daemon's local renderer simply has no destination:
events that no connection is listening for are discarded, as they effectively are today.

Two accepted consequences of "no local destination":
- When a daemon disappears mid-build, `DaemonClient` prints the tail of the daemon log via
  `DaemonDiagnostics.tailDaemonLog()`; for an embedded daemon there is no log to tail. Today's
  in-process path has no such diagnostics either (there is no daemon), so nothing is lost.
- The handshake protocol unconditionally serializes a daemon log *path* (`DaemonLogFile` is always
  available as a value); for embedded daemons the path points at a file that is never created —
  the same property foreground has always had. Implementors should not "fix" this by eagerly
  creating the file.

In a future change, we may give embedded daemons a real log file to bring them closer to forked
daemons (better disappeared-daemon diagnostics, a home for DEBUG infrastructure logging). That
would be a deliberate, observable behavior change and is explicitly out of scope here.

Why is nothing attached, rather than `attachConsole` on the passed-in streams or some other local
destination? Because the daemon's audience is always a *connection*. The single-use client already
receives every build output event via `LogToClient`; rendering locally as well would either write
into the starter's closed handshake pipes or force the client to echo text it already received —
printing everything twice. The foreground launcher receives the daemon's output via
`MonitorOutput` over the socket, for the same reason with the same duplication argument. There is
no double rendering anywhere: each build's output goes to that build's client via `LogToClient`,
the daemon's log output goes to the monitor, and the same output appearing on both a remote build
client's console and the foreground terminal is foreground's defining behavior today — each
destination is fed exactly once, over the wire.

One consequence of the two-renderer model: while the embedded daemon's logging manager is started,
the JVM-wide slf4j slot points at the **daemon's** renderer. Client-thread `LOGGER` calls during a
build therefore flow into the daemon's logging: those at or above the relevant level are relayed
out over the connections (`LogToClient` to the build's client, `MonitorOutput` to a foreground
monitor); the rest are discarded. In practice this is nearly invisible — during a build the client
sits waiting on the connection, and everything the user sees arrives as relayed events rendered by
the client's own renderer, which is *not* routed through slf4j.

Can the handoff be avoided entirely? Not while both sides share one slf4j context: the slot has a
single owner, and during a build that owner must be the daemon (that is where build logging
originates; if the client kept the slot, all daemon/build slf4j output would bypass the daemon log
and `LogToClient`'s level filtering). Ownership transfer for the daemon's lifetime is therefore
correct, not incidental. A future truly-isolated daemon classloader would give the daemon its own
slf4j context and dissolve the issue.

### A bug this design exposes: slf4j restore

The snapshot/restore stack does **not** currently restore the slf4j *listener* — only the level:

- `DefaultLoggingManager.stop()` → `LoggingSystemAdapter.restore(snapshot)` →
  `Slf4jLoggingConfigurer.configure(previousLevel)`;
- but `Slf4jLoggingConfigurer` only calls `context.setOutputEventListener(...)` on its **first**
  `configure` (`currentLevel == null` guard) — on restore it merely resets the level.

So after the embedded daemon's manager stops, the slf4j slot **stays pointed at the daemon's
(now stopped) renderer**. Nobody has noticed because in every existing topology it can't matter: a
forked daemon has one registry per process, and today's embedded routes share the client's registry
(same renderer before and after). Our design is the first with two registries in one context.

Impact: none for the one-shot CLI client (it exits right after the build). For a TAPI client
running sequential embedded daemons, client-side slf4j output between daemon N stopping and daemon
N+1 starting would be routed to a dead renderer and dropped.

**Fixed as part of this work**: the `Slf4jLoggingConfigurer` + `LoggingSystemAdapter` pair —
which existed only to serve slf4j — is inlined into a single `Slf4jLoggingSystem` whose snapshot
captures the binding's current listener and level, and whose restore puts both back. This makes
the nested-two-registries model genuinely correct rather than accidentally tolerable.

The restore only runs when the daemon's logging manager is stopped, and historically nothing
stopped it — a forked daemon just exits. `DaemonServerBootstrap` therefore owns the manager's
lifecycle end to end: it creates and starts the manager, and stops it as the last step of daemon
teardown (after the daemon services, so they can log while stopping), restoring every borrowed
global — the slf4j listener and, for embedded daemons, the captured `System.out`/`System.err`.
The stop is skipped in exactly the case service teardown is skipped: a forced stop of a forked
daemon, which relies on the imminent process exit for cleanup.

(For completeness: `OutputEventRenderer.snapshot()/restore()` only tracks the console attachment,
and restoring *to* a previous console throws `UnsupportedOperationException`. Our usage is safe —
the embedded daemon's renderer never attaches a console at all — but implementors should be aware
the renderer's restore is partial.)

## Known dragons

1. **Native feature gap.** `NativeServices.initialize` is first-caller-wins: the client initializes
   with `JANSI` only; a real daemon initializes with all features (including `FILE_EVENTS`). The
   embedded daemon shares the client's singleton, so `initializeOnDaemon` would be a silent no-op
   and file-system-watching natives are unavailable. This is **accepted**: embedded daemons already
   have degraded native behavior today (the current in-process path has the same limitation), and
   `NativeServices` degrades gracefully when a feature is missing. If it ever needs fixing, the
   client can detect *early* — before initializing native services — that it will be running an
   embedded daemon, and initialize with the daemon feature set up front. Out of scope for this
   work. Test fixtures that care can do the same today.

2. **slf4j listener restore** — see "Logging" above. Fixed as part of this work.

3. **Handshake wait can hang.** An embedded daemon that fails before the handshake must close its
   stdout in a `finally` so the client's blocking read unblocks (a forked daemon gets this for free
   from process death). Additionally, the client-side wait should get a timeout — TODO, ideally
   for both forked and embedded modes, since a forked daemon that hangs before printing the
   handshake without exiting already hangs the client today.

4. **Shutdown hooks.** `Daemon.start()` registers a VM shutdown hook. Forked daemons don't care;
   a TAPI client hosting several sequential embedded daemons would accumulate hooks (holding the
   daemon classloader reachable, among other things). This hook must be deregistered on daemon
   stop as part of this work. (`DaemonMain`'s log-file shutdown hook is part of the forked-only
   log-file setup; embedded daemons never register it.)

5. **Teardown after a forced stop.** Forked daemons skip service teardown after
   `DaemonStopState.Forced` and rely on process death; embedded daemons close their services
   unconditionally instead (see "Forced stops" above). The residual risk is that teardown runs
   while a non-cancellable build is still executing, so it may throw or block — exceptions surface
   on the runner thread, and a hang is bounded by `SingleUseDaemonClient`'s
   `DaemonHandle.awaitTermination` timeout, after which the client warns and moves on.

6. **Daemon registry visibility.** The embedded daemon uses the real on-disk
   `DaemonRegistryServices` and appears in the shared registry, exactly like `--foreground` does
   today. This is fine — and has the nice property that `./gradlew --stop` can kill a hanging
   embedded daemon. If cross-process connection attempts to embedded daemons ever become a problem,
   we can revisit (an in-memory `EmbeddedDaemonRegistry` already exists as a test fixture).

7. **Piped-stream liveness.** `PipedInputStream` assumes the writer thread stays alive; both sides
   here are short-lived and single-line, so this is fine — but keep the handshake the *only* thing
   that ever crosses these streams.

8. **No AOT for daemon classes in embedded mode.** Daemon jars load through a `URLClassLoader`, so
   they miss AOT/class-data sharing. Irrelevant: embedded daemons are not on the startup-critical
   path (single-use is testing-focused, foreground is long-running), and the prioritized
   forked-daemon path keeps its full `-cp`.

9. **Environment-variable mutation (found during implementation).** The daemon build pipeline
   (`ApplyClientEnvironmentVariables`) mutates the daemon process' environment to match the
   client's for the duration of each build -- including scrubbing per-process variables
   (`JAVA_MAIN_CLASS_<pid>`, `TERM_SESSION_ID`, ... -- see `Jvm.getInheritableEnvironmentVariables`)
   that the client filters from what it sends, so that they are not silent inputs to the build.
   Native-platform's implementation needs `--add-opens=java.base/java.util=ALL-UNNAMED` to update
   the JVM's cached env map; forked daemons get that from `JpmsConfiguration.forDaemonProcesses`
   on their command line, but the client JVM hosting an embedded daemon does not have it by
   default -- so any mutation attempt throws, and one is always attempted (at minimum the
   per-process scrub). Fix: the apply is still always attempted, preserving forked-daemon behavior
   bit for bit, but a mutation failure degrades to the action's existing warning instead of
   failing the build. Embedded builds therefore print this warning. This is deliberate: the
   warning reports a real difference between the daemon's environment and what the client
   requested, and a user who cares can start the client JVM with the JPMS options above to make
   embedded environment mutation work. The graceful degradation also fixes a latent
   foreground-daemon crash on JDK 16+ when a client with a different environment runs a build
   against it.

10. **JUL capture restore was lossy — fixed as part of this work.** `captureSystemSources()`
    includes java.util.logging: `JavaUtilLoggingSystem.startCapture()` used to do
    `LogManager.reset()` — wiping (and closing) every JUL handler in the JVM, including the
    hosting client's bridge or a host application's own handlers — and its restore did `reset()`
    again, reinstating nothing. After an embedded daemon stopped, client-side JUL logging was
    silently dropped. Fixed the same way as the slf4j listener: the snapshot captures the root
    logger's actual handlers, install surgically replaces the root handlers with the
    `SLF4JBridgeHandler` (without closing the displaced handlers, and without resetting the rest
    of the `LogManager`), and restore reinstates exactly the handlers that capturing displaced.
    Ownership follows scope: handlers displaced at install are borrowed from the outer scope and
    come back open, while handlers installed *during* the capturing scope (the bridge, or handlers
    installed by build logic) are closed on restore — mirroring `LogManager`'s close-on-exit
    behavior that a forked daemon gets from process death.

## Incremental plan

1. **Refactor, no behavior change**: extract `DaemonServerBootstrap` from `DaemonMain`; forked
   daemon coverage stays green. (The handshake consolidation into `DaemonStartupCommunication` —
   already done — was the prerequisite for this.)
2. Fix the slf4j listener snapshot/restore in the logging system (independently testable).
3. Add `EmbeddedDaemonMain` to `daemon-server`.
4. Add `EmbeddedDaemonStarter` to `client-services`; collapse
   `DaemonClientServicesSupport`/`DaemonClientServices`/`SingleUseDaemonClientServices`/
   `SingleUseDaemonClient` into a single non-abstract `DaemonClientServices` + `DaemonClient`
   parameterized by `(singleUse, embedded)`; extend
   `DaemonClientFactory` accordingly. Reflection is used from day one even while the daemon classes
   are still visible on the client classpath, so the compile-time dependency can be cut without
   touching this code again.
5. Rewire `BuildActionsFactory.runBuildInProcess` to `(singleUse=true, embedded=true)`.
6. Migrate `ProviderConnection`'s embedded path to the same machinery.
7. Add the `MonitorOutput` protocol command, daemon-side handler, and monitor client
   (independently testable against forked daemons; also the building block for a future forked
   foreground mode).
8. Replace `ForegroundDaemonAction` with the non-single-use embedded daemon + monitor connection.
9. Delete `RunInProcess` / `ForwardStdInToThisProcess` / `ForegroundDaemonAction` once nothing
   uses them.
10. Follow-up (out of scope): migrate the embedded integration-test executers
    (`InProcessGradleExecuter` and friends) onto this codepath.
11. Later, independently: client classpath split lands; optionally add a `FilteringClassLoader`
    between client and daemon loaders; eventually a truly isolated daemon classloader once the
    native-services dependency is separated; potentially serve `--foreground` with a forked
    process kept in the foreground, reusing the monitor client with `DefaultDaemonStarter`.
