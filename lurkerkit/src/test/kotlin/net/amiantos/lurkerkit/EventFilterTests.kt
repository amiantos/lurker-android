// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Consolidation
import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.EventMode
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.HistoryCountBy
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.SpeakerMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The event-noise tier (lurker#666) — which presence events reach the list, and the page unit
 * that has to match.
 *
 * Pinned because both halves fail quietly. A tier that filters rows the page wasn't sized for
 * looks like a short buffer, not a bug; and `None` hiding one type too many makes the buffer
 * lie about what happened rather than merely be quieter.
 */
class EventFilterTests {

    private fun settings(mode: String?): Settings {
        var s = Settings()
        if (mode != null) s = s.apply(mapOf(EventFilter.modeKey to SettingValue.String(mode)))
        return s
    }

    // MARK: - The tier

    /**
     * The phone always reads the mobile key. The tier is split by device class and this
     * device is never the desktop case; reading `chat.events` here would silently honor a
     * preference the user set for their laptop.
     */
    @Test
    fun testReadsTheMobileKey() {
        assertEquals("chat.events.mobile", EventFilter.modeKey)
    }

    @Test
    fun testResolvesEachRung() {
        assertEquals(EventMode.All, EventFilter.mode(settings("all")))
        assertEquals(EventMode.Smart, EventFilter.mode(settings("smart")))
        assertEquals(EventMode.None, EventFilter.mode(settings("none")))
    }

    /**
     * Before bootstrap lands, and against a server too old to know the key, the registry's
     * own default is the only honest answer — anything else changes what the reader sees a
     * moment after launch.
     */
    @Test
    fun testDefaultsToAll() {
        assertEquals(EventMode.All, EventFilter.mode(settings(null)))
        assertEquals(EventMode.All, EventFilter.mode(settings("something-newer")))
    }

    // MARK: - The noise set

    @Test
    fun testNoiseIsTheFoldSetPlusMode() {
        assertEquals(Consolidation.consolidatableTypes + setOf(EventType.Mode), EventFilter.noiseTypes)
        for (type in listOf(
            EventType.Join, EventType.Part, EventType.Quit, EventType.Nick, EventType.Chghost, EventType.Mode,
        )) {
            assertTrue(EventFilter.isNoise(type), "$type should be noise")
        }
    }

    /**
     * The line between churn and content. These are things that happened — being removed
     * from a channel, the topic changing, an invite addressed to you — and no rung hides them.
     */
    @Test
    fun testEventsThatCarryInformationAreNeverNoise() {
        for (type in listOf(
            EventType.Kick, EventType.Topic, EventType.Invite, EventType.Error, EventType.Message,
            EventType.Action, EventType.Notice,
        )) {
            assertFalse(EventFilter.isNoise(type), "$type should survive every tier")
        }
    }

    // MARK: - Page sizing

    @Test
    fun testPageUnitMatchesWhatWeRender() {
        var consolidating = settings("all")
        consolidating = consolidating.apply(mapOf("chat.consolidate_joins" to SettingValue.Bool(true)))
        assertEquals(HistoryCountBy.Renderable, HistoryCountBy.forRendering(consolidating))

        var unfolded = settings("all")
        unfolded = unfolded.apply(mapOf("chat.consolidate_joins" to SettingValue.Bool(false)))
        assertEquals(HistoryCountBy.Event, HistoryCountBy.forRendering(unfolded))
    }

    /**
     * At `None` consolidation is moot — there is nothing left to fold — so the tier decides
     * alone. Leaving this to the consolidation flag would size pages in a unit that still
     * counts `mode` rows the reader can't see.
     */
    @Test
    fun testNoneAsksForChatCountingRegardlessOfConsolidation() {
        for (folds in listOf(true, false)) {
            var s = settings("none")
            s = s.apply(mapOf("chat.consolidate_joins" to SettingValue.Bool(folds)))
            assertEquals(HistoryCountBy.Chat, HistoryCountBy.forRendering(s))
        }
    }

    /**
     * There is no unit for `Smart`: which events it hides depends on who spoke recently,
     * which the server can't know. It asks for the same unit `All` would.
     */
    @Test
    fun testSmartAsksForTheSameUnitAsAll() {
        var smart = settings("smart")
        smart = smart.apply(mapOf("chat.consolidate_joins" to SettingValue.Bool(true)))
        assertEquals(HistoryCountBy.Renderable, HistoryCountBy.forRendering(smart))
    }

    /** It travels on the wire as its raw value, so the spelling is protocol, not an enum name. */
    @Test
    fun testChatWireSpelling() {
        assertEquals("chat", HistoryCountBy.Chat.rawValue)
    }

    // MARK: - What the list actually drops

    private fun build(
        messages: List<Message>,
        mode: String,
        speakers: SpeakerMap = SpeakerMap(),
        ownNick: String? = null,
    ): List<MessageRow> =
        MessageRows.build(
            messages = messages, dividerAfterId = null, hasMoreOlder = true,
            settings = settings(mode), speakers = speakers, ownNick = ownNick,
        )

    private companion object {
        /**
         * An arbitrary fixed instant. Every event and every speaker time in this file is expressed
         * as an offset from it, so a window test says what it means without any clock arithmetic
         * in the assertion.
         */
        val t0: Instant = Instant.ofEpochSecond(1_784_548_800)

        fun at(minutes: Double): Instant = t0.plusMillis(Math.round(minutes * 60 * 1000))
    }

    private fun message(
        id: Long,
        type: EventType,
        nick: String = "alice",
        isSelf: Boolean = false,
        minutes: Double = 0.0,
    ): Message =
        Message(
            id = id, type = type, nick = nick,
            text = if (type == EventType.Message) "hi" else null,
            isSelf = isSelf,
            date = at(minutes),
            newNick = if (type == EventType.Nick) "${nick}_afk" else null,
            modes = if (type == EventType.Mode) listOf(ModeChange(mode = "+o", param = nick)) else emptyList(),
        )

    @Test
    fun testNoneDropsEveryNoiseRow() {
        val rows = build(
            listOf(
                message(1, EventType.Message),
                message(2, EventType.Join, "bob"),
                message(3, EventType.Mode, "op"),
                message(4, EventType.Quit, "carol"),
                message(5, EventType.Message),
            ),
            mode = "none",
        )
        val bubbles = rows.mapNotNull { (it as? MessageRow.Bubble)?.message?.id }
        assertEquals(listOf(1L, 5L), bubbles)
        // Nothing consolidated either: there is no run left to summarize.
        assertFalse(rows.any { it is MessageRow.Consolidated })
    }

    /**
     * `None` hides your own joins too. Someone who asked for no event noise on their phone
     * wants none of it, not none-except-mine.
     */
    @Test
    fun testNoneDropsYourOwnEventsAsWell() {
        val rows = build(
            listOf(message(1, EventType.Message), message(2, EventType.Join, "me", isSelf = true)),
            mode = "none",
        )
        val lines: List<Long> = rows.mapNotNull { (it as? MessageRow.Line)?.message?.id }
        assertEquals(emptyList(), lines)
    }

    /**
     * A buffer with messages can build to NO rows — the precondition behind a blank-screen
     * bug in iOS's `ChatViewController`, which decided its empty-state placeholder from the raw
     * message count. A quiet channel holding nothing but joins and mode changes has messages
     * and renders nothing, so anything keyed off "are there messages" has to be keyed off the
     * built rows instead. Pinned here because the two only started disagreeing with `None`.
     */
    @Test
    fun testAnAllNoiseBufferBuildsToNothing() {
        val rows = build(
            listOf(message(1, EventType.Join, "bob"), message(2, EventType.Mode, "op"), message(3, EventType.Part, "bob")),
            mode = "none",
        )
        assertTrue(rows.isEmpty(), "expected no rows, got $rows")
    }

    @Test
    fun testNoneKeepsKicksAndTopics() {
        val rows = build(
            listOf(message(1, EventType.Kick, "bob"), message(2, EventType.Topic), message(3, EventType.Join, "eve")),
            mode = "none",
        )
        val lines = rows.mapNotNull { (it as? MessageRow.Line)?.message?.type }
        assertEquals(listOf(EventType.Kick, EventType.Topic), lines)
    }

    /**
     * The control: at `All` everything still arrives, so a regression in the filter can't
     * hide behind a test that only ever asserts absence.
     */
    @Test
    fun testAllKeepsEverything() {
        val messages = listOf(
            message(1, EventType.Message), message(2, EventType.Join, "bob"),
            message(3, EventType.Mode, "op"), message(4, EventType.Message),
        )
        val rows = build(messages, mode = "all")
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(rows))
    }

    // MARK: - The smart rung (lurker-ios#63)

    private fun ids(rows: List<MessageRow>): List<Long> =
        rows.mapNotNull {
            when (it) {
                is MessageRow.Bubble -> it.message.id
                is MessageRow.Line -> it.message.id
                else -> null
            }
        }

    /**
     * The tier plus its five tuning keys. Consolidation is off throughout this section so the
     * assertions read as "which events survived" rather than "what did the summary say" — the
     * fold has its own tests, and pairing it with the filter here would let one hide the other.
     */
    private fun smart(
        delay: Int = 5,
        unmask: Int = 30,
        join: Boolean = true,
        quit: Boolean = true,
        nick: Boolean = true,
        mode: Boolean = true,
    ): Settings =
        settings("smart").apply(
            mapOf(
                "chat.consolidate_joins" to SettingValue.Bool(false),
                "chat.smart_filter_delay" to SettingValue.Int(delay),
                "chat.smart_filter_join_unmask" to SettingValue.Int(unmask),
                "chat.smart_filter_join" to SettingValue.Bool(join),
                "chat.smart_filter_quit" to SettingValue.Bool(quit),
                "chat.smart_filter_nick" to SettingValue.Bool(nick),
                "chat.smart_filter_mode" to SettingValue.Bool(mode),
            ),
        )

    /** A channel MODE row: `nick` is the SETTER, the targets ride in `modes`. */
    private fun modeMessage(
        id: Long,
        setter: String,
        signedLetter: String,
        targets: List<String>,
        kind: ModeChangeKind? = ModeChangeKind.Prefix,
        minutes: Double = 0.0,
    ): Message =
        Message(
            id = id, type = EventType.Mode, nick = setter, text = null, isSelf = false,
            date = at(minutes),
            modes = targets.map { ModeChange(mode = signedLetter, param = it, kind = kind) },
        )

    private fun rows(
        messages: List<Message>,
        settings: Settings,
        speakers: SpeakerMap = SpeakerMap(),
        ownNick: String? = null,
    ): List<Long> =
        ids(
            MessageRows.build(
                messages = messages, dividerAfterId = null, hasMoreOlder = true,
                settings = settings, speakers = speakers, ownNick = ownNick,
            ),
        )

    private fun spoke(nick: String, minutes: Double): SpeakerMap =
        SpeakerMap(listOf(Speaker(nick = nick, lastSpoke = at(minutes))))

    /**
     * The premise: churn from someone nobody has heard from goes away, and the same churn from
     * someone who was just talking stays. Both halves in one test, because either alone passes
     * for a filter that is simply stuck.
     */
    @Test
    fun testHidesChurnFromSilentNicksAndKeepsItFromRecentSpeakers() {
        val messages = listOf(
            message(1, EventType.Message, "carol", minutes = 0.0),
            message(2, EventType.Part, "bob", minutes = 1.0),
            message(3, EventType.Part, "alice", minutes = 1.0),
        )
        // alice spoke a minute before her part; bob hasn't spoken at all.
        assertEquals(listOf(1L, 3L), rows(messages, smart(), speakers = spoke("alice", minutes = 0.0)))
    }

    @Test
    fun testEveryChurnTypeIsFilterable() {
        val churn = listOf(EventType.Join, EventType.Part, EventType.Quit, EventType.Chghost, EventType.Nick)
        for ((index, type) in churn.withIndex()) {
            val messages = listOf(message(index + 1L, type, "bob", minutes = 1.0))
            assertEquals(emptyList(), rows(messages, smart()), "$type from a silent nick should hide")
            assertEquals(
                listOf(index + 1L), rows(messages, smart(), speakers = spoke("bob", minutes = 0.0)),
                "$type from a recent speaker should render",
            )
        }
    }

    /**
     * Speech *before* the event only counts inside the window. Outside it the nick is a lurker
     * again — which is the whole point of the window being tunable.
     */
    @Test
    fun testTheDelayWindowIsAWindow() {
        val part = listOf(message(1, EventType.Part, "bob", minutes = 10.0))
        assertEquals(listOf(1L), rows(part, smart(delay = 5), speakers = spoke("bob", minutes = 6.0)))
        assertEquals(listOf(1L), rows(part, smart(delay = 5), speakers = spoke("bob", minutes = 5.0)), "the edge counts")
        assertEquals(emptyList(), rows(part, smart(delay = 5), speakers = spoke("bob", minutes = 4.0)))
    }

    /**
     * The unmask rule: someone who joins and immediately starts talking isn't retroactively
     * invisible. This is the half no fetch can supply — the speech happens after the frame the
     * speaker list was built from, so it only ever arrives as a live message.
     */
    @Test
    fun testAJoinIsRevealedBySpeakingShortlyAfterIt() {
        val join = listOf(message(1, EventType.Join, "bob", minutes = 0.0))
        assertEquals(listOf(1L), rows(join, smart(unmask = 30), speakers = spoke("bob", minutes = 20.0)))
        assertEquals(emptyList(), rows(join, smart(unmask = 30), speakers = spoke("bob", minutes = 31.0)))
        assertEquals(emptyList(), rows(join, smart(unmask = 0), speakers = spoke("bob", minutes = 1.0)), "0 disables it")
    }

    /**
     * Joins only. There is nothing to reveal about a part or a rename by what the nick says
     * next — and a quit followed by speech is a nick that came back, which is its own join.
     */
    @Test
    fun testOnlyJoinsUnmask() {
        for (type in listOf(EventType.Part, EventType.Quit, EventType.Nick, EventType.Chghost)) {
            val event = listOf(message(1, type, "bob", minutes = 0.0))
            assertEquals(emptyList(), rows(event, smart(), speakers = spoke("bob", minutes = 1.0)), "$type")
        }
    }

    /**
     * Per-type toggles, including the one that isn't its own toggle: `chghost` rides the quit
     * switch rather than getting a fourth setting (lurker#591).
     */
    @Test
    fun testPerTypeTogglesOptOutOfFiltering() {
        fun surviving(settings: Settings): List<Long> =
            rows(
                listOf(
                    message(1, EventType.Join, "bob", minutes = 0.0),
                    message(2, EventType.Part, "bob", minutes = 0.0),
                    message(3, EventType.Quit, "bob", minutes = 0.0),
                    message(4, EventType.Chghost, "bob", minutes = 0.0),
                    message(5, EventType.Nick, "bob", minutes = 0.0),
                ),
                settings,
            )
        assertEquals(emptyList(), surviving(smart()))
        assertEquals(listOf(1L), surviving(smart(join = false)))
        assertEquals(listOf(2L, 3L, 4L), surviving(smart(quit = false)), "chghost rides the quit toggle")
        assertEquals(listOf(5L), surviving(smart(nick = false)))
    }

    /**
     * Your own churn is never hidden, however quiet you've been. `isSelf` alone doesn't cover
     * it — the server stamps that on messages you *sent*, not on the JOIN it saw you make —
     * so the nick has to be checked too, case-insensitively like every other nick comparison.
     */
    @Test
    fun testNeverHidesYourOwnChurn() {
        val messages = listOf(
            message(1, EventType.Join, "Me", minutes = 0.0),
            message(2, EventType.Part, "me", isSelf = true, minutes = 0.0),
            message(3, EventType.Quit, "bob", minutes = 0.0),
        )
        assertEquals(listOf(1L, 2L), rows(messages, smart(), ownNick = "mE"))
    }

    /**
     * A rename is the one event whose actor has two names, and the store carries their speaker
     * entry from the old to the new one *as it applies the event* — so by render time the nick
     * printed on the row is the one no longer in the map. Looking only there hid the rename of
     * somebody who had just been talking, which is exactly the churn this rung keeps.
     */
    @Test
    fun testARenameIsJudgedUnderBothOfItsNicks() {
        val rename = listOf(message(1, EventType.Nick, "alice", minutes = 1.0)) // → alice_afk
        assertEquals(
            listOf(1L), rows(rename, smart(), speakers = spoke("alice_afk", minutes = 0.0)),
            "the carried entry counts",
        )
        assertEquals(
            listOf(1L), rows(rename, smart(), speakers = spoke("alice", minutes = 0.0)),
            "and so does an uncarried one — a backlog rename never went through the carry",
        )
        assertEquals(emptyList(), rows(rename, smart()), "a genuine lurker's rename still goes")
    }

    /**
     * Our own rename straddles the change: whichever of `own-nick` and the `nick` line the
     * store applies first, the other name is the one `ownNick` is holding. Both are exempt, so
     * neither order can hide our own churn.
     */
    @Test
    fun testOurOwnRenameIsExemptUnderEitherName() {
        val rename = listOf(message(1, EventType.Nick, "me", minutes = 0.0)) // → me_afk
        assertEquals(listOf(1L), rows(rename, smart(), ownNick = "me"), "own-nick hasn't landed yet")
        assertEquals(listOf(1L), rows(rename, smart(), ownNick = "me_afk"), "own-nick landed first")
    }

    /**
     * The rung filters churn and nothing else. Conversation, kicks, topics and invites are
     * never touched. The `Mode` row here is UNSTAMPED, which is why it survives: without a
     * `kind` there is no way to tell op churn from a ban, so it is shown rather than guessed
     * at. Stamped mode rows do take part — see the mode tests below.
     */
    @Test
    fun testSmartLeavesEverythingThatIsNotChurnAlone() {
        val messages = listOf(
            message(1, EventType.Message, "bob", minutes = 0.0),
            message(2, EventType.Action, "bob", minutes = 0.0),
            message(3, EventType.Notice, "bob", minutes = 0.0),
            message(4, EventType.Kick, "bob", minutes = 0.0),
            message(5, EventType.Topic, "bob", minutes = 0.0),
            message(6, EventType.Mode, "bob", minutes = 0.0),
            message(7, EventType.Invite, "bob", minutes = 0.0),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), rows(messages, smart()))
    }

    /**
     * An unseeded buffer hides every filterable event, matching the web. Worth pinning because
     * the alternative failure — showing everything until the map loads — looks like the filter
     * working intermittently rather than like a missing seed.
     */
    @Test
    fun testAnEmptySpeakerMapHidesAllChurn() {
        assertEquals(emptyList(), rows(listOf(message(1, EventType.Join, "bob", minutes = 0.0)), smart()))
    }

    /**
     * An event with no clock renders. There is no window to judge it against, and the web's
     * `Date.parse(…) || 0` reads it as the epoch — infinitely stale, therefore always hidden,
     * which is the wrong way to fail for a row whose only problem is a missing timestamp.
     */
    @Test
    fun testAnUndatedEventIsNotJudged() {
        val undated = Message(id = 1, type = EventType.Join, nick = "bob", text = null, isSelf = false, date = null)
        assertEquals(listOf(1L), rows(listOf(undated), smart()))
    }

    /**
     * The filter runs *above* consolidation, so a hidden event can't be counted by a summary
     * it never reached. Getting this backwards is invisible in the row stream and shows up as a
     * summary that names people whose own lines aren't on screen.
     */
    @Test
    fun testFilteringHappensBeforeConsolidation() {
        var folding = smart()
        folding = folding.apply(mapOf("chat.consolidate_joins" to SettingValue.Bool(true)))
        val built = MessageRows.build(
            messages = listOf(
                message(1, EventType.Join, "alice", minutes = 1.0),
                message(2, EventType.Join, "bob", minutes = 1.0),
                message(3, EventType.Join, "carol", minutes = 1.0),
            ),
            dividerAfterId = null, hasMoreOlder = true,
            settings = folding, speakers = spoke("alice", minutes = 0.0),
        )
        // alice spoke, so her join survives — alone, which is a line rather than a summary.
        assertEquals(listOf(1L), ids(built))
        assertFalse(built.any { it is MessageRow.Consolidated })
    }

    // MARK: - Mode rows in the smart rung (lurker#825)

    /**
     * The premise, and the reason it keys on the target: ChanServ never speaks in the
     * channel, so an author-keyed rule would hide every mode change there is.
     */
    @Test
    fun testJudgesTheTargetNotTheAuthor() {
        val opAlice = listOf(modeMessage(1, "ChanServ", "+o", listOf("alice"), minutes = 1.0))
        // alice spoke a minute before: shown.
        assertEquals(listOf(1L), rows(opAlice, smart(), speakers = spoke("alice", minutes = 0.0)))
        // Nobody spoke: hidden.
        assertEquals(emptyList(), rows(opAlice, smart()))
        // The AUTHOR speaking is not what saves it. This is the assertion that would have
        // caught halloy's shape.
        assertEquals(emptyList(), rows(opAlice, smart(), speakers = spoke("ChanServ", minutes = 0.0)))
    }

    @Test
    fun testShowsAModeSetOnUsOrByUs() {
        val onMe = listOf(modeMessage(1, "ChanServ", "+o", listOf("me"), minutes = 1.0))
        assertEquals(listOf(1L), rows(onMe, smart(), ownNick = "me"))
        val byMe = listOf(modeMessage(1, "me", "+o", listOf("lurker"), minutes = 1.0))
        assertEquals(listOf(1L), rows(byMe, smart(), ownNick = "me"))
        // Case-folded, like every other nick comparison.
        assertEquals(listOf(1L), rows(onMe, smart(), ownNick = "ME"))
    }

    @Test
    fun testAnyOneRecentTargetShowsAMultiTargetRow() {
        // `+ooo a b c` where only b spoke. Hiding it would drop a and c's grants on the floor.
        val burst = listOf(modeMessage(1, "ChanServ", "+o", listOf("a", "b", "c"), minutes = 1.0))
        assertEquals(listOf(1L), rows(burst, smart(), speakers = spoke("b", minutes = 0.0)))
        assertEquals(emptyList(), rows(burst, smart()))
    }

    @Test
    fun testNeverHidesAModeCarryingAnythingButMemberStatus() {
        // The whole-message gate. A ban alone, and a ban riding along with an op change.
        val ban = listOf(modeMessage(1, "op", "+b", listOf("*!*@host"), kind = ModeChangeKind.List, minutes = 1.0))
        assertEquals(listOf(1L), rows(ban, smart()))

        val mixed = listOf(
            Message(
                id = 1, type = EventType.Mode, nick = "op", text = null, isSelf = false, date = at(1.0),
                modes = listOf(
                    ModeChange(mode = "+o", param = "alice", kind = ModeChangeKind.Prefix),
                    ModeChange(mode = "-b", param = "*!*@host", kind = ModeChangeKind.List),
                ),
            ),
        )
        assertEquals(listOf(1L), rows(mixed, smart()))
    }

    @Test
    fun testNeverHidesAnUnstampedModeRow() {
        val unstamped = listOf(modeMessage(1, "ChanServ", "+o", listOf("lurker"), kind = null, minutes = 1.0))
        assertEquals(listOf(1L), rows(unstamped, smart()))
    }

    @Test
    fun testShowsAServerSetModeThatHasNoNick() {
        // Services or the ircd restoring modes on a netjoin sends a channel MODE with no
        // nick at all. The web gates its whole smart walk on the actor being present, so
        // those always show there; judging them against a speaker map they can never appear
        // in would hide them here and split the two clients on the same row.
        val serverSet = Message(
            id = 1, type = EventType.Mode, nick = null, text = null, isSelf = false, date = at(1.0),
            modes = listOf(ModeChange(mode = "+o", param = "lurker", kind = ModeChangeKind.Prefix)),
        )
        assertEquals(listOf(1L), rows(listOf(serverSet), smart()))
    }

    @Test
    fun testTheModeToggleGatesIt() {
        val opAlice = listOf(modeMessage(1, "ChanServ", "+o", listOf("lurker"), minutes = 1.0))
        assertEquals(emptyList(), rows(opAlice, smart(mode = true)))
        assertEquals(listOf(1L), rows(opAlice, smart(mode = false)), "off means never filtered")
    }

    // Port-only:

    /**
     * Both windows are judged to the millisecond, as LurkerKit's `TimeInterval`s are: a window
     * measured in whole seconds would let the extra millisecond through. The answers are
     * LurkerKit's.
     */
    @Test
    fun testTheWindowsAreExactToTheMillisecond() {
        val part = listOf(message(1, EventType.Part, "bob", minutes = 10.0))
        val justInside = SpeakerMap(listOf(Speaker(nick = "bob", lastSpoke = at(5.0))))
        val justOutside = SpeakerMap(listOf(Speaker(nick = "bob", lastSpoke = at(5.0).minusMillis(1))))
        assertEquals(listOf(1L), rows(part, smart(delay = 5), speakers = justInside))
        assertEquals(emptyList(), rows(part, smart(delay = 5), speakers = justOutside))

        val join = listOf(message(1, EventType.Join, "bob", minutes = 0.0))
        val revealed = SpeakerMap(listOf(Speaker(nick = "bob", lastSpoke = at(30.0))))
        val tooLate = SpeakerMap(listOf(Speaker(nick = "bob", lastSpoke = at(30.0).plusMillis(1))))
        assertEquals(listOf(1L), rows(join, smart(unmask = 30), speakers = revealed))
        assertEquals(emptyList(), rows(join, smart(unmask = 30), speakers = tooLate))
    }
}
