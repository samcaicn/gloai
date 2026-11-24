# Voice Model Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put STT, TTS and Realtime models into one explicit voice catalog and one adapter registry, migrate every existing configuration without loss, and leave all media/session behavior unchanged.

**Architecture:** Add an app-side voice catalog that stores services, capability-specific model bindings, selections and verification state in one atomically replaced snapshot. Resolve existing chat-provider accounts or encrypted voice-owned credentials, then create the existing speech implementations through three typed adapter interfaces. Wire each runtime behind the same `legacyMigrationComplete` gate, activate migration only after all three paths are ready, and remove legacy runtime rules only after the new paths pass.

**Tech Stack:** Kotlin, Android, Compose, StateFlow, kotlinx.serialization, OkHttp, Android `SpeechRecognizer` / `TextToSpeech`, JUnit, MockWebServer, existing `KeyCipher`.

**Spec:** `docs/design/specs/2026-11-22-voice-model-integration-design.md`

## Global Constraints

- Work only in `/Users/bolyn/coding-projects/Mika/.claude/worktrees/voice-model-integration` on branch `worktree-voice-model-integration`; never edit `main`, another branch or another worktree. Do not push.
- Standard Android APIs are the main path. No manufacturer/model gates, private API reflection or new native dependency.
- Keep existing media, realtime task execution, approval and audio-route behavior unchanged. Do not fix unrelated playback or call bugs in this branch.
- No new cloud provider and no new library dependency. Reuse the existing protocol implementations and request semantics.
- Existing provider secrets stay encrypted. Never put decrypted credentials in `StateFlow`, logs, exceptions, diagnostics, tests or commits.
- Every new user-facing string needs every language folder currently covered by `python3 tools/i18n/translations.py check`; run that check for each UI task.
- Use `:app:testDebugUnitTest`, not bare `:app:test`. Protocol tests belong in `:core:test` when they touch `:core`.
- Follow TDD for every task: write the listed failing tests, verify the failure, implement the smallest change, verify the pass, run the neighboring regression tests, then commit only the listed files.
- New catalog code lives under `app/src/main/java/com/wangzan101/mika/voice/model/`; UI remains under `app/src/main/java/com/wangzan101/mika/ui/`. Do not move the existing audio engines merely to change their package.
- Legacy data is retained as a read-only rollback source. Do not delete old preference keys, encrypted blobs or account records during this plan.

## Review Focus

- The same `serviceId + modelId` is registered for STT and TTS: each binding and selection must remain independent. Pin in Task 1.
- Migration stops after a credential write but before the catalog snapshot commit: the next launch must still use legacy configuration and retry without duplicates. Pin in Task 3.
- An account changes or disappears while several voice models reference it: no stale credential may be used, verification must reset, and deletion must not leave an unexplained selection. Pin in Tasks 2 and 7.
- Catalog JSON is corrupt or contains an unknown protocol: before migration the app uses legacy configuration; after migration it reports a readable configuration error instead of silently selecting another engine. Pin in Tasks 1 and 7.
- A probe completes after its model/account changed or the user cancelled: its late result must not mark the new configuration verified or overwrite the current error. Pin in Task 7.
- A Realtime profile has several device/mode audio verification records: migration and deletion must preserve the records for exactly that profile. Pin in Tasks 3 and 6.

---

### Task 1: Voice catalog types and atomic persistence

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceCatalog.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceCatalogStore.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/VoiceCatalogStoreTest.kt`

**Interfaces:**
- Consumes: only Kotlin/JVM state and a string backend; no Android framework dependency.
- Produces:
  - `VoiceCapability { STT, TTS, REALTIME }`
  - `VoiceModelRef(serviceId: String, modelId: String, capability: VoiceCapability)`
  - `VoiceService`, `VoiceModel`, `SttBinding`, `TtsBinding`, `RealtimeBinding` and their protocol enums from the spec.
  - `VoiceSelections(stt: SttSelection, tts: VoiceModelRef?, realtime: VoiceModelRef?)`; `SttSelection` is `Auto`, `Off` or `Model(ref)`. System STT uses `Model(VoiceModelRef("android-system", "system-stt", STT))`.
  - `VoiceCatalogStore.state: StateFlow<VoiceCatalogSnapshot>`
  - `upsertService`, `upsertModel`, `deleteModel`, `setSttSelection`, `setTtsSelection`, `setRealtimeSelection`, `markVerification`.
  - `VoiceCatalogBackend.read(): String?` and `write(value: String): Boolean`.
  - `replaceAfterMigration(snapshot: VoiceCatalogSnapshot)` for Task 3.

- [ ] **Step 1: Write failing catalog tests**

Cover one model with two independent bindings, selection round trips, verification invalidation when a binding changes, and corrupt/unknown JSON:

```kotlin
@Test fun `same model keeps STT and TTS bindings independent`() {
    val store = VoiceCatalogStore(FakeBackend())
    store.upsertService(testService)
    store.upsertModel(testModel(stt = sttBinding, tts = ttsBinding))
    assertEquals(sttBinding, store.requireModel(sttRef).stt)
    assertEquals(ttsBinding, store.requireModel(ttsRef).tts)
}

