package com.ahmad.aternoswatcher

data class ServerConfig(val name: String, val address: String)

object ServerConfig {
    val servers = listOf(
        ServerConfig("ABD Survival", "abd999alrahem.aternos.me:51889"),
        ServerConfig("MACE SMP 37", "MACESMP37.aternos.me:44114"),
        ServerConfig("MACE SMP X99", "Macesmpx99.aternos.me:35419")
    )
    const val PREFS = "server_states"
    const val WORK_NAME = "aternos_server_monitor"
}
