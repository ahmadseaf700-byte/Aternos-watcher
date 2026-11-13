package com.ahmad.aternoswatcher

data class ServerEntry(val name: String, val address: String)

object ServerConfig {
    val servers = listOf(
        ServerEntry("ABD Survival", "abd999alrahem.aternos.me:51889"),
        ServerEntry("MACE SMP 37", "MACESMP37.aternos.me:44114"),
        ServerEntry("MACE SMP X99", "Macesmpx99.aternos.me:35419")
    )
    const val PREFS = "server_states"
    const val WORK_NAME = "aternos_server_monitor"
}
