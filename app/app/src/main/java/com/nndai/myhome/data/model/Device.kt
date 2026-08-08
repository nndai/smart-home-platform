package com.nndai.myhome.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val device_id: String, // "dev-xxx"
    val name: String,
    val profile: String, // "pump", "switch", "fan"
    val status: String? = null,
    val owner_id: String? = null
)