@Test fun `corrupt migrated catalog fails instead of resetting choices`() {
    val store = VoiceCatalogStore(FakeBackend("{broken"), migrated = true)
    assertTrue(store.state.value.error is VoiceCatalogError.ReadFailed)
    assertEquals(null, store.state.value.selections.tts)
}
```

Also test that an unknown protocol preserves the raw snapshot and exposes `UnsupportedSchema`, not `Auto` or an empty catalog.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.VoiceCatalogStoreTest'`  
Expected: FAIL because the catalog types and store do not exist.

- [ ] **Step 3: Implement the catalog and store**

Use immutable snapshots and one serialized document containing services, models, selections, verification state, `schemaVersion = 1` and `legacyMigrationComplete`. Every mutation validates the complete snapshot and performs one backend write before publishing StateFlow. A failed write leaves both disk and state unchanged. `VoiceModel.capabilities` is derived from non-null bindings; do not store a second capability set.

- [ ] **Step 4: Run catalog tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.VoiceCatalogStoreTest'`  
Expected: PASS, including the two Review Focus cases for multi-capability models and corrupt/unknown JSON.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice/model/VoiceCatalog.kt app/src/main/java/com/wangzan101/mika/voice/model/VoiceCatalogStore.kt app/src/test/java/com/wangzan101/mika/voice/model/VoiceCatalogStoreTest.kt
git commit -m "feat(voice): add an atomic voice model catalog"
```

---

### Task 2: Account resolution and three typed adapter families

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceAccountResolver.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceCredentials.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceAdapterRegistry.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceModelError.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/adapters/SttAdapters.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/adapters/TtsAdapters.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/adapters/RealtimeAdapters.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/VoiceAccountResolverTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/VoiceAdapterRegistryTest.kt`

**Interfaces:**
- Consumes: Task 1 catalog; existing `ProviderStore`, `SpeechEngineSpec`, `SpeechSession`, `TtsModel`, `CallRealtimeProvider`.
- Produces:
  - `VoiceCredentials(apiKey: String = "", appId: String = "", accessKey: String = "")`.
  - `ResolvedVoiceModel(service, model, account, configurationFingerprint)`; the fingerprint excludes secrets but includes account endpoint, model and binding.
  - `VoiceAccountResolver.resolve(service: VoiceService): ResolvedAccount`.
  - `SttAdapter.spec(model)` and `start(context, model, language, hooks): SpeechSession`.
  - `TtsAdapter.create(context, model, capabilities): TtsModel`.
  - `RealtimeAdapter.create(model): CallRealtimeProvider`.
  - `VoiceAdapterRegistry.stt(model)`, `tts(model)`, `realtime(model)` returning the resolved adapter or throwing `VoiceModelError.Unsupported`.
  - `VoiceErrorCategory { INCOMPLETE_CONFIG, MISSING_CREDENTIALS, AUTH_FAILED, UNSUPPORTED, NETWORK, TIMEOUT, RATE_LIMITED, PROTOCOL_ERROR, SYSTEM_UNAVAILABLE, CANCELLED }`; every adapter error carries this category plus the original server/protocol detail for diagnostics.

- [ ] **Step 1: Write failing account and registry tests**

Test every currently supported protocol, a missing account, a changed account fingerprint, and a protocol/capability mismatch:

