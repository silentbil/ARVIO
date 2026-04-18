package com.lagradost.cloudstream3

enum class ActorRole {
    Main,
    Supporting,
    Background
}

data class Actor(
    val name: String,
    val image: String? = null
)

data class ActorData(
    val actor: Actor,
    val role: ActorRole? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null
)
