# Shared development server

This workspace shares a server with the chat and other running services. Keep local
validation within a dedicated resource limit so builds cannot exhaust the host.

- Run local Gradle commands through `scripts/gradle-local.sh`. It caps the whole
  process tree at 4 GiB of memory and 1.5 CPU cores, with two Gradle workers.
- Do not run an Android emulator and Gradle at the same time on this server.
- Run release builds and Windows executable tests in GitHub Actions.
- Keep long-running validation logs in `~/.cache/mihon-ai-validation`, not `/tmp`,
  so they survive a server restart.
- If a capped build runs out of memory, move it to CI; do not remove the cap or
  stop unrelated chat, database, or web services to make room.