```kotlin
@Test fun `account endpoint change invalidates resolved fingerprint`() {
    val first = resolver.resolve(service)
    providers.changeBaseUrl("p1", "https://new.example/v1")
    val second = resolver.resolve(service)
    assertNotEquals(first.configurationFingerprint, second.configurationFingerprint)
}

@Test fun `registry never returns an adapter for another capability`() {
    val error = assertFailsWith<VoiceModelError.Unsupported> { registry.stt(ttsOnlyResolvedModel) }
    assertEquals(VoiceCapability.STT, error.requested)
}
```

Use fake account sources and fake sessions. Add error-mapping cases for HTTP 401 → `AUTH_FAILED`, HTTP 429 → `RATE_LIMITED`, unknown host → `NETWORK`, socket timeout → `TIMEOUT`, malformed success response → `PROTOCOL_ERROR`, and coroutine cancellation → `CANCELLED` without a failure toast. Do not instantiate Android audio objects in these JVM tests.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.VoiceAccountResolverTest' --tests 'com.wangzan101.mika.voice.model.VoiceAdapterRegistryTest'`  
Expected: FAIL because the resolver and registry do not exist.

- [ ] **Step 3: Implement account resolution and encrypted voice-owned credentials**

Resolve `ExistingProvider` through a small provider-account source interface backed by `ProviderStore`, preserving the active key/key-pool behavior and request headers. Store `VoiceOwned` credentials through `KeyCipher`; expose only `credentialsConfigured` in catalog state. `None` resolves without secrets. Missing/changed accounts fail before any network request.

- [ ] **Step 4: Implement and register existing protocol adapters**

Map each existing STT method and streaming service, both existing TTS protocols, and both Realtime protocols. Reuse existing request/session objects. The Realtime adapter may wrap `ProfileRealtimeProvider` internally, but callers must receive only `CallRealtimeProvider`. Do not register `TtsProtocol.AndroidSystem` yet.

- [ ] **Step 5: Run new and neighboring tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.VoiceAccountResolverTest' --tests 'com.wangzan101.mika.voice.model.VoiceAdapterRegistryTest' --tests 'com.wangzan101.mika.voice.ProviderSpeechTest' --tests 'com.wangzan101.mika.calls.CallVoiceProfileStoreTest'`  
Expected: PASS. Account change/missing cases from Review Focus must be green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice/model app/src/test/java/com/wangzan101/mika/voice/model
git commit -m "feat(voice): resolve accounts through typed model adapters"
```

---

### Task 3: Lossless legacy snapshot migration

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/LegacyVoiceMigration.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/LegacyVoiceSnapshot.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/SpeechChoice.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/VoiceSettings.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/CallVoiceProfiles.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/LegacyVoiceMigrationTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2 and read-only snapshots of all three legacy stores.
- Produces: `LegacyVoiceMigration.migrateIfNeeded(): MigrationResult` with `AlreadyMigrated`, `Migrated`, `Failed(VoiceModelError)`; no app startup wiring yet.

- [ ] **Step 1: Write failing migration fixtures**

Include every `SpeechChoice` variant and all five `TranscribeMethod` values, TTS options, two Realtime profiles of different protocols, and multiple audio verification keys. Assert exact model bindings, options, active selections and verification records after migration.

Add the crash/retry case:

```kotlin
@Test fun `failure after credential write keeps legacy active and retry is idempotent`() {
    backend.failNextCatalogWrite()
    assertTrue(migration.migrateIfNeeded() is MigrationResult.Failed)
    assertFalse(catalog.state.value.legacyMigrationComplete)
    assertEquals(legacySelection, legacySource.currentSelection())

    migration.migrateIfNeeded()
    assertTrue(catalog.state.value.legacyMigrationComplete)
    assertEquals(1, catalog.state.value.services.count { it.id == "stt-doubao" })
    assertEquals(legacySelection, catalog.state.value.selections.stt.toLegacySelection())
}
```

- [ ] **Step 2: Run migration tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.LegacyVoiceMigrationTest'`  
Expected: FAIL because migration does not exist.

- [ ] **Step 3: Add read-only legacy snapshots and implement migration**

Use deterministic IDs: `provider:<providerId>`, `stt-doubao`, `stt-funasr:<normalized-url-hash>`, `stt-bailian:<region>`, and `realtime:<profileId>`. Preserve Realtime profile IDs inside the new service/model mapping so audio verification ownership does not move.

Write owned credentials first, then call `replaceAfterMigration` once with the complete catalog, selections and `legacyMigrationComplete = true`. A crash before that write leaves the legacy path active. Retry reuses IDs and overwrites only its own migration-owned secrets. Never delete legacy data.

