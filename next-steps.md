# Embedded Daemon — Next Steps

What remains from `embedded-daemon-design.md` that is in scope for this effort but not yet
implemented. Items the design explicitly defers (test executer migration, client classpath split,
classloader filtering / true isolation, log files for embedded daemons, native services feature
upgrades, foreground as a forked process) are not listed here.

Implemented so far: the shared `DaemonServerBootstrap` extraction, `EmbeddedDaemonMain`,
`EmbeddedDaemonStarter`, the CLI rewire (`runBuildInProcess` replaced by the embedded single-use
daemon), the slf4j listener snapshot/restore fix (`Slf4jLoggingSystem`), the JUL handler
snapshot/restore fix (`JavaUtilLoggingSystem`), and the environment-variable mutation fix
(`ApplyClientEnvironmentVariables`).

## Remaining

1. **Tooling API migration** (`ProviderConnection`). Replace the TAPI in-process path
   (`RunInProcess` + `SystemPropertySetterExecuter` + `ForwardStdInToThisProcess`) with the same
   embedded single-use daemon client services the CLI now uses.

2. **`MonitorOutput` protocol command.** New daemon protocol command plus daemon-side handler
   (registered with the non-build handlers, streaming output events using the dispatcher pattern
   from `LogToClient`) plus a small monitor client modeled on `ReportDaemonStatusClient`. Works
   against any daemon and is independently testable against forked ones.

3. **Foreground replacement.** Replace `ForegroundDaemonAction` with a non-single-use embedded
   daemon plus a `MonitorOutput` connection for terminal output. Carries a foreground flag in the
   serialized configuration for the mark-Idle registry behavior.

4. **Delete the replaced machinery.** `RunInProcess`, `ForwardStdInToThisProcess` and
   `ForegroundDaemonAction`, once steps 1 and 3 land.

5. **Client services consolidation.** Fold `DaemonClientServicesSupport`, `DaemonClientServices`
   and `SingleUseDaemonClientServices`/`SingleUseDaemonClient` into a single non-abstract
   `DaemonClientServices` parameterized by `(singleUse, embedded)`. Deliberately kept minimal in
   the current change.

6. **Shutdown hook deregistration.** `Daemon.start()` registers a VM shutdown hook that is never
   removed. Harmless for forked daemons, but a TAPI client running embedded daemons sequentially
   would accumulate hooks (each holding its daemon classloader reachable). Deregister on daemon
   stop. Needed before step 1 ships.
