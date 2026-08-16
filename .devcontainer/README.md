# Dev Container for Chrono SMP

This project uses a VS Code dev container to keep the Java toolchain isolated from the host OS.

## Required versions
- Java: 21
- Gradle: 9.2.1 (provided by the project wrapper)

## Open in container
1. Open this folder in VS Code.
2. Run: `Dev Containers: Reopen in Container`
3. Wait for the container to finish building.
4. Run:

```bash
./gradlew build
```

## Notes
The dev container uses the official Java 21 image and relies on the project’s Gradle wrapper instead of a remote GHCR feature, which avoids the registry access issue seen in the original configuration.
