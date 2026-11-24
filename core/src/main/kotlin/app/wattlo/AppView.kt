package app.wattlo

/**
 * How the app screens draw the schedule: the day ring (the original), a line (a bar for the day, or a window of
 * 12 hours on the watch), a vertical timeline (phone) or the coming phases one under another (watch).
 */
enum class AppView {
    RING, LINE, TIMELINE, STAGES;

    companion object {
        val PHONE = setOf(RING, LINE, TIMELINE)
        val WATCH = setOf(RING, LINE, STAGES)

        /** A stored name, or the ring when it is unknown or not offered on that device. */
        fun of(name: String?, allowed: Set<AppView>): AppView =
            entries.firstOrNull { it.name == name && it in allowed } ?: RING
    }
}
