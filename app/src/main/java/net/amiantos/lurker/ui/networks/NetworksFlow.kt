// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.model.BuiltinNetworks
import net.amiantos.lurkerkit.model.CertificateExport
import net.amiantos.lurkerkit.model.CertificateResult
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ClientCertificatePEM
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPreset
import net.amiantos.lurkerkit.model.NetworkRow
import net.amiantos.lurkerkit.model.NetworkSaveResult
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.session.ChatViewModel

/** Which page a networks dialog opens on. */
internal enum class NetworksStart {
    /** The networks list — iOS's Settings → Networks. */
    List,

    /**
     * The preset picker, then the form — iOS's `showAddNetwork`, reached from the buffer list without
     * going through the list, because the account with no networks is exactly the one that can't
     * find it and shouldn't have to.
     */
    AddNetwork,
}

/** One page of a networks dialog, holding its own state so the page under a pushed one keeps it. */
internal sealed interface NetworksPage {
    class List(val state: NetworksListState) : NetworksPage
    class Picker(val state: NetworkPickerState) : NetworksPage
    class Form(val state: NetworkFormState) : NetworksPage
}

/**
 * Everything one open networks dialog holds: its page stack and each page's state, and the scope
 * their requests run in.
 *
 * ⚠ Not `remember`ed in the dialog, and not `rememberSaveable`d: kept in [NetworksFlowStore], which
 * outlives a configuration change. A rotation recreates the activity, and a form that lost its draft
 * to one would be the worst kind of Android bug — but the draft holds typed passwords and possibly an
 * imported private key, and the saved-instance Bundle is written to disk across a process death, so
 * none of it may go there. In memory for the life of the dialog, then dropped.
 *
 * The scope outlives each page, so a reply that lands after its page was popped still reaches the
 * page under it (a certificate written, then Back before the answer: the list still hears about it
 * — iOS holds its form until the reply for the same reason). It's cancelled with the dialog, when
 * there's no one left to tell; the writes themselves run `NonCancellable`, since a request torn
 * down mid-flight is one the server may or may not have acted on.
 */
internal class NetworksFlow(private val model: ChatViewModel, start: NetworksStart) {
    // `Main`, not `Main.immediate`: the flow is built inside composition (`NetworkSheetsHost`), and
    // its pages start loading in their `init`. Dispatched, those requests begin after the frame
    // rather than inline in the composition pass that created them.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The page stack, root first. Never empty: back from the root dismisses the dialog instead. */
    val pages = mutableStateListOf<NetworksPage>()

    /** Set when the flow is done and the dialog should go — an add from the buffer list, saved. */
    var finished by mutableStateOf(false)
        private set

    private val list: NetworksListState?

    init {
        list = if (start == NetworksStart.List) NetworksListState(model, scope) else null
        pages.add(if (list != null) NetworksPage.List(list) else NetworksPage.Picker(NetworkPickerState(model, scope)))
    }

    /** Pop the top page. False at the root, where back means dismissing the dialog. */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        pages.removeAt(pages.lastIndex)
        return true
    }

    /** Adding starts with "which network?", not with a blank hostname field. */
    fun pushPicker() {
        pages.add(NetworksPage.Picker(NetworkPickerState(model, scope)))
    }

    fun pushAdd(draft: NetworkDraft) {
        lateinit var page: NetworksPage.Form
        page = NetworksPage.Form(
            NetworkFormState(
                ChatViewModelWrites(model),
                scope,
                existing = null,
                initialDraft = draft,
                onSaved = { formSaved(page) },
                onCertificateChanged = {},
            ),
        )
        pages.add(page)
    }

    fun pushEdit(config: NetworkConfig) {
        val list = list
        lateinit var page: NetworksPage.Form
        page = NetworksPage.Form(
            NetworkFormState(
                ChatViewModelWrites(model),
                scope,
                existing = config,
                initialDraft = NetworkDraft(editing = config),
                onSaved = { formSaved(page) },
                onCertificateChanged = { list?.certificateChanged(it, config.id) },
            ),
        )
        pages.add(page)
    }

    /**
     * Back to where the user started, with the change on it.
     *
     * To the root, not one page back: adding goes list → picker → form, so popping one page would
     * land on the picker — the step before the form, not the place the user started. The list
     * re-reads on appearing (the create's roster re-read updates the roster, and the list is a
     * different fetch of a different shape). From the buffer list's Add Network the whole dialog
     * goes: there is nothing to come back to — the new network's buffers arriving IS the result.
     *
     * ⚠ Only while the saving form is still the page on top. The reply can land after the user has
     * gone back and opened something else — another network's form with a draft in it, or the
     * picker for a new one — and unwinding the stack then would throw that page away under them.
     * The save itself still happened, so the list re-reads in place.
     */
    private fun formSaved(page: NetworksPage.Form) {
        if (pages.lastOrNull() !== page) {
            // Still re-read the list: its appearance reload may have finished before this write
            // landed, and would otherwise keep showing the row as it was until the dialog reopens.
            list?.reload()
            return
        }
        if (list != null) {
            while (pages.size > 1) pages.removeAt(pages.lastIndex)
        } else {
            finished = true
        }
    }

    fun close() {
        scope.cancel()
    }
}

