# Upload to GitHub and build the APK

This repository contains one workflow: `.github/workflows/build-apk.yml`.

## Upload

1. Create an empty GitHub repository.
2. Extract the project ZIP.
3. Upload the extracted **contents** to the repository root. The `app`, `gradle` and `.github` folders must be at the top level.
4. Commit the files.

## Build

A build starts after every push. It can also be started manually from **Actions → Build Debug APK → Run workflow**.

The workflow:

1. checks out the repository;
2. installs Temurin JDK 17;
3. installs Android SDK 35 and Build Tools 35.0.0;
4. runs JVM unit tests;
5. builds the debug APK;
6. uploads the APK as a GitHub Actions artifact.

## Download

Open the completed workflow run and download the artifact ending in `-debug-apk`. The installable APK is inside the downloaded artifact ZIP.