- [ ] **Step 4: Run migration and legacy-store regression tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.LegacyVoiceMigrationTest' --tests 'com.wangzan101.mika.voice.SpeechSettingsTest' --tests 'com.wangzan101.mika.settings.VoiceSettingsTest' --tests 'com.wangzan101.mika.calls.CallVoiceProfileStoreTest'`  
Expected: PASS, including the credential-write crash and Realtime verification Review Focus cases.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice/model app/src/main/java/com/wangzan101/mika/voice/SpeechChoice.kt app/src/main/java/com/wangzan101/mika/settings/VoiceSettings.kt app/src/main/java/com/wangzan101/mika/calls/CallVoiceProfiles.kt app/src/test/java/com/wangzan101/mika/voice/model app/src/test/java/com/wangzan101/mika/voice/SpeechSettingsTest.kt app/src/test/java/com/wangzan101/mika/settings/VoiceSettingsTest.kt app/src/test/java/com/wangzan101/mika/calls/CallVoiceProfileStoreTest.kt
git commit -m "feat(voice): migrate legacy speech models without loss"
```

---

### Task 4: Route STT usage through the catalog when migrated

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/SttSelectionController.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/VoiceInput.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/ui/SpeechModelScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelSetupViewModel.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/media/AndroidTranscribePort.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/VoiceInputUnifiedTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/SttSelectionControllerTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/media/AndroidTranscribePortTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3; `SttAdapter.spec/start` and `VoiceCatalogStore.setSttSelection`.
- Produces: `SttSelectionController.current()`, `select(Auto/System/Off)`, `select(ref)`, and a `VoiceInput` path that needs no provider/model-name branch when migrated. `select(System)` writes the built-in `android-system/system-stt` ref.

- [ ] **Step 1: Write failing unified STT tests**

Cover Auto/System/Off behavior, every adapter family, late selection changes, and file transcription only for batch STT. Add a MockWebServer test proving selection sends exactly the explicitly configured protocol and zero other requests:

```kotlin
@Test fun `selecting STT model does not probe other protocols`() {
    controller.select(audioTranscriptionsRef)
    assertEquals(0, server.requestCount)
    voiceInput.start("en-US")
    server.takeRequest().let { assertEquals("/v1/audio/transcriptions", it.path) }
    assertEquals(1, server.requestCount)
}
```

- [ ] **Step 2: Run unified STT tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.VoiceInputUnifiedTest' --tests 'com.wangzan101.mika.voice.model.SttSelectionControllerTest' --tests 'com.wangzan101.mika.media.AndroidTranscribePortTest'`  
Expected: FAIL because unified selection is not wired.

- [ ] **Step 3: Implement the migrated STT path**

When `legacyMigrationComplete` is false, retain the current code unchanged. When true, resolve the current ref and delegate `spec/start` to `SttAdapter`. Register the built-in `android-system` service with `system-stt` and map legacy `SpeechChoice.System` to that model; `Auto` and `Off` remain usage modes. The settings controller writes only the selection; it never calls `Transcriber.detect` or performs a network request. Keep system health and language behavior in `VoiceInput`; when no recognition service exists, keep the model visible with the existing system-settings guidance and never substitute a cloud model.

Change `AndroidTranscribePort` to consume the resolved batch STT model and its explicit binding. System and streaming-only selections still do not expose the file-transcription tool.