/**
 * The open networks dialogs' state, kept across configuration changes — see [NetworksFlow]. Keyed by
 * a token the opener saves, so a dialog restored after a rotation finds its flow, and one restored
 * after a process death (token saved, store empty) starts fresh at its root.
 */
internal class NetworksFlowStore : ViewModel() {
    private val flows = mutableMapOf<String, NetworksFlow>()

    fun flow(token: String, create: () -> NetworksFlow): NetworksFlow = flows.getOrPut(token, create)

    fun discard(token: String) {
        flows.remove(token)?.close()
    }

    override fun onCleared() {
        flows.values.forEach { it.close() }
        flows.clear()
    }
}

/**
 * The networks list's state — lurker-ios's `NetworksViewController`, minus the table. The rules are
 * [NetworksListModel]'s.
 */
internal class NetworksListState(private val model: ChatViewModel, private val scope: CoroutineScope) {
    var load by mutableStateOf<NetworksLoad>(NetworksLoad.Loading)
        private set

    /** The most recent refusal, under the row it belongs to. One slot: a new one moves it. */
    var actionError by mutableStateOf<RowError?>(null)
        private set

    /** The network waiting on the delete confirmation. */
    var confirmingDelete by mutableStateOf<NetworkConfig?>(null)

    /**
     * Which fetch is the current one. Several call sites start one — every appearance, a completed
     * delete, Try Again — and nothing orders their replies.
     */
    private var generation = 0

    /** The row model each network was last drawn with — see [NetworksListModel.movedIds]. */
    private var shownRows: Map<Int, NetworkRow> = emptyMap()
    private var live: Map<Int, ConnectionState> = NetworksListModel.liveStates(model.state)

    val configs: List<NetworkConfig> get() = NetworksListModel.configs(load)

    init {
        reload()
    }

    /**
     * Re-read on every appearance, not just the first: a network added or deleted from the web while
     * this screen sat behind another is exactly the kind of change nobody is going to push at us —
     * the roster frame carries names, not hosts or ports. The first load is already in flight.
     */
    fun appeared() {
        if (load is NetworksLoad.Loading) return
        reload()
    }

    fun reload() {
        load = NetworksListModel.beforeReload(load)
        generation += 1
        val asked = generation
        scope.launch {
            val fetched = model.networkConfigs()
            // ⚠ Two deletes in quick succession start two fetches, and nothing orders their replies.
            // Applying a stale one puts the network the user just deleted back on screen as a row
            // that can only answer with an error.
            if (asked != generation) return@launch
            load = NetworksListModel.afterFetch(load, fetched)
            rendered()
        }
    }

    /** The list changed: drop a refusal for a network that's gone, and record what's drawn. */
    private fun rendered() {
        actionError = NetworksListModel.retainedError(actionError, configs)
        shownRows = NetworksListModel.shownRows(configs, live)
    }

