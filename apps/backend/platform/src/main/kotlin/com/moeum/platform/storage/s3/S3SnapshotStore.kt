package com.moeum.platform.storage.s3

import com.moeum.platform.storage.SnapshotStore
import com.moeum.platform.storage.SnapshotStoreException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@Component
class S3SnapshotStore(
    private val s3Client: S3Client,
    private val properties: S3Properties,
) : SnapshotStore {

    private val log = LoggerFactory.getLogger(S3SnapshotStore::class.java)

    override fun put(key: String, content: ByteArray, contentType: String) {
        runCatching {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(properties.bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(content),
            )
        }.onFailure { e -> throw SnapshotStoreException("스냅샷 업로드 실패: key=$key", e) }
    }

    override fun get(key: String): ByteArray =
        runCatching {
            s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(properties.bucket).key(key).build(),
            ).asByteArray()
        }.getOrElse { e -> throw SnapshotStoreException("스냅샷 조회 실패: key=$key", e) }

    // 삭제 실패는 예외를 던지되, 호출부가 못 잡더라도 purge_after 폴백으로 나중에 정리된다(설계상 세이프티넷).
    override fun delete(key: String) {
        runCatching {
            s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build(),
            )
        }.onFailure { e ->
            log.warn("스냅샷 삭제 실패, purge_after 폴백에 의존: key={}, error={}", key, e.message)
            throw SnapshotStoreException("스냅샷 삭제 실패: key=$key", e)
        }
    }
}
