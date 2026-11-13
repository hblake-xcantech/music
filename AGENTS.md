# Music fork

Read `MUSIC.md` first, then `.claude/project.md` and the files it references, and `docs/CONTRIBUTING.md`. This is Blake's personal Android fork of the Music Assistant mobile app, on branch `music/main`, based on a released upstream tag that matches his server's API schema.

- `main` tracks upstream and carries no personal changes. Never push to `upstream`, and never open upstream issues, PRs or comments: their AI policy forbids autonomous agents and requires a human who reviewed and can explain every change.
- Fork code lives in its own files (`androidApp/.../surfaces/`, `wearApp/`, `wire/`, `res/*music_widget*`). Touch upstream files as little as possible so rebasing onto a new tag stays cheap; list any new touch in `MUSIC.md`.
- Widgets and the watch go through `SurfaceHub`: one server session, the app's own. Do not add a second connection.
- The phone app and the watch app must keep the same application ID and signing key, and the wire (`wire/src`) is compiled into both: change it in one place, bump `MusicWire.VERSION` when the format changes, and install both.
- Preserve the Apache-2.0 license and upstream attribution. Do not use Music Assistant's logo for the fork.
- Keep the server address, tokens, signing secrets and APKs out of Git. Use `opx`, never raw `op`. Do not copy the stock app's data or token into the fork; Blake signs in himself.
- The stock app `io.music_assistant.client` stays installed as the rollback. Do not send playback or volume commands to Blake's real players as a test without his say-so.
- A successful build is not a working phone or watch: say what was verified on a device and what was not, and keep that section of `MUSIC.md` current.
