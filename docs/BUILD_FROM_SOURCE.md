# Build from Source

## Requirements

- Linux, macOS or Windows
- JDK 17
- Android SDK
- Android SDK Platform 35
- Android Build Tools 35.0.0

The project includes the Gradle wrapper, so a separate Gradle installation is not required.

## Get the source

Clone the repository:

    git clone https://github.com/ShehryarMalik/TickMode.git
    cd TickMode

## Build a debug APK

On Linux or macOS:

    chmod +x gradlew
    ./gradlew assembleDebug

On Windows:

    gradlew.bat assembleDebug

The debug APK is generated at:

    app/build/outputs/apk/debug/app-debug.apk

## Build a release APK

Run:

    ./gradlew assembleRelease

The release output is generated at:

    app/build/outputs/apk/release/app-release-unsigned.apk

This output is unsigned. Creating a distributable release APK requires a release signing key and the Android signing tools.

See [Release and Signing](RELEASING.md).

## Notes

Gradle downloads dependencies from Google's Maven repository and Maven Central when they are not already cached.

The first build can take longer than later builds. Subsequent builds normally reuse downloaded dependencies and incremental build outputs.
