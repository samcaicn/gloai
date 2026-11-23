package com.wangzan101.mika.voice.model

/**
 * One entry of a vendor's model list. [caps] is what the vendor (or the model's name) says it does; empty means
 * unknown, and such a model is offered for listening and speaking both instead of being dropped.
 */
data class ListedModel(
    val id: String,
    val name: String = id,
    val caps: Set<VoiceCapability> = emptySet(),
    /** Voices that belong to this model alone (a vendor whose voices are per model). */
    val voices: List<String> = emptyList(),
    val voiceNames: Map<String, String> = emptyMap(),
)

/** What a vendor's list endpoints returned: its models, and the voices every speak model of the account can use. */
data class VoiceListing(
    val models: List<ListedModel> = emptyList(),
    val voices: List<String> = emptyList(),
    val voiceNames: Map<String, String> = emptyMap(),
)

/** Asks one vendor what models (and voices) this account has. */
fun interface VoiceModelLister {
    suspend fun list(service: VoiceService, credentials: VoiceCredentials, models: List<VoiceModel>): VoiceListing
}

/**
 * Turns list results and typed-in ids into catalog entries. A new model borrows the binding of the vendor's own
 * model for that capability (same protocol, endpoint and region), so every listed model runs through the protocol
 * the vendor already speaks; whether a given model accepts it is what the connection test shows.
 */
object VoiceModelLists {
    /**
     * The model for [cap] whose binding new models copy: the service's own (built-in first), else the vendor's
     * built-in model for that capability ([fallback]), for an account an earlier version set up with fewer models.
     */
    fun template(models: List<VoiceModel>, cap: VoiceCapability, fallback: List<VoiceModel> = emptyList()): VoiceModel? =
        models.filter { cap in it.capabilities }.minByOrNull { it.origin.ordinal }
            ?: fallback.firstOrNull { cap in it.capabilities }

    /** The capabilities new models of this service can be given: the ones some model has a binding for. */
    fun templated(models: List<VoiceModel>, fallback: List<VoiceModel> = emptyList()): Set<VoiceCapability> =
        VoiceCapability.entries.filter { template(models, it, fallback) != null }.toSet()

    /** A fresh entry for [id] with a binding per capability in [caps], copied from the templates. */
    fun derive(
        models: List<VoiceModel>, serviceId: String, id: String, name: String, caps: Set<VoiceCapability>,
        origin: VoiceModelOrigin, guessed: Boolean = false, retarget: (VoiceModel) -> VoiceModel = { it },
        fallback: List<VoiceModel> = emptyList(),
    ): VoiceModel? {
        fun of(cap: VoiceCapability) = if (cap in caps) template(models, cap, fallback) else null
        if (VoiceCapability.entries.none { of(it) != null }) return null
        val model = VoiceModel(
            serviceId, id, name.ifBlank { id },
            stt = of(VoiceCapability.STT)?.stt,
            tts = of(VoiceCapability.TTS)?.tts,
            realtime = of(VoiceCapability.REALTIME)?.realtime,
            origin = origin, guessed = guessed,
        )
        return retarget(model)
    }

    /**
     * The service's models after a refresh: built-in and typed-in entries stay; listed entries are rebuilt from
     * [listing]; a previously listed entry that is gone from the list survives only while [keep] holds its id
     * (it is selected somewhere). Fetched voices replace a speak model's voice list.
     */
    fun merge(
        current: List<VoiceModel>, serviceId: String, listing: VoiceListing, keep: Set<String> = emptySet(),
        fallback: List<VoiceModel> = emptyList(), retarget: (VoiceModel) -> VoiceModel = { it },
    ): List<VoiceModel> {
        val fixed = current.filter { it.origin != VoiceModelOrigin.Listed }
        val fixedIds = fixed.mapTo(HashSet()) { it.id }
        val templates = templated(current, fallback)
        val listed = listing.models.distinctBy { it.id }.filter { it.id.isNotBlank() && it.id !in fixedIds }.mapNotNull { entry ->
            val known = entry.caps intersect templates
            val guessed = known.isEmpty()
            val caps = if (guessed) templates intersect GUESS else known
            derive(current, serviceId, entry.id, entry.name, caps, VoiceModelOrigin.Listed, guessed, retarget, fallback)
        }
        val listedIds = listed.mapTo(HashSet()) { it.id }
        val stale = current.filter { it.origin == VoiceModelOrigin.Listed && it.id !in listedIds && it.id in keep }
        val byId = listing.models.associateBy { it.id }
        return (fixed + listed + stale).map { it.withVoices(byId[it.id], listing) }
    }