- [ ] **Step 4: Run STT and transcription regressions**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*' --tests 'com.wangzan101.mika.media.AndroidTranscribePortTest' --tests 'com.wangzan101.mika.media.ReadAloudTest'`  
Expected: PASS. The explicit-protocol test must show no silence request and no protocol rotation.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice app/src/main/java/com/wangzan101/mika/voice/ui/SpeechModelScreen.kt app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelSetupViewModel.kt app/src/main/java/com/wangzan101/mika/task/TaskRun.kt app/src/main/java/com/wangzan101/mika/media/AndroidTranscribePort.kt app/src/test/java/com/wangzan101/mika/voice app/src/test/java/com/wangzan101/mika/media/AndroidTranscribePortTest.kt
git commit -m "feat(voice): use catalog-backed speech recognition"
```

---

### Task 5: Route TTS usage through the catalog when migrated

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/TtsSelectionController.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/VoiceSettings.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/ui/VoiceModelScreen.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/TtsSelectionControllerTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/media/ReadAloudUnifiedTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3 and `TtsAdapter.create`.
- Produces: `TtsSelectionController.current()`, `select(ref?)`, and `createTts(ref: VoiceModelRef, capabilities: TtsCapabilities): TtsModel` used by chat read-aloud, settings preview and audio tools.

- [ ] **Step 1: Write failing unified TTS tests**

Test that a TTS model not present in legacy `Provider.ttsModels` still creates the correct adapter when migrated, and that voice, speed and streaming preferences survive selection changes:

```kotlin
@Test fun `migrated TTS does not require a legacy chat role marker`() {
    val model = controller.requireModel(ttsRef)
    assertTrue(model.account is ResolvedAccount.ExistingProvider)
    assertTrue(controller.createTts(ttsRef, capabilities) is OpenAiSpeechModel)
    assertFalse(providerStore.provider("p1")!!.ttsModels.contains("voice-1"))
}
```

- [ ] **Step 2: Run unified TTS tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.TtsSelectionControllerTest' --tests 'com.wangzan101.mika.media.ReadAloudUnifiedTest'`  
Expected: FAIL because TTS still requires the legacy marker.

- [ ] **Step 3: Implement the migrated TTS path**

Keep `enabled`, `autoRead`, `speakTool`, save policy, voice, speed, streaming and max characters in `VoiceSettings`. Replace only its migrated model resolution. `MikaApp.speak`, `TaskRun.audioPort` and the settings preview all call the same controller; the legacy branch remains until migration is activated.

- [ ] **Step 4: Run TTS and audio-tool regressions**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.VoiceSettingsTest' --tests 'com.wangzan101.mika.voice.model.TtsSelectionControllerTest' --tests 'com.wangzan101.mika.media.*' && ./gradlew :core:test --tests 'com.wangzan101.mika.core.model.*Tts*'`  
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice/model app/src/main/java/com/wangzan101/mika/settings/VoiceSettings.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/java/com/wangzan101/mika/task/TaskRun.kt app/src/main/java/com/wangzan101/mika/voice/ui/VoiceModelScreen.kt app/src/test/java/com/wangzan101/mika/voice/model app/src/test/java/com/wangzan101/mika/settings/VoiceSettingsTest.kt app/src/test/java/com/wangzan101/mika/media
git commit -m "feat(voice): use catalog-backed speech synthesis"
```

---

### Task 6: Route Realtime selection and connections through the catalog

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/RealtimeSelectionController.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/CallRuntime.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/CallSettings.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/ui/CallSettingsScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voicecall/ui/VoiceCallOverlay.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/RealtimeSelectionControllerTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/calls/CallRealtimeUnifiedTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3 and `RealtimeAdapter.create(model): CallRealtimeProvider`.
- Produces: `RealtimeSelectionController.current()`, `select(ref)`, `provider()`, `credentialsConfigured(ref)`, `connectionTested(ref)`, and audio verification read/write keyed by the migrated model ref.

- [ ] **Step 1: Write failing unified Realtime tests**

Cover both protocols, multiple profiles, exact endpoint/model/speaker mapping, active selection, and every audio verification key. Assert that in-app and carrier entry points receive the same `CallRealtimeProvider` for the same ref.

- [ ] **Step 2: Run unified Realtime tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.RealtimeSelectionControllerTest' --tests 'com.wangzan101.mika.calls.CallRealtimeUnifiedTest'`  
Expected: FAIL because both entry points still read `CallVoiceProfileStore` directly.

- [ ] **Step 3: Implement the migrated Realtime path**

Before migration, keep current behavior. After migration, resolve the active ref through the controller and create the provider from the registry. Keep the existing `CallRealtimeProvider` interface and all session, authorization, prompt, DTMF and audio-verification logic. Update both settings and connection gates to use catalog verification state rather than a second profile copy.