    /**
     * The connections moved. A row that moved on its own has retired whatever refusal was pinned
     * under it — the state it was describing is no longer the state.
     */
    fun onLiveStates(next: Map<Int, ConnectionState>) {
        live = next
        val moved = NetworksListModel.movedIds(configs, shownRows, next)
        if (moved.isEmpty()) return
        if (actionError?.id in moved) actionError = null
        shownRows = NetworksListModel.shownRows(configs, next)
    }

    fun perform(action: NetworkAction, config: NetworkConfig) {
        when (action) {
            NetworkAction.Delete -> confirmingDelete = config
            NetworkAction.Connect, NetworkAction.Disconnect, NetworkAction.Reconnect ->
                run(config.id) { model.perform(action, config.id) }
        }
    }

    /**
     * Run a verb that answers with an error message or null, and pin the refusal under its row.
     *
     * Nothing is applied optimistically. Every one of these is answered by the server the long way
     * round — the connection transitions arrive as `state` events, the delete by the roster it
     * triggers — and a row that moved on its own would be claiming an outcome the server hasn't
     * reached yet. On a paused account (lurker-ios#18) they are all refused, which is the case that
     * makes the message worth showing rather than swallowing.
     */
    private fun run(id: Int, verb: suspend () -> String?) {
        // Whatever the last attempt said is now stale — a new attempt is under way.
        actionError = null
        scope.launch {
            val message = withContext(NonCancellable) { verb() } ?: return@launch
            actionError = RowError(id, message)
        }
    }

    fun delete(config: NetworkConfig) {
        confirmingDelete = null
        scope.launch {
            val message = withContext(NonCancellable) { model.perform(NetworkAction.Delete, config.id) }
            if (message != null) {
                actionError = RowError(config.id, message)
                return@launch
            }
            // The roster re-read the delete triggers has already updated the store; this screen's
            // own list is a separate fetch and has to catch up on its own.
            reload()
        }
    }

    /** Put a certificate the form just wrote onto this list's copy of the row — see [NetworksListModel.withCertificate]. */
    fun certificateChanged(certificate: ClientCertificate?, id: Int) {
        val next = NetworksListModel.withCertificate(load, id, certificate) ?: return
        // A fetch already out was asked before the change, and would put the old row back.
        generation += 1
        load = next
        rendered()
    }
}

/** The preset picker's state — lurker-ios's `NetworkPickerViewController`. The rules are [NetworkPickerModel]'s. */
internal class NetworkPickerState(model: ChatViewModel, scope: CoroutineScope) {
    /** Every preset on offer, before the search narrows it. */
    var offered by mutableStateOf<List<NetworkPreset>>(BuiltinNetworks.all)
        private set
    var allowsCustom by mutableStateOf(true)
        private set
    var query by mutableStateOf("")

    init {
        // The bundled catalogue is already on the device, so this never needs a spinner: the fetch
        // only adds this instance's own networks and its policy, and on failure we keep what we
        // shipped with, because a request that didn't arrive is no reason to refuse to add a
        // network. (It can still come back EMPTY — see `NetworkPickerModel.placeholder`.)
        scope.launch {
            val presets = model.networkPresets() ?: return@launch
            offered = presets.offered
            allowsCustom = presets.allowUserDefined
        }
    }
}

/**
 * What the network form asks of the server — the view model's network and certificate verbs, behind
 * an interface so the form's previews can draw it without one. Every one of these WRITES.
 */
internal interface NetworkFormWrites {
    suspend fun createNetwork(draft: NetworkDraft): NetworkSaveResult
    suspend fun updateNetwork(id: Int, draft: NetworkDraft): NetworkSaveResult
    suspend fun attachCertificate(networkId: Int, source: CertificateSource): CertificateResult
    suspend fun removeCertificate(networkId: Int): CertificateResult
    suspend fun exportCertificate(networkId: Int): CertificateExport
}

/** [NetworkFormWrites] through the view model — the real thing. */
internal class ChatViewModelWrites(private val model: ChatViewModel) : NetworkFormWrites {
    override suspend fun createNetwork(draft: NetworkDraft) = model.createNetwork(draft)
    override suspend fun updateNetwork(id: Int, draft: NetworkDraft) = model.updateNetwork(id = id, draft = draft)
    override suspend fun attachCertificate(networkId: Int, source: CertificateSource) =
        model.attachCertificate(networkId = networkId, source = source)
    override suspend fun removeCertificate(networkId: Int) = model.removeCertificate(networkId = networkId)
    override suspend fun exportCertificate(networkId: Int) = model.exportCertificate(networkId = networkId)
}

