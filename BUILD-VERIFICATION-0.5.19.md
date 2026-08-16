# Build / validation notes — 0.5.19

Validated in this environment:

- `DatedOutputLayout` + `ProcessingStatus` compiled with local Kotlin 1.9.0.
- Modified `RecordingProcessingEngine` compiled against focused Android/project stubs to verify Kotlin syntax and method wiring.
- `GlobalOutputReportStore` compiled against focused Android stubs; its local-filesystem branch was executed end-to-end (create 3 global TXT files, append, tail-read, exact-line delete).
- Modified `DashboardApi` compiled against focused Android/project stubs.
- Startup policy (`LabpanoApplication` + `BootReceiver`) compiled against focused Android stubs.
- AndroidManifest.xml, strings.xml and styles.xml parse as valid XML.
- Source checks confirm default Recording path, dated media output, OUTPUT-root global reports, zero-byte guard, and startup OFF policy.

Full Gradle verification was attempted with `./gradlew test --no-daemon`, but the wrapper cannot download Gradle 8.9 because this sandbox cannot resolve/reach `services.gradle.org` (`UnknownHostException`).
