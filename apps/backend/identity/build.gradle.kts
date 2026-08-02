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

// identity는 라이브러리 모듈이라 실행 가능한 jar가 필요 없다.
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

    implementation(libs.spring.boot.starter.data.jpa)
    // 런타임 서블릿/웹 계층은 bootstrap의 spring-boot-starter-web이 제공한다.
    compileOnly(libs.spring.boot.starter.web)
    implementation(libs.google.api.client)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
