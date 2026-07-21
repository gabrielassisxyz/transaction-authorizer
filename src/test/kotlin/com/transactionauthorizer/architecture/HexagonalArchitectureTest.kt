package com.transactionauthorizer.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

@AnalyzeClasses(
    packages = ["com.transactionauthorizer"],
    importOptions = [DoNotIncludeTests::class],
)
class HexagonalArchitectureTest {
    @ArchTest
    val layersRespectDependencyDirection: ArchRule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("domain")
            .definedBy("com.transactionauthorizer.domain..")
            .layer("application")
            .definedBy("com.transactionauthorizer.application..")
            .layer("adapter")
            .definedBy("com.transactionauthorizer.adapter..")
            .whereLayer("adapter")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("application")
            .mayOnlyBeAccessedByLayers("adapter")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("application", "adapter")

    @ArchTest
    val domainKnowsNoFramework: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("com.transactionauthorizer.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "org.hibernate..",
                "tools.jackson..",
                "com.fasterxml.jackson..",
            )

    @ArchTest
    val adaptersDoNotTalkToEachOther: ArchRule =
        slices()
            .matching("com.transactionauthorizer.adapter.(*).(*)..")
            .should()
            .notDependOnEachOther()

    @ArchTest
    val jpaEntitiesLiveInThePersistenceAdapter: ArchRule =
        noClasses()
            .that()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .resideOutsideOfPackage("com.transactionauthorizer.adapter.outbound.persistence..")

    @ArchTest
    val jpaEntitiesAreNotDataClasses: ArchRule =
        noClasses()
            .that()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should(declareKotlinCopyMethod)
}

private val declareKotlinCopyMethod: ArchCondition<JavaClass> =
    object : ArchCondition<JavaClass>("be a Kotlin data class (declare `copy`)") {
        override fun check(
            item: JavaClass,
            events: ConditionEvents,
        ) {
            val isDataClass = item.methods.any { it.name == "copy" }
            events.add(
                SimpleConditionEvent(
                    item,
                    isDataClass,
                    "${item.name} is ${if (isDataClass) "" else "not "}a data class",
                ),
            )
        }
    }
