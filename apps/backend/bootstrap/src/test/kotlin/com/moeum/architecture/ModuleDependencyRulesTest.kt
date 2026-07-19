package com.moeum.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

/**
 * docs/ARCHITECTURE.md 의 모듈 의존 규칙을 강제한다.
 * Stage 0 시점엔 모듈 내부 클래스가 거의 없어 대부분 공허하게 통과하며,
 * Stage 1 이후 실제 코드가 늘어나면서부터 가드레일로 작동한다.
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
            .allowEmptyShould(true) // Stage 0: identity 패키지에 아직 클래스가 없음
            .check(importedClasses)
    }

    @Test
    fun `journal은 conversation에 직접 의존하지 않는다`() {
        noClasses().that().resideInAPackage("com.moeum.journal..")
            .should().dependOnClassesThat().resideInAPackage("com.moeum.conversation..")
            .allowEmptyShould(true) // Stage 0: journal 패키지에 아직 클래스가 없음
            .check(importedClasses)
    }

    @Test
    fun `업무 모듈은 publicapi를 통해서만 다른 모듈에 접근된다`() {
        businessModules.forEach { module ->
            classes().that().resideInAPackage("com.moeum.$module..")
                .and().resideOutsideOfPackage("com.moeum.$module.application.publicapi..")
                .should().onlyBeAccessed().byClassesThat().resideInAPackage("com.moeum.$module..")
                .allowEmptyShould(true) // Stage 0: 업무 모듈에 아직 클래스가 없음
                .check(importedClasses)
        }
    }
}
