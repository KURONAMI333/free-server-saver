# Release Metadata Reference

Quick-reference card for what to enter when uploading v0.1.0 to Modrinth
and CurseForge. Long-form descriptions live in MODRINTH_DESCRIPTION.md
and CURSEFORGE_DESCRIPTION.md.

## Project identity

| Field | Value |
|---|---|
| Display name | Free Server Saver |
| Slug | `free-server-saver` |
| Mod ID | `freeserversaver` |
| Version | `0.1.0` |
| License | MIT |
| Source | https://github.com/KURONAMI333/free-server-saver |
| Issues | https://github.com/KURONAMI333/free-server-saver/issues |

## Modrinth

### Categories
- Optimization (primary)
- Utility (secondary)
- Management (only if their taxonomy allows a third)

### Environment
- Server: **Required**
- Client: **Optional** (mod runs server-side but doesn't fail on a client-only mod loader)

### Loader
- NeoForge

### Versions
- Minecraft: `1.21.1` only

### Featured image
Set `assets/freeserversaver/icon.png` once the logo is ready, also upload separately as the Modrinth project icon (96×96 minimum, 512×512 preferred PNG).

### Tags (free-text in the description body)
These work like SEO. Sprinkle naturally:
- falixnodes
- minehut
- low-ram
- lag-fix
- gc-pause
- modpack-optimization
- heap-monitor
- free-minecraft-server

## CurseForge

### Categories (CurseForge has fixed top-level categories)
- Server Utility (primary)
- Tweaks (secondary)

### Loader
- NeoForge

### Versions
- Minecraft 1.21.1 only

### Logo
Same icon.png as Modrinth. CurseForge accepts 64×64–400×400 PNG/JPG/GIF.

### Mention in description (for CurseForge full-text search)
- free minecraft server lag
- low ram minecraft server
- minecraft heap optimization
- gc pause fix
- minecraft modpack lag
- 2gb minecraft server

## Pre-upload checklist

- [ ] Logo done (`icon.png` placed at `src/main/resources/assets/freeserversaver/icon.png`)
- [ ] `neoforge.mods.toml` has `logoFile="icon.png"` (set inside `[[mods]]` block)
- [ ] `./gradlew build` SUCCESSFUL
- [ ] Jar tested in `runClient` (covered by VERIFICATION_CHECKLIST.md)
- [ ] CHANGELOG.md v0.1.0 entry final
- [ ] README.md status updated to "Released"
- [ ] git tag `v0.1.0` created on the merge commit
- [ ] GitHub release created with the jar attached
- [ ] Modrinth project created with description copied
- [ ] CurseForge project created with description copied
- [ ] First version uploaded with the release notes link to GitHub

## After-upload monitoring

For the first week:
- Watch Modrinth/CurseForge issue threads daily
- Watch GitHub issues
- Spot-check Reddit / community reports for any mention (or backlash)
- Address load-failure reports as priority 1
- Note feedback for v0.2 backlog