- [ ] **Step 4: Run Realtime and voice-call regressions**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.calls.*' --tests 'com.wangzan101.mika.voicecall.*' --tests 'com.wangzan101.mika.voice.model.RealtimeSelectionControllerTest'`  
Expected: PASS, including preservation and exact ownership of all audio verification records.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice/model app/src/main/java/com/wangzan101/mika/calls app/src/main/java/com/wangzan101/mika/calls/ui/CallSettingsScreen.kt app/src/main/java/com/wangzan101/mika/voicecall/ui/VoiceCallOverlay.kt app/src/test/java/com/wangzan101/mika/voice/model app/src/test/java/com/wangzan101/mika/calls
git commit -m "feat(voice): use catalog-backed realtime models"
```

---

### Task 7: Unified management UI, account rules, probes and activation

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceAccountManager.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceModelProbe.kt`
- Create: `docs/design/prototypes/2026-11-22-voice-catalog.html` (local approval prototype, not shipped)
- Create: `app/src/main/java/com/wangzan101/mika/voice/ui/VoiceCatalogViewModel.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/ui/VoiceCatalogScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/ui/Navigation.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelsScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelSetupViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml` and every translated values file
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/VoiceAccountManagerTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/model/VoiceModelProbeTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/ui/VoiceCatalogViewModelTest.kt`
- Test: `app/src/androidTest/java/com/wangzan101/mika/voice/ui/VoiceCatalogScreenTest.kt`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces:
  - `VoiceAccountManager.saveVoiceAccount(...)`, `deleteProviderWithVoiceUsage(...)`.
  - `VoiceModelProbe.connection(ref)`, `verifyStt(ref): Flow<VoiceProbeState>`, `verifyTts(ref, request): TtsResponse`, `verifyRealtimeConnection(ref)`. STT captures real microphone speech through `VoiceInput`; TTS returns synthesized audio for the ViewModel to start through the existing `VoicePlayer`.
  - `VoiceCatalogViewModel` CRUD, explicit protocol selection, save-unverified, `verifyTts(ref)` synthesis-plus-playback, and probe state keyed by `configurationFingerprint`.
  - App startup calls `LegacyVoiceMigration.migrateIfNeeded()` before any migrated consumer is initialized.

- [ ] **Step 1: Build the local page prototype and obtain user approval**

Create the standalone HTML prototype showing the service groups, capability badges, add/edit form and type-specific test actions. Open it locally for the user and obtain explicit approval before writing the Compose page; record any approved layout changes in the prototype. This step has no production-code dependency.

- [ ] **Step 2: Write failing account, probe and activation tests**

Test voice-only account save with zero chat-probe requests, account deletion with dependent models/selections, corrupt migrated catalog behavior, and startup fallback when migration fails.

Add the late-probe race:

```kotlin
@Test fun `late probe cannot verify changed configuration`() {
    val pending = viewModel.probeAsync(ref)
    viewModel.updateEndpoint(ref, "https://new.example")
    pending.complete(ProbeResult.Success)
    assertEquals(VoiceVerification.Unverified, catalog.verification(ref))
}
```

Also test cancellation, an STT probe sending PCM from a fake microphone rather than generated silence, and TTS verification starting the returned audio in a fake `VoicePlayer`. Keep synthesis and playback errors distinct.

- [ ] **Step 3: Run new tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.VoiceAccountManagerTest' --tests 'com.wangzan101.mika.voice.model.VoiceModelProbeTest' --tests 'com.wangzan101.mika.voice.ui.VoiceCatalogViewModelTest'`  
Expected: FAIL because account management, probes and the management ViewModel do not exist.

- [ ] **Step 4: Implement account management and probes**

Separate account persistence from chat-model probing: the existing chat flow keeps its current validation, while `saveVoiceAccount` performs local validation and optional voice connection tests only. Saving changed account configuration invalidates verification for every referencing model. Provider deletion previews all dependent voice models and, after confirmation, deletes them and clears affected selections in the same catalog snapshot.

Probe success carries the starting `configurationFingerprint`; the store accepts it only if the current fingerprint matches. Cancellation returns without changing verification. STT uses real microphone input and the selected adapter. TTS synthesis must be followed by actual `VoicePlayer` startup before success is shown. Realtime handshake records only connection state. Map UI failures to the Task 2 categories while preserving raw protocol detail for diagnostics.

- [ ] **Step 5: Implement the catalog page and activate migration**

Add `Route.VoiceCatalog` and one settings entry, following the approved prototype. The page groups models by service, displays capability badges, supports existing/new voice accounts, explicit per-capability protocols, edit/delete, save-unverified and type-specific test actions. Model-list responses may supply display metadata, but manual model entry remains available and no model-name capability inference is allowed. Existing STT/TTS/Realtime pages remain the places for usage preferences.

