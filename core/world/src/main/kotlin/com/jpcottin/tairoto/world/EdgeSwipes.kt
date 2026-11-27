package com.jpcottin.tairoto.world

/**
 * Tells a finger swiped across two screens lying side by side: on the first it
 * leaves over an edge, on the second it comes on from one. Pure geometry on
 * one screen, in its own pixels (x right, y down).
 */
object EdgeSwipes {
    /** A finger this close to an edge is at it: a finger comes onto glass a few mm in, past the frame. */
    const val MARGIN_MM = 6f

    /** How straight at (or away from) the edge the finger must move: the cosine of the widest angle. */
    private const val HEADING = 0.5f

    data class AtEdge(val edge: Edge, val alongPx: Float)

    /** The edge [p] is at, nearest first, if within [marginPx] of one. */
    fun edgeAt(p: Vec2, widthPx: Float, heightPx: Float, marginPx: Float): Edge? {
        val d = listOf(Edge.TOP to p.y, Edge.RIGHT to widthPx - p.x, Edge.BOTTOM to heightPx - p.y, Edge.LEFT to p.x)
        return d.filter { it.second <= marginPx }.minByOrNull { it.second }?.first
    }

    private fun along(edge: Edge, p: Vec2) = if (edge == Edge.TOP || edge == Edge.BOTTOM) p.x else p.y

    /** A finger put down at [start] that moves toward [toward]: did it come on over an edge? */
    fun entering(start: Vec2, toward: Vec2, widthPx: Float, heightPx: Float, marginPx: Float): AtEdge? {
        val edge = edgeAt(start, widthPx, heightPx, marginPx) ?: return null
        val d = toward - start
        if (d.length <= 0f || dot(d, edge.outward) > -HEADING * d.length) return null
        return AtEdge(edge, along(edge, start))
    }

    /** A finger lifted at [end], coming from [from]: did it go off over an edge? */
    fun leaving(from: Vec2, end: Vec2, widthPx: Float, heightPx: Float, marginPx: Float): AtEdge? {
        val edge = edgeAt(end, widthPx, heightPx, marginPx) ?: return null
        val d = end - from
        if (d.length <= 0f || dot(d, edge.outward) < HEADING * d.length) return null
        return AtEdge(edge, along(edge, end))
    }

    private fun dot(a: Vec2, b: Vec2) = a.x * b.x + a.y * b.y
}
