plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "com.transactionauthorizer"
version = "0.0.1-SNAPSHOT"
description = "API de autorizacao de transacoes financeiras"

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
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// detekt 1.23.x é compilado com Kotlin 2.0.21 e recusa rodar sob o Kotlin do projeto;
// fixar o classpath de runtime do detekt na versão esperada é o workaround documentado
// (https://detekt.dev/docs/gettingstarted/gradle#dependencies). Remover quando o
// detekt passar a suportar o Kotlin do projeto.
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
                // O gate de cobertura vale onde mora a lógica; adapters e wiring ficam
                // de fora para não induzir teste de getter.
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