Initialize the catalog and run `migrateIfNeeded()` in `MikaApp.onCreate` before lazy consumers. If migration fails, retain legacy behavior and show the migration error when opening the catalog; if a migrated catalog is corrupt, show `ReadFailed` rather than resetting choices.

- [ ] **Step 6: Run management, UI-copy and full voice regressions**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.model.*' --tests 'com.wangzan101.mika.voice.ui.VoiceCatalogViewModelTest' --tests 'com.wangzan101.mika.settings.ui.models.ModelSetupViewModelTest' --tests 'com.wangzan101.mika.voice.*' --tests 'com.wangzan101.mika.voicecall.*' && python3 tools/i18n/translations.py check && ./gradlew :app:assembleDebugAndroidTest`  
Expected: PASS; voice-only save issues no chat request and late/cancelled probes cannot update new configuration.

- [ ] **Step 7: Run the Compose test on an available Android device**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.voice.ui.VoiceCatalogScreenTest`  
Expected: add/edit/delete and all capability badges work; if no device is connected, record NOT RUN rather than claiming device verification.

- [ ] **Step 8: Commit**

```bash
git add docs/design/prototypes/2026-11-22-voice-catalog.html app/src/main/java/com/wangzan101/mika/voice/model app/src/main/java/com/wangzan101/mika/ui app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/res app/src/test/java/com/wangzan101/mika/voice/model app/src/test/java/com/wangzan101/mika/ui app/src/androidTest/java/com/wangzan101/mika/voice/ui/VoiceCatalogScreenTest.kt
git commit -m "feat(voice): manage all speech models in one catalog"
```

---

### Task 8: Add the built-in Android system TTS model

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voice/SystemTtsEngine.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/SystemTtsModel.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voice/model/adapters/AndroidSystemTtsAdapter.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceAdapterRegistry.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/model/VoiceCatalogStore.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/voice/model/TtsSelectionController.kt`
- Modify: `app/src/main/res/values/strings.xml` and every translated values file
- Test: `app/src/test/java/com/wangzan101/mika/voice/SystemTtsModelTest.kt`
- Test: `app/src/androidTest/java/com/wangzan101/mika/voice/SystemTtsDeviceTest.kt`

**Interfaces:**
- Consumes: Tasks 1–7 and the existing `TtsModel` / `VoicePlayer`.
- Produces: `SystemTtsModel.synthesize(request): TtsResponse` returning a complete `audio/wav`; `SystemTtsEngine` as the fakeable wrapper around Android `TextToSpeech`.

- [ ] **Step 1: Write failing system TTS tests**

Use a fake engine to pin async initialization, `LANG_MISSING_DATA`, `LANG_NOT_SUPPORTED`, synthesis error, empty output, cancellation and `shutdown` on every exit. Assert that the default new-install selection is the built-in system model while an existing migrated cloud selection remains unchanged.

- [ ] **Step 2: Run system TTS tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.SystemTtsModelTest'`  
Expected: FAIL because the system engine/model does not exist.

- [ ] **Step 3: Implement system TTS**

Create/init the engine for a synthesis operation, set the concrete locale from `AppLanguage.resolved`, apply speech rate, call `synthesizeToFile`, wait for `UtteranceProgressListener`, read and delete the temporary WAV, and always `stop`/`shutdown` on completion, error or cancellation. Return `TtsResponse(bytes, "audio/wav")`; declare streaming false. Do not add another playback path or a background service.

Register stable built-in IDs `android-system` / `system-tts`. New empty installations select it by default; unavailable engines or language data keep the model visible with a standard settings/install action. Existing migrated selections are never replaced.

- [ ] **Step 4: Run system TTS, catalog and i18n tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.SystemTtsModelTest' --tests 'com.wangzan101.mika.voice.model.*' --tests 'com.wangzan101.mika.media.ReadAloudUnifiedTest' && python3 tools/i18n/translations.py check`  
Expected: PASS; every fake-engine exit releases resources.

- [ ] **Step 5: Run the real-device system TTS smoke test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.voice.SystemTtsDeviceTest`  
Expected: where the device exposes an engine, synthesis produces non-empty WAV and an unsupported language returns the explicit capability result. Record engine/language skips and NOT RUN separately.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice app/src/main/java/com/wangzan101/mika/voice/model app/src/main/res app/src/test/java/com/wangzan101/mika/voice/SystemTtsModelTest.kt app/src/androidTest/java/com/wangzan101/mika/voice/SystemTtsDeviceTest.kt
git commit -m "feat(voice): add Android system speech synthesis"
```

