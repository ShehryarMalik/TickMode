# Release and Signing

This document describes the manual APK release process.

## Signing key

Create a release key outside the repository:

    mkdir -p ~/.keys
    chmod 700 ~/.keys

    keytool -genkeypair -v \
      -keystore ~/.keys/tickmode-release.jks \
      -storetype JKS \
      -alias tickmode \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000

Keep the keystore and its passwords private.

Back up the keystore securely. Do not commit it, upload it to GitHub, or include it in source archives.

All future APK updates must use the same signing identity.

## Build

    ./gradlew assembleRelease

The generated APK is:

    app/build/outputs/apk/release/app-release-unsigned.apk

## Align

Set the Android Build Tools location:

    BT="$HOME/Android/Sdk/build-tools/35.0.0"

Align the APK:

    "$BT/zipalign" -v -p 4 \
      app/build/outputs/apk/release/app-release-unsigned.apk \
      app/build/outputs/apk/release/app-release-aligned.apk

## Sign

    "$BT/apksigner" sign \
      --ks "$HOME/.keys/tickmode-release.jks" \
      --ks-key-alias tickmode \
      --out "$HOME/Downloads/TickMode-VERSION.apk" \
      app/build/outputs/apk/release/app-release-aligned.apk

Replace VERSION with the release version.

## Verify

    "$BT/apksigner" verify --verbose --print-certs \
      "$HOME/Downloads/TickMode-VERSION.apk"

Do not publish an APK unless signature verification succeeds.

## Checksum

Create a checksum from the Downloads directory:

    cd "$HOME/Downloads"
    sha256sum TickMode-VERSION.apk > TickMode-VERSION.apk.sha256

The checksum file can be attached to the same GitHub release as the APK.

## Publish

1. Commit and push the source code.
2. Create a version tag, for example v0.6.0.
3. Draft a GitHub Release for that tag.
4. Attach the signed APK.
5. Attach its SHA-256 checksum.
6. Include concise release notes.
7. Mark the release as a pre-release when further device testing is needed.
