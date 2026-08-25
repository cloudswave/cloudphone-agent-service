package com.cloudphone.agentservice

/**
 * Agent configuration model.
 * Parses shell-style config: KEY='value'
 */
data class AgentConfig(
    val agentId: String = "",
    val signaling: String = "",
    val iceServers: String = ""
) {
    fun isValid(): Boolean = agentId.isNotBlank() && signaling.isNotBlank()

    companion object {
        fun load(content: String): AgentConfig {
            var id = ""
            var sig = ""
            var ice = ""
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) return@forEach
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    var value = trimmed.substring(eqIdx + 1).trim()
                    if (value.startsWith("'") && value.endsWith("'"))
                        value = value.substring(1, value.length - 1)
                    if (value.startsWith("\"") && value.endsWith("\""))
                        value = value.substring(1, value.length - 1)
                    when (key) {
                        "AGENT_ID" -> id = value
                        "SIGNALING" -> sig = value
                        "ICE_SERVERS" -> ice = value
                    }
                }
            }
            return AgentConfig(id, sig, ice)
        }
    }
}