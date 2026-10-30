package com.moeum.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * docs/ARCHITECTURE.md 의 모듈 의존·스키마 소유 규칙을 강제한다.
 */
class ModuleDependencyRulesTest {

    private val importedClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.moeum")

    private val businessModules = listOf("identity", "conversation", "journal")

    @Test
    fun `모듈은 서로 순환 의존하지 않는다`() {
        slices().matching("com.moeum.(*)..")
            .should().beFreeOfCycles()
            .check(importedClasses)
    }

    @Test
    fun `identity는 다른 업무 모듈에 의존하지 않는다`() {
        noClasses().that().resideInAPackage("com.moeum.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.moeum.conversation..",
                "com.moeum.journal..",
            )
            .check(importedClasses)
    }

    @Test
    fun `모듈 소스는 자기 스키마의 테이블만 참조한다`() {
        // 테스트 작업 디렉터리는 bootstrap 모듈이다. 초기 스키마에서 schema.table 목록을 읽는다.
        val backend = Path.of("").toAbsolutePath().parent
        val tables = Regex("""CREATE TABLE (\w+)\.(\w+)""")
            .findAll(backend.resolve("bootstrap/src/main/resources/db/migration").toFile().walk()
                .filter { it.extension == "sql" }.joinToString("\n") { it.readText() })
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertThat(tables.map { it.first }.toSet()).containsExactlyInAnyOrderElementsOf(businessModules)

        (businessModules + "platform").forEach { module ->
            val sources = backend.resolve("$module/src/main/kotlin").toFile().walk().filter { it.extension == "kt" }.toList()
            assertThat(sources).describedAs(module).isNotEmpty()
            val violations = sources.flatMap { file ->
                val text = file.readText()
                tables.filter { (schema, table) ->
                    schema != module && Regex("""(?<![\w.])$schema\.$table\b""").containsMatchIn(text)
                }.map { (schema, table) -> "${file.name}: $schema.$table" } +
                    Regex("""schema\s*=\s*"(\w+)"""").findAll(text).map { it.groupValues[1] }
                        .filter { it != module }.map { "${file.name}: @Table(schema = \"$it\")" }
            }
            assertThat(violations).describedAs("$module 이 다른 모듈 스키마를 참조한다").isEmpty()
        }
    }

    @Test
    fun `업무 모듈은 publicapi를 통해서만 다른 모듈에 접근된다`() {
        businessModules.forEach { module ->
            classes().that().resideInAPackage("com.moeum.$module..")
                .and().resideOutsideOfPackage("com.moeum.$module.application.publicapi..")
                .should().onlyBeAccessed().byClassesThat().resideInAPackage("com.moeum.$module..")
                .check(importedClasses)
        }
    }
}
