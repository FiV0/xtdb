package xtdb.http

import kotlinx.serialization.Serializable

@Serializable
data class AuthOptions(val user: String = "xtdb", val password: String = "xtdb")
