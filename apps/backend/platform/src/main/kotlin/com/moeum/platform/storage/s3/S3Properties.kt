package com.moeum.platform.storage.s3

import org.springframework.boot.context.properties.ConfigurationProperties

// Garage(로컬/운영 초기)든 향후 AWS S3든 동일한 S3 API를 쓰므로 설정값만 바뀐다.
@ConfigurationProperties(prefix = "moeum.storage.s3")
data class S3Properties(
    val endpoint: String,
    val region: String = "garage",
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyleAccess: Boolean = true,
)
