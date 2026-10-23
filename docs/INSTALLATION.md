# Installation

Requires **macOS** and **IntelliJ IDEA 2026.2.3**, or a later update in the **2026.2**
branch. Check the full build number under **Help → About**. The declared range is
`262.10968.63` through `262.*`; support for 2026.3 is not declared.

## Install from a GitHub release

1. Open [Releases](https://github.com/vlaslaptev/project-tab-icons/releases/tag/v0.3.99) and download [project-tab-icons-0.3.99.zip](https://github.com/vlaslaptev/project-tab-icons/releases/download/v0.3.99/project-tab-icons-0.3.99.zip).
   Use the plugin ZIP attached to the release, rather than GitHub's source-code archive.
2. In IDEA, open **Settings → Plugins**.
3. Use the gear menu and select **Install Plugin from Disk…**.
4. Choose the ZIP and restart IDEA if prompted.

The ZIP is unsigned and intended for manual installation. No Marketplace account
or Marketplace token is needed to install it.

## Show project window tabs

Open two or more projects in **separate windows**. Select
**Window → Merge All Project Windows**. macOS now displays a window-tab strip.
The plugin decorates that strip; it does not change editor file tabs.

Open **Settings → Appearance & Behavior → Project Tab Icons**. Choose a preset,
adjust the preview and click **Apply**. Icon assignments and per-project colors
are edited in the table of open projects.

## If nothing changes

- Confirm that the IDE is a local macOS IDEA installation in the supported build range.
- Confirm that project windows are merged into tabs.
- Open a project so the plugin's startup activity runs.
- Confirm **Enable tab customization** is selected and click **Apply**.
- For Git captions, enable the IDE's bundled Git plugin and choose a Git information mode.
- A repository without its first commit cannot supply changed-line counts.

For a report, include the plugin version, exact IDE build, macOS version, theme
and display scale. Remove private paths, project names and unrelated log content.

## Update or remove

Install a newer release ZIP through the same **Install Plugin from Disk…** action.
To stop decorating tabs, turn off **Enable tab customization** and apply the change.
To remove the plugin, disable or uninstall it through the IDE's plugin manager.

The plugin restores the tab presentation it decorated. Project icons saved through
Apply remain native IDEA project icons. Restore your own original artwork through
the IDE's project icon chooser if you want to change those icons too.
