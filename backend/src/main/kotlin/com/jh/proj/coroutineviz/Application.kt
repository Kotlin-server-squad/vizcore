package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()

    // Configure bounded EventStore before any sessions are created (FND-02)
    val maxEvents = environment.config.propertyOrNull("session.maxEvents")
        ?.getString()?.toIntOrNull() ?: 10_000
    SessionManager.configure(maxEventsPerSession = maxEvents)

    configureRouting()
}
