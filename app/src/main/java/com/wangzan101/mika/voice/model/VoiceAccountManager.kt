package com.wangzan101.mika.voice.model

/**
 * Persists voice accounts with local validation only; it never probes chat models or decodes provider keys.
 * Owned credentials are written to the encrypted store and a rotation invalidates every model that referenced
 * the previous secret. Provider deletion is previewed, then removes dependent models and clears affected
 * selections in one catalog snapshot.
 */
class VoiceAccountManager(
    private val catalog: VoiceCatalogStore,
    private val owned: VoiceCredentialStore,
) {
    /**
     * Validates and stores a service's account reference. Changing the account identity (including rotating a
     * voice-owned secret) invalidates verification for every model under that service. The existing chat flow
     * keeps its own validation; this method never issues a chat probe.
     */
    @Synchronized
    fun saveVoiceAccount(service: VoiceService, credentials: VoiceCredentials? = null): VoiceService {
        require(service.id.isNotBlank() && service.name.isNotBlank()) { "Voice service identity is incomplete" }
        when (val account = service.account) {
            is VoiceAccount.ExistingProvider -> require(account.providerId.isNotBlank()) {
                "Voice provider account is incomplete"
            }
            is VoiceAccount.VoiceOwned -> require(account.secretId.isNotBlank()) {
                "Voice owned account is incomplete"
            }
            VoiceAccount.None -> Unit
        }
        val existing = catalog.state.value.services.firstOrNull { it.id == service.id }
        var identityChanged = existing?.account != service.account
        if (service.account is VoiceAccount.VoiceOwned && credentials != null && credentials.configured) {
            val before = owned.configurationToken(service.account.secretId)
            owned.save(service.account.secretId, credentials)
            if (owned.configurationToken(service.account.secretId) != before) identityChanged = true
        }
        if (identityChanged) catalog.invalidateVerification(service.id)
        catalog.upsertService(service)
        return catalog.state.value.services.first { it.id == service.id }
    }

    /** Every capability reference held by voice models under a chat provider; used to preview a provider deletion. */
    fun providerVoiceUsage(providerId: String): List<VoiceModelRef> {
        val snapshot = catalog.state.value
        val serviceIds = snapshot.services
            .filter { (it.account as? VoiceAccount.ExistingProvider)?.providerId == providerId }
            .mapTo(HashSet()) { it.id }
        return snapshot.models.filter { it.serviceId in serviceIds }.flatMap { model ->
            buildList {
                if (model.stt != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT))
                if (model.tts != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS))
                if (model.realtime != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME))
            }
        }
    }

    /**
     * Deletes every voice service that referenced [providerId] (together with its models, selections and
     * verifications) in a single catalog snapshot. Returns the deleted references for confirmation display.
     */
    @Synchronized
    fun deleteProviderWithVoiceUsage(providerId: String): List<VoiceModelRef> {
        val snapshot = catalog.state.value
        val serviceIds = snapshot.services
            .filter { (it.account as? VoiceAccount.ExistingProvider)?.providerId == providerId }
            .mapTo(HashSet()) { it.id }
        val refs = snapshot.models.filter { it.serviceId in serviceIds }.flatMap { model ->
            buildList {
                if (model.stt != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT))
                if (model.tts != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS))
                if (model.realtime != null) add(VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME))
            }
        }
        catalog.deleteServices(serviceIds)
        return refs
    }
}
