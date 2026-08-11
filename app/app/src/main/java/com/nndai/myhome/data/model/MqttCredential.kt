package com.nndai.myhome.data.model

data class MqttCredential(
    val username: String,
    val password: String
) {
    fun isValid(): Boolean = username.isNotBlank() && password.isNotBlank()
}
