# Generator Guardrails (MASTER)

This document defines **generation-level rules** that apply to ALL projects
generated from OpenSpec specifications, regardless of the AI engine used.

These rules are **not functional requirements**.
They exist to prevent known generation failures and repeated technical mistakes.

---

## General rules (all projects)

- Generated output must be complete and non-truncated.
- No partially written files are allowed.
- The project must compile or sync without errors on first execution.

---

## Android projects

### AndroidX
- Every Android project MUST include a `gradle.properties` file at the root
  of the Android project (`android/gradle.properties`).
- The following properties MUST be present:

### CameraX ImageProxy lifecycle (critical)
- When using CameraX ImageAnalysis, `ImageProxy` MUST be closed exactly once.
- The analysis code MUST NOT access `Image` / `planes` after `ImageProxy.close()`.
- Avoid launching async analysis that outlives the `ImageProxy` unless you copy required buffers first.
- If analysis is throttled (e.g., every 5 seconds), non-processed frames MUST be closed immediately.

```properties
android.useAndroidX=true
android.enableJetifier=true