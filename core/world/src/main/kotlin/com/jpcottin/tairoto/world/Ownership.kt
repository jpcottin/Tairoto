package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable

/** Who simulates an entity, and how many times it has changed hands. */
@Serializable
data class Ownership(val owner: DeviceId, val epoch: Int, val pendingTo: DeviceId? = null, val pendingEpoch: Int = -1)

/** The answer to an ownership message: what changed, or why nothing did. */
sealed interface OwnershipResult {
    data class Accepted(val id: EntityId, val ownership: Ownership) : OwnershipResult
    data class Rejected(val id: EntityId, val reason: String) : OwnershipResult
}

/**
 * The host's record of who owns what, the referee of every handoff.
 *
 * An entity changes hands in two steps: its owner offers it with
 * [handoff] (epoch + 1), then the receiver takes it with [claim] at that
 * epoch. Until the claim the old owner stays the owner, so an entity is never
 * owned twice nor by nobody. Anything carrying an older epoch is ignored, and
 * an offer nobody claims is cancelled by [expire], leaving it where it was.
 */
class OwnershipRegistry(private val host: DeviceId) {
    private val entries = LinkedHashMap<EntityId, Ownership>()

    val size: Int get() = entries.size

    operator fun get(id: EntityId): Ownership? = entries[id]

    fun ownedBy(device: DeviceId): List<EntityId> = entries.filterValues { it.owner == device }.keys.toList()

    fun register(id: EntityId, owner: DeviceId): OwnershipResult {
        if (id in entries) return OwnershipResult.Rejected(id, "already registered")
        val o = Ownership(owner, epoch = 0)
        entries[id] = o
        return OwnershipResult.Accepted(id, o)
    }

    fun unregister(id: EntityId): Boolean = entries.remove(id) != null

    fun handoff(id: EntityId, from: DeviceId, to: DeviceId, epoch: Int): OwnershipResult {
        val cur = entries[id] ?: return OwnershipResult.Rejected(id, "unknown entity")
        if (cur.owner != from) return OwnershipResult.Rejected(id, "${from.name} does not own it")
        if (from == to) return OwnershipResult.Rejected(id, "handoff to itself")
        if (epoch != cur.epoch + 1) return OwnershipResult.Rejected(id, "stale epoch $epoch (now ${cur.epoch})")
        if (cur.pendingTo != null) return OwnershipResult.Rejected(id, "handoff already pending to ${cur.pendingTo.name}")
        val o = cur.copy(pendingTo = to, pendingEpoch = epoch)
        entries[id] = o
        return OwnershipResult.Accepted(id, o)
    }

    fun claim(id: EntityId, by: DeviceId, epoch: Int): OwnershipResult {
        val cur = entries[id] ?: return OwnershipResult.Rejected(id, "unknown entity")
        if (cur.pendingTo != by || cur.pendingEpoch != epoch) {
            return OwnershipResult.Rejected(id, "no handoff to ${by.name} at epoch $epoch")
        }
        val o = Ownership(owner = by, epoch = epoch)
        entries[id] = o
        return OwnershipResult.Accepted(id, o)
    }

    /** Cancels an offer that was not claimed in time; the entity stays with its owner. */
    fun expire(id: EntityId): OwnershipResult {
        val cur = entries[id] ?: return OwnershipResult.Rejected(id, "unknown entity")
        if (cur.pendingTo == null) return OwnershipResult.Rejected(id, "nothing pending")
        val o = Ownership(owner = cur.owner, epoch = cur.epoch)
        entries[id] = o
        return OwnershipResult.Accepted(id, o)
    }

    /** A device went silent: the host takes over everything it owned, and every offer to it is cancelled. */
    fun deviceLost(device: DeviceId): List<OwnershipResult> {
        val out = ArrayList<OwnershipResult>()
        for ((id, cur) in entries.entries.toList()) {
            val o = when {
                cur.owner == device -> Ownership(owner = host, epoch = cur.epoch + 1)
                cur.pendingTo == device -> Ownership(owner = cur.owner, epoch = cur.epoch)
                else -> continue
            }
            entries[id] = o
            out += OwnershipResult.Accepted(id, o)
        }
        return out
    }
}
