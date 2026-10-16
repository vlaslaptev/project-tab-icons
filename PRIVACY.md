# Privacy

Project Tab Icons works locally in the IDE. It has no plugin accounts, analytics,
advertising or independent network requests.

The settings page lists open projects and can read their native project SVG icons.
Importing an image uses the IDE's file chooser. Appearance settings are saved by
the IDE in `projectTabIcons.xml`.

Applying a new project icon writes to that project's native IDEA icon storage and
replaces its light/dark variants. The imported source file is not modified.
Applying appearance settings alone does not rewrite project icons.

The icon catalog is bundled and works offline. Imported SVG files must be
standalone artwork without external resources. Git branch data comes from the
IDE's local repository model; changed-line counts run a local Git diff in the
background, with no fetch. Private project paths and branch names are not sent
to the developer by the plugin.

Compatibility errors are recorded in the IDE's local log. When submitting an
issue, inspect screenshots and log excerpts and remove unrelated private data.
