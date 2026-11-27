- The desktop app now ships as installers with their own runtime: a `.deb` for Linux x64, a `.dmg` for Apple silicon
  macOS and an `.msi` for Windows x64. The macOS app is ad hoc signed, not notarized: try to open it, then allow it
  under System Settings > Privacy & Security > Open Anyway. The jars stay for Linux and macOS self-update; an
  installed app opens the download page for an update instead of swapping files.
