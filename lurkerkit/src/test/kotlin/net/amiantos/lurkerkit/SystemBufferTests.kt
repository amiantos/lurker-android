// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system buffer rendered empty for its whole life on iOS because the chat screen filtered
 * every buffer by `isSpeech`, and *no* system line is speech. These lock the contract
 * that made that a silent failure rather than a crash.
 */
class SystemBufferTests {

    // MARK: - The bug

    @Test
    fun testSystemBufferRendersSystemLines() {
        // The regression. Every line the server writes to the system buffer is
        // `type: "system"`, so a buffer that only renders speech renders nothing.
        assertTrue(BufferKind.System.renders(EventType.System))
        assertFalse(EventType.System.isSpeech, "the type that IS the system buffer is not speech")
    }

    @Test
    fun testChannelsAndDmsRenderSpeechAndActivity() {
        // A channel shows the conversation *and* its structural traffic — joins, modes, and
        // the rest — which consolidate rather than spam (see ConsolidationTests). What it
        // never carries is the app/server-scoped `system`/`motd` or the unmodeled `Other`
        // state noise (usermode/lag/peer-presence), so those stay filtered out.
        for (kind in listOf(BufferKind.Channel, BufferKind.Dm)) {
            assertTrue(kind.renders(EventType.Message), "$kind")
            assertTrue(kind.renders(EventType.Action), "$kind")
            assertTrue(kind.renders(EventType.Notice), "$kind")
            for (activity in listOf(
                EventType.Join, EventType.Part, EventType.Quit, EventType.Nick, EventType.Kick, EventType.Mode,
                EventType.Topic, EventType.Invite,
            )) {
                assertTrue(kind.renders(activity), "$kind should render $activity")
            }
            assertFalse(kind.renders(EventType.System), "$kind")
            assertFalse(kind.renders(EventType.Motd), "$kind")
            assertFalse(kind.renders(EventType.Other), "$kind")
        }
    }

    /**
     * The same bug as the system buffer's, one kind over: a server log is a log, and only
     * one of the five types the server actually routes to `:server:` is speech, so
     * `isSpeech` hid the whole MOTD, every mode line, and every server error — leaving a
     * buffer that looked nearly empty rather than broken.
     */
    @Test
    fun testServerLogsRenderEverythingTheServerSendsThem() {
        for (type in listOf(EventType.Motd, EventType.Other, EventType.Error, EventType.Notice, EventType.Invite)) {
            assertTrue(BufferKind.Server.renders(type), "$type")
        }
    }

    @Test
    fun testSystemBufferRendersNothingButSystemLines() {
        assertFalse(BufferKind.System.renders(EventType.Message))
        assertFalse(BufferKind.System.renders(EventType.Join))
    }

    /**
     * The server streams state-only events to the server buffer alongside its log lines —
     * `usermode` (carries `modes`), `away-state` (an `away` object), `lag`, `peer-presence`
     * — none with a `text` field. The client parses them as `Other` and, unfiltered, each
     * draws an empty bubble. `isRenderable` is what keeps them off screen.
     */
    @Test
    fun testAnEventWithNoTextIsNotRenderable() {
        val usermode = Message(id = 1, type = EventType.Other, nick = null, text = null)
        assertFalse(usermode.isRenderable)

        val blank = Message(id = 2, type = EventType.Other, nick = null, text = "   ")
        assertFalse(blank.isRenderable, "whitespace-only is still blank")

        val motd = Message(id = 3, type = EventType.Motd, nick = null, text = "- Welcome -")
        assertTrue(motd.isRenderable)

        // An activity line is the exception: it synthesizes its body from structured fields,
        // so a join renders "alice joined" despite carrying no `text` at all.
        val join = Message(id = 4, type = EventType.Join, nick = "alice", text = null)
        assertTrue(join.isRenderable, "a text-less join still renders")
    }

