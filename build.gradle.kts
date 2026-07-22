plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
}

group = "com.transactionauthorizer"
version = "0.0.1-SNAPSHOT"
description = "Transaction authorization API for a bank account balance"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Micrometer's Prometheus registry: the scrape endpoint at /actuator/prometheus is what
    // the load campaign and dashboards read, so it ships with the app, not as a test-only dep.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.sqs)
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Boot 4 split autoconfiguration per technology: `flyway-core` alone is on the
    // classpath but never wired, so the starter is what actually runs the migrations.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed every module to the `testcontainers-` prefix; the 1.x
    // coordinates (`org.testcontainers:postgresql`) no longer exist in the BOM.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-localstack")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The junit5 plugin on the pitest classpath is enough for PIT to detect the engine.
    pitest(libs.pitest.junit5)
}

pitest {
    // Mutation testing is scoped to the pure domain: that is where the arithmetic and the
    // invariants live, so a surviving mutant there points at a genuinely weak test. It is
    // advisory and never a merge gate, so no mutation threshold is set: the report informs,
    // it does not fail the build. It runs as its own non-blocking CI job; bin/ci stays the
    // single blocking gate.
    targetClasses.set(listOf("com.transactionauthorizer.domain.*"))
    targetTests.set(listOf("com.transactionauthorizer.domain.*"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// detekt 1.23.x is built against Kotlin 2.0.21 and refuses to run under the project's
// Kotlin; pinning detekt's own runtime classpath to the expected version is the
// workaround detekt documents (https://detekt.dev/docs/gettingstarted/gradle#dependencies).
// Drop it once detekt supports the project's Kotlin.
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
        }
    }
}

kover {
    reports {
        filters {
            includes {
                // The coverage gate applies where the logic lives; adapters and wiring
                // stay out so it does not push anyone into testing getters.
                classes("com.transactionauthorizer.domain.*", "com.transactionauthorizer.application.*")
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
