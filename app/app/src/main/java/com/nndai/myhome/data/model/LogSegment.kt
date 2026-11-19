package com.nndai.myhome.data.model

data class LogSegment(
    val stream: Int,
    val generation: Long,
    val id: Long,
    val size: Long,
    val minEpoch: Long,
    val maxEpoch: Long,
    val sealed: Boolean
)
