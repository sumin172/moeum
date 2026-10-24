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
    implementation(libs.spring.boot.starter.actuator)
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
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("capacity", "provider-smoke") }
}

tasks.register<Test>("capacityTest") {
    description = "Disposable PostgreSQL and separate JVM capacity/failure experiments"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("capacity") }
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    doFirst {
        val runtimeClasspathFile = layout.buildDirectory.file("capacity/runtime-classpath.txt").get().asFile
        runtimeClasspathFile.parentFile.mkdirs()
        runtimeClasspathFile.writeText(sourceSets.test.get().runtimeClasspath.asPath)
        systemProperty("capacity.classpathFile", runtimeClasspathFile.absolutePath)
    }
    providers.gradlePropertiesPrefixedBy("capacity.").get().forEach { (key, value) -> systemProperty(key, value) }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("providerSmokeTest") {
    description = "At most two synthetic Gemini requests; requires GEMINI_API_KEY"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("provider-smoke") }
    outputs.upToDateWhen { false }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=local")
}
