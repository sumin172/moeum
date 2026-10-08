package com.moeum.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

/**
 * docs/ARCHITECTURE.md 의 모듈 의존 규칙을 강제한다.
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
    fun `업무 모듈은 publicapi를 통해서만 다른 모듈에 접근된다`() {
        businessModules.forEach { module ->
            classes().that().resideInAPackage("com.moeum.$module..")
                .and().resideOutsideOfPackage("com.moeum.$module.application.publicapi..")
                .should().onlyBeAccessed().byClassesThat().resideInAPackage("com.moeum.$module..")
                .check(importedClasses)
        }
    }
}