---

### Task 9: Remove legacy runtime rules and verify the whole branch

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/voice/Transcriber.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ModelOptions.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelSetupViewModel.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelsScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/models/ModelSetupScreen.kt`
- Modify: `docs/dev/architecture.md`
- Test: `app/src/test/java/com/wangzan101/mika/voice/TranscriberTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/settings/ProviderStoreTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/settings/ProviderStoreGroupTest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/settings/ui/models/ModelSetupViewModelTest.kt`
- Test: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/models/ModelSetupScreenTest.kt`

**Interfaces:**
- Consumes: all previous tasks and their green migrated paths.
- Produces: no silence detection or `ttsModels` runtime rules. Legacy serialized data remains readable, and a failed migration can still run the saved legacy selection without either obsolete rule.

- [ ] **Step 1: Write failing cleanup guard tests**

Add tests proving there is no `Transcriber.detect` entry point, no model-name speech filter, and no chat/group/vision behavior that reads `Provider.ttsModels`. Add a failed-migration test proving the saved explicit STT/TTS selections still run without those rules. Keep migration tests proving the old field can still be read as legacy input.

- [ ] **Step 2: Run cleanup guards and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.TranscriberTest' --tests 'com.wangzan101.mika.settings.ProviderStoreTest' --tests 'com.wangzan101.mika.settings.ProviderStoreGroupTest' --tests 'com.wangzan101.mika.settings.ui.models.ModelSetupViewModelTest'`  
Expected: FAIL while legacy runtime calls remain.

- [ ] **Step 3: Remove obsolete runtime rules and update architecture documentation**

Delete silence detection and model-token filtering from every STT selection path. Remove `isTtsOnly` checks from chat, vision, fallback, model-group, STT and TTS runtime paths; legacy fallback resolves its already saved explicit protocol/selection instead. Keep the serialized legacy field, migration DTOs and legacy data so failed migrations and rollback remain possible. Update `docs/dev/architecture.md` with the single catalog, three adapter families and migration boundary; do not document discarded alternatives as current behavior.

- [ ] **Step 4: Run the full JVM, build and translation verification**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest && python3 tools/i18n/translations.py check`  
Expected: all tasks `BUILD SUCCESSFUL`; translation check exits 0. Any failure is fixed before proceeding; do not dismiss a voice-related failure as unrelated.

- [ ] **Step 5: Run the targeted Android regression set**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.voice.ui.VoiceCatalogScreenTest,com.wangzan101.mika.voice.SystemTtsDeviceTest,com.wangzan101.mika.voice.ui.VoiceHoldTest,com.wangzan101.mika.voicecall.ui.VoiceCallPageTest,com.wangzan101.mika.settings.ui.models.ModelSetupScreenTest`  
Expected: PASS on a real Android device. Record NOT RUN if no device is available; do not use emulator-only behavior to change implementation.

- [ ] **Step 6: Perform the migration and protocol smoke matrix on the real test phone**

Run: `./gradlew :app:assemblePerf && adb -s 64cd3508 install -r app/build/outputs/apk/perf/app-perf.apk && adb -s 64cd3508 shell cmd package compile -m speed -f com.wangzan101.mika`  
Expected: the performance APK installs and forced compilation succeeds on the real phone. Use a test account or local fake services, never real user credentials. Verify one legacy configuration of each capability, a fresh TTS-only account, explicit batch STT, streaming STT, streaming TTS, both existing Realtime protocols where credentials permit, and system STT/TTS. Check Logcat/diagnostics for plaintext keys and record the exact untested services instead of claiming full provider coverage.

- [ ] **Step 7: Review the complete branch diff**

Run: `git diff --check origin/main...HEAD && git status --short`  
Expected: no whitespace errors; no changes outside this feature; no legacy data deletion; no media/session rewrites. Request a fresh whole-branch reviewer before marking the branch complete.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/voice app/src/main/java/com/wangzan101/mika/settings app/src/main/java/com/wangzan101/mika/ui app/src/test app/src/androidTest docs/dev/architecture.md docs/design/specs/2026-11-22-voice-model-integration-design.md docs/design/plans/2026-11-22-voice-model-integration.md
git commit -m "refactor(voice): retire legacy speech model rules"
```
