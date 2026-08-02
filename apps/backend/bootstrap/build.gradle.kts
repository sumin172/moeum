plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":platform"))
    implementation(project(":identity"))
    implementation(project(":conversation"))
    implementation(project(":journal"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    // MoeumApplication의 UserDetailsServiceAutoConfiguration 참조를 컴파일하기 위해 필요.
    // 런타임 보안 설정 자체는 platform 모듈이 제공한다.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.kotlin.reflect)

    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=local")
}
