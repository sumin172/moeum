package com.moeum.capacity

/** 성능 목표와 실측을 섞지 않는다. 모든 값은 보고서에 그대로 남긴다. */
data class CapacitySettings(
    val profile: String = System.getProperty("capacity.profile", "P0"),
    val scenario: String = System.getProperty("capacity.scenario", "smoke"),
    val durationSeconds: Int = number("durationSeconds", 20),
    val warmupSeconds: Int = number("warmupSeconds", 5),
    val rps: Int = number("rps", when (System.getProperty("capacity.profile", "P0")) { "P1" -> 250; "P2" -> 2500; else -> 25 }),
    val conversationSlots: Int = number("conversationSlots", 8),
    val journalSlots: Int = number("journalSlots", 2),
    val conversationReplicas: Int = number("conversationReplicas", 1),
    val conversationBatchSize: Int = number("conversationBatchSize", 32),
    val conversationDelayMs: Int = number("conversationDelayMs", 100),
    val journalDelayMs: Int = number("journalDelayMs", 200),
    val historyRecords: Int = number("historyRecords", 0),
    val journalBurst: Int = number("journalBurst", 100),
    val drainSeconds: Int = number("drainSeconds", 120),
    val journalDrainSeconds: Int = number("journalDrainSeconds", 0),
    val apiPool: Int = number("apiPool", 10),
    val conversationPool: Int = number("conversationPool", 4),
    val journalPool: Int = number("journalPool", 2),
) {
    init {
        require(profile in setOf("P0", "P1", "P2"))
        require(scenario in setOf("smoke", "benchmark", "sustained", "failure"))
        require(durationSeconds in 5..14400 && warmupSeconds in 0..600)
        require(rps in 1..2500 && conversationReplicas in 1..8)
        require(conversationBatchSize in 1..64)
        require(conversationSlots in 1..1024 && journalSlots in 1..1024)
        require(conversationDelayMs in 0..30000 && journalDelayMs in 0..30000)
        require(historyRecords in 0..100000 && journalBurst in 0..100000)
        require(drainSeconds in 1..7200 && journalDrainSeconds in 0..7200)
        require(apiPool in 2..32 && conversationPool in 1..16 && journalPool in 1..16)
    }
    companion object {
        private fun number(name: String, default: Int) = System.getProperty("capacity.$name")?.toInt() ?: default
        const val JWT_SECRET = "capacity-only-local-signing-secret-32-bytes-minimum"
    }
}