    /** A typed-in model id for [cap]; an existing entry with that id gains the capability instead. */
    fun manual(
        current: List<VoiceModel>, serviceId: String, id: String, cap: VoiceCapability,
        fallback: List<VoiceModel> = emptyList(), retarget: (VoiceModel) -> VoiceModel = { it },
    ): VoiceModel? {
        val clean = id.trim()
        if (clean.isEmpty()) return null
        val fresh = derive(current, serviceId, clean, clean, setOf(cap), VoiceModelOrigin.Manual, retarget = retarget, fallback = fallback) ?: return null
        val existing = current.firstOrNull { it.id == clean } ?: return fresh
        if (cap in existing.capabilities) return existing
        return existing.copy(
            stt = existing.stt ?: fresh.stt, tts = existing.tts ?: fresh.tts, realtime = existing.realtime ?: fresh.realtime,
            guessed = false,
        )
    }

    private fun VoiceModel.withVoices(entry: ListedModel?, listing: VoiceListing): VoiceModel {
        val binding = tts ?: return this
        val (voices, names) = when {
            entry != null && entry.voices.isNotEmpty() -> entry.voices to entry.voiceNames
            listing.voices.isNotEmpty() -> listing.voices to listing.voiceNames
            else -> return this
        }
        val distinct = voices.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return this
        val voice = binding.voice.takeIf { it in distinct } ?: distinct.first()
        return copy(tts = binding.copy(voice = voice, voices = distinct, voiceNames = names.filterKeys { it in distinct }))
    }

    /** What an unrecognized model is offered as; realtime needs a session protocol a plain list never implies. */
    private val GUESS = setOf(VoiceCapability.STT, VoiceCapability.TTS)
}

/** Outcome of one list refresh, for the vendor page's status line. */
sealed interface VoiceListRefresh {
    /** The vendor has no list endpoint; its built-in models and typed-in ids are all there is. */
    data object NoList : VoiceListRefresh
    data class Done(val models: Int, val voices: Int) : VoiceListRefresh
    data class Failed(val error: VoiceModelError) : VoiceListRefresh
}

/** Fetches a vendor service's model list with its saved account and writes the merged result into the catalog. */
class VoiceModelRefresher(private val catalog: VoiceCatalogStore, private val resolver: VoiceAccountResolver) {
    suspend fun refresh(serviceId: String): VoiceListRefresh {
        val vendor = VoiceVendors.forService(serviceId) ?: return VoiceListRefresh.NoList
        val lister = vendor.lister ?: return VoiceListRefresh.NoList
        val snapshot = catalog.state.value
        val service = snapshot.services.firstOrNull { it.id == serviceId }
            ?: return VoiceListRefresh.Failed(VoiceModelError.IncompleteConfig("Voice service is not registered"))
        val current = snapshot.models.filter { it.serviceId == serviceId }
        val listing = try {
            lister.list(service, resolver.resolve(service).credentials, current)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return VoiceListRefresh.Failed(VoiceModelError.from(e))
        }
        // 刷新前的快照可能已过时(期间用户选了别的),以写入时的选择为准
        val latest = catalog.state.value
        val merged = VoiceModelLists.merge(
            latest.models.filter { it.serviceId == serviceId }, serviceId, listing, selectedIds(latest, serviceId),
            vendor.models(serviceId), vendor.retarget,
        )
        catalog.replaceModels(serviceId, merged)
        val voices = merged.mapNotNull { it.tts }.flatMap { it.voices }.distinct().size
        return VoiceListRefresh.Done(merged.size, voices)
    }

    /** Adds a typed-in model id for [cap] under [serviceId]; null when the service has no model to copy a binding from. */
    fun addManual(serviceId: String, id: String, cap: VoiceCapability): VoiceModelRef? {
        val current = catalog.state.value.models.filter { it.serviceId == serviceId }
        val vendor = VoiceVendors.forService(serviceId)
        val model = VoiceModelLists.manual(
            current, serviceId, id, cap, vendor?.models(serviceId).orEmpty(), vendor?.retarget ?: { it },
        ) ?: return null
        catalog.upsertModel(model)
        return VoiceModelRef(serviceId, model.id, cap)
    }

    private fun selectedIds(snapshot: VoiceCatalogSnapshot, serviceId: String): Set<String> = with(snapshot.selections) {
        listOfNotNull((stt as? SttSelection.Model)?.ref, tts, realtime, callRealtime, callStt, callTts)
            .filter { it.serviceId == serviceId }.mapTo(HashSet()) { it.modelId }
    }
}
