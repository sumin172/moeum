package com.moeum.platform.storage

interface SnapshotStore {
    fun put(key: String, content: ByteArray, contentType: String = "application/octet-stream")
    fun get(key: String): ByteArray
    fun delete(key: String)
}

class SnapshotStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