    /**
     * An activity event with none of the fields its line is built from has nothing to
     * render, and would seed consolidation with an empty-nick identity — so it's filtered,
     * not papered over with placeholder text.
     */
    @Test
    fun testActivityWithoutItsRequiredFieldsIsNotRenderable() {
        assertFalse(Message(id = 1, type = EventType.Join, nick = null, text = null).isRenderable, "join needs a nick")
        assertFalse(
            Message(id = 2, type = EventType.Nick, nick = "alice", text = null, newNick = null).isRenderable,
            "a rename needs the new nick",
        )
        assertFalse(
            Message(id = 3, type = EventType.Mode, nick = "chan", text = null, modes = emptyList()).isRenderable,
            "a mode needs a change list or raw text",
        )
        // But a mode with only the raw string (no structured list) still renders.
        assertTrue(Message(id = 4, type = EventType.Mode, nick = "chan", text = "+nt", modes = emptyList()).isRenderable)
        assertTrue(
            Message(id = 5, type = EventType.Nick, nick = "alice", text = null, newNick = "alice_afk").isRenderable
        )
    }

    /**
     * chghost carries no `text` at all — the line is synthesized from `newIdent`/`newHost` —
     * so before it had an `EventType` case it folded to `Other`, failed the `hasText` check,
     * and rendered nowhere despite arriving intact (lurker-ios#59).
     */
    @Test
    fun testChghostRenderabilityRidesTheNewMask() {
        assertTrue(
            Message(id = 1, type = EventType.Chghost, nick = "alice", text = null, newIdent = "u", newHost = "new.host")
                .isRenderable
        )
        // Either half alone is enough to say something.
        assertTrue(
            Message(id = 2, type = EventType.Chghost, nick = "alice", text = null, newHost = "new.host").isRenderable
        )
        assertFalse(
            Message(id = 3, type = EventType.Chghost, nick = "alice", text = null).isRenderable,
            "with neither half the line reads 'alice changed host to '",
        )
        assertFalse(
            Message(id = 4, type = EventType.Chghost, nick = null, text = null, newHost = "new.host").isRenderable,
            "chghost needs an actor",
        )
    }

    /**
     * `userhost` arrives as the full `nick!user@host`; the suffix shows only the two halves
     * after the `!`. Both are required — the server stores an empty ident or host when it
     * lacks one, and a half-mask like `(@host)` reads worse than nothing.
     */
    @Test
    fun testUserHostMaskRequiresBothHalves() {
        fun mask(userhost: String?): String? =
            Message(id = 1, type = EventType.Join, nick = "alice", text = null, userhost = userhost).userHostMask
        assertEquals("~user@example.host", mask("alice!~user@example.host"))
        assertNull(mask("alice!@example.host"), "no ident")
        assertNull(mask("alice!user@"), "no host")
        assertNull(mask("alice"), "no mask at all")
        assertNull(mask("alice!userexample.host"), "no @ separator")
        assertNull(mask(null))
        // A host containing an '@' (rare, but the server doesn't forbid it) splits on the
        // first one, so the ident stays the ident.
        assertEquals("user@a@b", mask("alice!user@a@b"))
    }

    /**
     * A half-mask like `@host` reads worse than the host alone, so the two are only joined
     * when both are present — matching the web's `chghostMask()`.
     */
    @Test
    fun testChghostMaskOnlyJoinsWhenBothHalvesArePresent() {
        val both = Message(id = 1, type = EventType.Chghost, nick = "a", text = null, newIdent = "u", newHost = "h")
        assertEquals("u@h", both.chghostMask)
        val hostOnly = Message(id = 2, type = EventType.Chghost, nick = "a", text = null, newHost = "h")
        assertEquals("h", hostOnly.chghostMask)
        val identOnly = Message(id = 3, type = EventType.Chghost, nick = "a", text = null, newIdent = "u")
        assertEquals("u", identOnly.chghostMask)
    }

    // MARK: - Severity rides `level`, not `type`

    // MARK: - originNetworkId

    // MARK: - Types the server sends that the client didn't model

    // MARK: - The system backlog is a latest slice, never a resume delta

    // MARK: - What the server will actually answer

    @Test
    fun testOnlyChannelsAndDmsHydrateOnDemand() {
        // `handleOpenBuffer` drops the request for the system buffer and `:server:` logs
        // and never replies, so asking latches a wait on a reply that isn't coming.
        assertTrue(BufferKind.Channel.hydratesOnDemand)
        assertTrue(BufferKind.Dm.hydratesOnDemand)
        assertFalse(BufferKind.System.hydratesOnDemand, "server: `if (!networkId) return`")
        assertFalse(BufferKind.Server.hydratesOnDemand, "server: `if (target.startsWith(':server:')) return`")
    }