/**
 * The network form's state — lurker-ios's `NetworkFormViewController`, minus the table. The rules
 * are [NetworkFormModel]'s.
 *
 * @param existing the network being edited, or null when adding.
 * @param onSaved what to do once the write has landed. The form doesn't navigate itself: where the
 *   user should end up afterwards is the flow's business, and it certainly isn't "back to the picker".
 * @param onCertificateChanged told about every certificate the form writes — see [certificate].
 */
internal class NetworkFormState(
    private val writes: NetworkFormWrites,
    private val scope: CoroutineScope,
    val existing: NetworkConfig?,
    initialDraft: NetworkDraft,
    private val onSaved: () -> Unit,
    private val onCertificateChanged: (ClientCertificate?) -> Unit,
) {
    val isEditing: Boolean get() = existing != null

    var draft by mutableStateOf(initialDraft)
        private set

    /**
     * The port fields' text, kept beside the draft's numbers so the field shows what was typed — a
     * field rebuilt from the parsed number would swallow a lone "0" or a stray character as it's
     * typed. The number is what's saved; see [NetworkFormModel.parsePort].
     */
    var portText by mutableStateOf(NetworkFormModel.portText(initialDraft.port))
        private set
    var proxyPortText by mutableStateOf(NetworkFormModel.portText(initialDraft.proxy.port))
        private set

    /**
     * Why the last save was refused. The server's wording where it gave any — it knows about blocked
     * hosts and paused accounts, and this client is guessing.
     */
    var error by mutableStateOf<String?>(null)
        private set

    /** Bumped with every refusal shown, so the screen scrolls up to it even when the words repeat. */
    var errorShown by mutableIntStateOf(0)
        private set

    /** The last [errorShown] the screen scrolled up for — see `NetworkFormPage`. Not state: nothing draws it. */
    var errorScrolledTo = 0

    /**
     * The network's certificate as the server last described it.
     *
     * Not read from [existing] after the form opens: the certificate rows write immediately and
     * answer with the new description, and [existing] is the row as it was when the form opened.
     *
     * ⚠⚠ Every change goes back to the list. It was written without a Save, so Back tells the list
     * nothing, and the list is where this form is reopened from: reopened from a stale row, it offered
     * Generate over a certificate the user had just registered — which the server's attach replaces
     * without asking.
     */
    var certificate by mutableStateOf(existing?.clientCertificate)
        private set

    /**
     * A certificate request is out. Save waits for it, because the server checks TLS on each side
     * separately: an attach landing alongside a save that turns TLS off would leave a certificate on
     * a network with no handshake to present it in, which can never connect.
     */
    var certificateBusy by mutableStateOf(false)
        private set
    var certificateError by mutableStateOf<String?>(null)
        private set

    /** An export is being fetched, so a second tap doesn't queue a second save dialog. */
    var exporting by mutableStateOf(false)
        private set

    /**
     * An exported pair waiting on the user to choose where it goes. In memory only, and dropped the
     * moment it's written or the choice is cancelled: it holds an unencrypted private key.
     */
    var pendingExport by mutableStateOf<String?>(null)
        private set

    /**
     * The system's save dialog is up for [pendingExport]. Held here, which outlives a rotation, so a
     * recreated screen doesn't open a second one over the first.
     */
    var exportPickerOpen = false

    var saving by mutableStateOf(false)
        private set

    var confirmingRemove by mutableStateOf(false)

    val sections: List<FormSection>
        get() = NetworkFormModel.sections(existing, draft, error, certificate, certificateError)

    val canAddCertificate: Boolean
        get() = NetworkFormModel.canAddCertificate(draft, existing, certificateBusy, saving)

    val saveEnabled: Boolean get() = !saving && !certificateBusy

    fun edit(transform: (NetworkDraft) -> NetworkDraft) {
        draft = transform(draft)
    }

    fun setPort(text: String) {
        portText = text
        draft = draft.copy(port = NetworkFormModel.parsePort(text))
    }

    fun setProxyPort(text: String) {
        proxyPortText = text
        draft = draft.copy(proxy = draft.proxy.edited(port = NetworkFormModel.parsePort(text)))
    }

    /** An untouched default port follows the type, so that field may change too. */
    fun setProxyType(type: ProxyType) {
        val before = draft.proxy.port
        draft = draft.copy(proxy = draft.proxy.setType(type))
        if (draft.proxy.port != before) proxyPortText = NetworkFormModel.portText(draft.proxy.port)
    }

    // MARK: - Certificates

    /**
     * Generate or import. Adding, the choice waits in the draft for the create request, which
     * attaches it before the first dial. Editing, it's written now.
     */
    fun addCertificate(source: CertificateSource) {
        certificateError = null
        val existing = existing
        if (existing == null) {
            draft = draft.copy(certificate = source)
            return
        }
        runCertificate { writes.attachCertificate(existing.id, source) }
    }

    fun undoCertificate() {
        draft = draft.copy(certificate = null)
    }

    /** Picked files, read and joined: the pair often lives in two. */
    fun importFiles(read: suspend () -> String) {
        scope.launch {
            when (val reading = ClientCertificatePEM.reading(read())) {
                is ClientCertificatePEM.Reading.Ready -> addCertificate(reading.source)
                is ClientCertificatePEM.Reading.Refused -> certificateError = reading.message
            }
        }
    }

    fun requestRemove() {
        if (existing == null || certificateBusy || saving) return
        confirmingRemove = true
    }

    fun remove() {
        confirmingRemove = false
        val existing = existing ?: return
        runCertificate { writes.removeCertificate(existing.id) }
    }

    private fun runCertificate(request: suspend () -> CertificateResult) {
        certificateBusy = true
        certificateError = null
        scope.launch {
            val result = withContext(NonCancellable) { request() }
            certificateBusy = false
            when (result) {
                is CertificateResult.Updated -> {
                    certificate = result.certificate
                    onCertificateChanged(result.certificate)
                }
                is CertificateResult.Failure -> certificateError = result.message
            }
        }
    }

    fun export() {
        val existing = existing ?: return
        if (exporting || pendingExport != null) return
        exporting = true
        scope.launch {
            val result = writes.exportCertificate(existing.id)
            exporting = false
            when (result) {
                is CertificateExport.Pem -> pendingExport = result.pem
                is CertificateExport.Failure -> certificateError = result.message
            }
        }
    }

    /** The user chose where the export goes; [write] puts it there and says whether it did. */
    fun finishExport(write: suspend (String) -> Boolean) {
        val pem = pendingExport ?: return
        pendingExport = null
        exportPickerOpen = false
        scope.launch {
            if (!write(pem)) certificateError = "Couldn't save the certificate file."
        }
    }

    fun cancelExport() {
        pendingExport = null
        exportPickerOpen = false
    }

    // MARK: - Saving

    fun save() {
        // Not while a certificate request is out — see `certificateBusy`.
        if (saving || certificateBusy) return
        // Whatever the last attempt was refused for is now stale — the user has had a chance to fix
        // it, and leaving it up next to a disabled Add tells them a field they just corrected is
        // still wrong for the length of the round trip.
        error = null
        val problem = NetworkFormModel.saveProblem(draft, certificate)
        if (problem != null) {
            show(problem)
            return
        }
        saving = true
        val sent = draft
        scope.launch {
            val result = withContext(NonCancellable) {
                val existing = existing
                if (existing != null) writes.updateNetwork(existing.id, sent) else writes.createNetwork(sent)
            }
            saving = false
            when (result) {
                // ⚠ `SavedWithoutDetail` leaves too. The write landed; only its reply was unreadable.
                // Keeping the form open would invite the retry that creates the network twice — see
                // `NetworkSaveResult`.
                is NetworkSaveResult.Saved, NetworkSaveResult.SavedWithoutDetail -> onSaved()
                is NetworkSaveResult.Failure -> show(result.message)
            }
        }
    }

    private fun show(message: String) {
        error = message
        errorShown += 1
    }
}
