# Contributing

Start with [compatibility](docs/COMPATIBILITY.md) and
[architecture](docs/ARCHITECTURE.md). Keep the plugin focused on local macOS
project window tabs and the declared IntelliJ platform branch.

## Report a problem

Include the plugin version, exact IDE build, macOS version, theme, display scale
and steps to reproduce. State whether the affected icon is custom SVG/PNG or
generated initials. For Git captions, describe the selected display mode and
whether changes are saved, tracked, staged or unstaged.

Remove private project names, source code, credentials and personal paths from
screenshots and logs. Use neutral demo projects in public images.

## Develop

The repository pins Gradle 9.8.1, IntelliJ Platform Gradle Plugin 2.19.0,
Kotlin 2.4.0, JDK 25 and IntelliJ IDEA 2026.2.3.

Read `AGENTS.md` before automated work. JVM compilation and packaging are allowed
for this project. Standalone JVM tests, linters, checks and Plugin Verifier commands
are outside this workspace's workflow. Inspect changes statically; document exactly
what was compiled, packaged or observed in the running IDE.

Produce an installable ZIP with:

```sh
./gradlew buildPlugin
```

The output goes to `build/distributions`. An optional `localPlatformPath` Gradle
property may select an existing target IDE SDK; keep machine-specific paths out of
tracked files. Do not broaden the supported IDE range without reviewing its APIs.

UI changes should account for light/dark themes, display scaling, custom artwork
and initials. Event listeners, background jobs and decoration must have explicit
cleanup. Preserve existing settings and user-selected project icons.

Keep patches focused and describe the behavior change, evidence and remaining
limits. No publication or remote push is implied by a contribution alone.