    // MARK: - Naming

    @Test
    fun testTheSystemBufferIsCalledLurkerNotItsSentinel() {
        // `:system:` is a wire sentinel, not something to show anyone.
        assertEquals("Lurker", Buffer.system.displayName())
        assertEquals("Lurker", Buffer.system.displayName(networkName = "libera"))
    }

    @Test
    fun testAServerLogPrefersItsNetworksName() {
        // Two copies of this lived in two view controllers on iOS and disagreed: the switcher
        // said "Server" while the title it opened said "libera".
        val log = Buffer(networkId = 1, target = ":server:libera", kind = BufferKind.Server)
        assertEquals("libera", log.displayName(networkName = "libera"))
        assertEquals("Server", log.displayName(), "…and falls back when the roster hasn't landed")
    }

    @Test
    fun testChannelsAndDmsAreJustTheirTarget() {
        val channel = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel)
        val dm = Buffer(networkId = 1, target = "alice", kind = BufferKind.Dm)
        assertEquals("#lurker", channel.displayName(networkName = "libera"))
        assertEquals("alice", dm.displayName(networkName = "libera"))
    }

    // MARK: - The buffer itself

    @Test
    fun testTheSystemBufferIsConstructibleWithoutTheServer() {
        // It's the launch screen, so it must exist before any frame arrives.
        assertEquals(BufferKind.System, Buffer.system.kind)
        assertNull(Buffer.system.networkId)
        assertEquals("sys::" + Buffer.systemTarget, Buffer.system.key.id)
    }

    // Port-only:

    /**
     * `BufferKey.id` is the map key the whole store hangs off, and LurkerKit has no test whose
     * subject it is. These are the Swift's own answers for the same keys (LurkerKit's
     * `BufferKey`, compiled and run on a Mac): the format, and a fold that covers the whole of
     * Unicode rather than ASCII alone.
     *
     * ⚠ One answer is left out because the two differ on it: a word-final `Σ` (see the Port
     * note on `BufferKey.id`).
     */
    @Test
    fun testTheKeyIdAgreesWithLurkerKit() {
        fun id(networkId: Int?, target: String): String = BufferKey(networkId = networkId, target = target).id
        assertEquals("1::#chan", id(1, "#Chan"))
        assertEquals("42::#chan", id(42, "#CHAN"))
        assertEquals("0::bob", id(0, "BOB"))
        assertEquals("sys:::system:", id(null, ":system:"))
        assertEquals("sys::#chan", id(null, "#Chan"))
        assertEquals("1::", id(1, ""))
        assertEquals("1::=bob", id(1, "=Bob"))
        assertEquals("1:::server:1", id(1, ":server:1"))
        // Beyond ASCII: `lowercased()` folds these, so the key does too.
        assertEquals("1::émile", id(1, "Émile"))
        assertEquals("1::i\u0307stanbul", id(1, "\u0130stanbul"))
        assertEquals("1::ß", id(1, "ß"))
        assertEquals("1::#\uFF41\uFF42", id(1, "#\uFF21\uFF22"))
        assertEquals("1::\uD801\uDC28", id(1, "\uD801\uDC00"))
        // …and it is the id that folds, never the key: the key keeps the casing it was given.
        assertNotEquals(BufferKey(networkId = 1, target = "#Chan"), BufferKey(networkId = 1, target = "#chan"))
    }

    // Waiting on FrameParser, ServerFrame: testAnErrorSystemLineIsStillTypeSystem,
    // testSystemLineDefaultsToInfoWhenLevelIsAbsentOrJunk, testLevelIsOnlySetForSystemLines,
    // testSeverityAndOriginAreBothSystemOnlyEvenIfTheWireSaysOtherwise,
    // testSystemLineCarriesTheNetworkItIsAbout, testTypesTheServerSendsThatTheClientDidNotModel,
    // testAGenuinelyUnknownTypeStillFoldsToOther, testTheSystemBacklogReplacesEvenThoughItSaysResetFalse,
    // testANetworkResumeSliceStillAppends, testAnOversizedNetworkGapStillReplaces,
    // testTheSyntheticSystemBufferMatchesTheServersOwn
    //
    // Waiting on LurkerStore (and FrameParser):
    // testALiveSystemLineBeatingTheBacklogDoesNotShoveHistoryBelowIt
}
