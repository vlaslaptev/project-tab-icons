# Contributing

Start with [compatibility](docs/COMPATIBILITY.md) and
[architecture](docs/ARCHITECTURE.md). Keep the plugin focused on local project
window tabs on macOS and Windows and the declared IntelliJ platform branch.

## Report a problem

Include the plugin version, exact IDE build, operating system version, theme, display scale
and steps to reproduce. State whether the affected icon is custom SVG/PNG or
generated initials. For Git captions, describe the selected display mode and
whether changes are saved, tracked, staged or unstaged.

Remove private project names, source code, credentials and personal paths from
screenshots and logs. Use neutral demo projects in public images.

## Develop

The repository pins Gradle 9.8.1, IntelliJ Platform Gradle Plugin 2.19.0,
Kotlin 2.4.0, JDK 25 and IntelliJ IDEA 2026.2.3.

Inspect changes statically and document exactly what was compiled, packaged or
observed in the running IDE. Keep local automation instructions outside Git.

Produce an installable ZIP with:

```sh
./gradlew buildPlugin -PpluginTarget=macos
./gradlew buildPlugin -PpluginTarget=windows
```

The output goes to `build/macos/distributions` or `build/windows/distributions`.
An optional `localPlatformPath` Gradle
property may select an existing target IDE SDK; keep machine-specific paths out of
tracked files. Do not broaden the supported IDE range without reviewing its APIs.

UI changes should account for light/dark themes, display scaling, custom artwork
and initials. Event listeners, background jobs and decoration must have explicit
cleanup. Preserve existing settings and user-selected project icons.

Keep patches focused and describe the behavior change, evidence and remaining
limits. No publication or remote push is implied by a contribution alone.
