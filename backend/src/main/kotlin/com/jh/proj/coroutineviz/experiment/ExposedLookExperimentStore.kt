package com.jh.proj.coroutineviz.experiment

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val ID_LENGTH = 36
private const val LOOK_LENGTH = 16
private const val KIND_LENGTH = 8

internal object LookVisitorsTable : Table("look_visitors") {
    val anonId = varchar("anon_id", ID_LENGTH)
    val entryLook = varchar("entry_look", LOOK_LENGTH).nullable()
    val entryKind = varchar("entry_kind", KIND_LENGTH).nullable()
    val firstSeenAt = long("first_seen_at")
    override val primaryKey = PrimaryKey(anonId)
}

internal object LookExposureTable : Table("look_exposure") {
    val anonId = varchar("anon_id", ID_LENGTH)
    val look = varchar("look", LOOK_LENGTH)
    val sessionsSeen = integer("sessions_seen")
    val visibleMs = long("visible_ms")
    override val primaryKey = PrimaryKey(anonId, look)
}

internal object LookSwitchesTable : Table("look_switches") {
    val anonId = varchar("anon_id", ID_LENGTH)
    val fromLook = varchar("from_look", LOOK_LENGTH)
    val toLook = varchar("to_look", LOOK_LENGTH)
    val times = integer("times")
    override val primaryKey = PrimaryKey(anonId, fromLook, toLook)
}

internal object LookVotesTable : Table("look_votes") {
    val anonId = varchar("anon_id", ID_LENGTH)
    val look = varchar("look", LOOK_LENGTH)
    val rating = integer("rating")
    val comment = text("comment").nullable()
    val votedAt = long("voted_at")
    override val primaryKey = PrimaryKey(anonId, look)
}

/**
 * The look experiment in the database (V3 migration), when `storage.type=database`. One transaction per
 * request; the writes are serialised in this process (one server writes), so read-then-update cannot race.
 */
class ExposedLookExperimentStore(
    private val db: Database,
    private val maxVisitors: Int = LookExperimentStore.DEFAULT_MAX_VISITORS,
) : LookExperimentStore {
    private val lock = Any()
    private val full = FullWarning()

    override fun record(
        anonId: String,
        events: List<LookEvent>,
        at: Long,
    ): Boolean =
        synchronized(lock) {
            transaction(db) {
                if (!admit(anonId, at)) return@transaction false
                events.forEach { event ->
                    when (event) {
                        is LookEvent.Entry -> enter(anonId, event)
                        is LookEvent.Seen -> addExposure(anonId, event.look, seen = 1, ms = 0)
                        is LookEvent.Time -> addExposure(anonId, event.look, seen = 0, ms = event.ms)
                        is LookEvent.Switch -> addSwitch(anonId, event)
                    }
                }
                true
            }
        }

    override fun putVote(
        anonId: String,
        look: String,
        rating: Int,
        comment: String?,
        at: Long,
    ): Boolean =
        synchronized(lock) {
            transaction(db) {
                if (!admit(anonId, at)) return@transaction false
                val where = (LookVotesTable.anonId eq anonId) and (LookVotesTable.look eq look)
                val updated =
                    LookVotesTable.update({ where }) {
                        it[LookVotesTable.rating] = rating
                        it[LookVotesTable.comment] = comment
                        it[votedAt] = at
                    }
                if (updated == 0) {
                    LookVotesTable.insert {
                        it[LookVotesTable.anonId] = anonId
                        it[LookVotesTable.look] = look
                        it[LookVotesTable.rating] = rating
                        it[LookVotesTable.comment] = comment
                        it[votedAt] = at
                    }
                }
                true
            }
        }

    override fun snapshot(): ExperimentData =
        transaction(db) {
            ExperimentData(
                visitors =
                    LookVisitorsTable.selectAll().map {
                        VisitorRow(
                            it[LookVisitorsTable.anonId],
                            it[LookVisitorsTable.entryLook],
                            it[LookVisitorsTable.entryKind]?.let { kind -> EntryKind.entries.firstOrNull { e -> e.name == kind } },
                            it[LookVisitorsTable.firstSeenAt],
                        )
                    },
                exposures =
                    LookExposureTable.selectAll().map {
                        ExposureRow(
                            it[LookExposureTable.anonId],
                            it[LookExposureTable.look],
                            it[LookExposureTable.sessionsSeen],
                            it[LookExposureTable.visibleMs],
                        )
                    },
                switches =
                    LookSwitchesTable.selectAll().map {
                        SwitchRow(
                            it[LookSwitchesTable.anonId],
                            it[LookSwitchesTable.fromLook],
                            it[LookSwitchesTable.toLook],
                            it[LookSwitchesTable.times],
                        )
                    },
                votes =
                    LookVotesTable.selectAll().map {
                        VoteRow(
                            it[LookVotesTable.anonId],
                            it[LookVotesTable.look],
                            it[LookVotesTable.rating],
                            it[LookVotesTable.comment],
                            it[LookVotesTable.votedAt],
                        )
                    },
            )
        }

    /** Adds the browser when new and there is room; true when it is (now) known. */
    private fun admit(
        anonId: String,
        at: Long,
    ): Boolean {
        val known = LookVisitorsTable.selectAll().where { LookVisitorsTable.anonId eq anonId }.count() > 0
        val room = known || LookVisitorsTable.selectAll().count() < maxVisitors
        when {
            !room -> full.once(maxVisitors)
            !known ->
                LookVisitorsTable.insert {
                    it[LookVisitorsTable.anonId] = anonId
                    it[firstSeenAt] = at
                }
        }
        return room
    }

    private fun enter(
        anonId: String,
        entry: LookEvent.Entry,
    ) {
        LookVisitorsTable.update({ (LookVisitorsTable.anonId eq anonId) and LookVisitorsTable.entryKind.isNull() }) {
            it[entryLook] = entry.look
            it[entryKind] = entry.kind.name
        }
    }

    private fun addExposure(
        anonId: String,
        look: String,
        seen: Int,
        ms: Long,
    ) {
        val where = (LookExposureTable.anonId eq anonId) and (LookExposureTable.look eq look)
        val row = LookExposureTable.selectAll().where { where }.singleOrNull()
        if (row == null) {
            LookExposureTable.insert {
                it[LookExposureTable.anonId] = anonId
                it[LookExposureTable.look] = look
                it[sessionsSeen] = seen
                it[visibleMs] = ms
            }
        } else {
            LookExposureTable.update({ where }) {
                it[sessionsSeen] = row[sessionsSeen] + seen
                it[visibleMs] = row[visibleMs] + ms
            }
        }
    }

    private fun addSwitch(
        anonId: String,
        switch: LookEvent.Switch,
    ) {
        val where =
            (LookSwitchesTable.anonId eq anonId) and (LookSwitchesTable.fromLook eq switch.from) and (LookSwitchesTable.toLook eq switch.to)
        val row = LookSwitchesTable.selectAll().where { where }.singleOrNull()
        if (row == null) {
            LookSwitchesTable.insert {
                it[LookSwitchesTable.anonId] = anonId
                it[fromLook] = switch.from
                it[toLook] = switch.to
                it[times] = 1
            }
        } else {
            LookSwitchesTable.update({ where }) { it[times] = row[times] + 1 }
        }
    }
}
