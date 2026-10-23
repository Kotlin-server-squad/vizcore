package com.jh.proj.coroutineviz.health

/**
 * The session has no usable summaries: it was not created with `keepSummaries = true`, or applying an event to
 * the table failed and the table was abandoned (see `VizSession.readSummaries`).
 */
class SummariesUnavailableException(
    message: String,
) : IllegalStateException(message)
