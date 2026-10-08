plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// journal은 라이브러리 모듈이라 실행 가능한 jar가 필요 없다.
// spring-boot 플러그인은 BOM(dependency-management) 자동 임포트를 위해서만 적용한다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":platform"))
    implementation(project(":conversation"))
    // 기능 권한(FeatureAccessQuery) — identity publicapi만 쓴다
    implementation(project(":identity"))

    implementation(libs.spring.boot.starter.data.jpa)
    // LLM 응답(JSON) 파싱. 런타임 JsonMapper는 bootstrap의 web starter가 제공한다.
    compileOnly(libs.jackson.databind)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.security)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson.databind)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
