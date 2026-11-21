package com.mubox.app.utils.parser

data class NodeLinkParserWebSocketPathConfig(
    val path: String,
    val maxEarlyData: Long?,
    val earlyDataHeaderName: String?
)
