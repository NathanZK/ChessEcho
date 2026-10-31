plugins {
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
    id("info.solidsoft.pitest") version "1.15.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.chessecho"
version = "0.0.1-SNAPSHOT"

// Spring Boot's dependency-management BOM pins org.testcontainers artifacts to an
// older release (1.19.8) that is incompatible with Docker Engine 29+'s minimum
// supported client API version. Override the BOM property so the whole
// org.testcontainers dependency graph resolves consistently to the compatible
// version declared below, instead of only the directly-declared artifacts.
extra["testcontainers.version"] = "1.21.4"
extra["hibernate.version"] = "6.5.3.Final"

// Targeted Spring Boot BOM property overrides to remediate Dependabot alerts
// without altering the Spring Boot 3.3.2 baseline (Issue #616).
extra["tomcat.version"] = "10.1.61"
extra["jackson-bom.version"] = "2.18.12"
// Note: Logback upgrades (>1.5.6) require Spring Boot 3.3.6+ / 3.5+ due to binary
// incompatibility in LoggerContext.getConfigurationLock(); tracked in framework migration Issue #617.
extra["postgresql.version"] = "42.7.14"
extra["commons-lang3.version"] = "3.21.0"
extra["httpclient5.version"] = "5.4.3"
extra["httpcore5.version"] = "5.3.3"
extra["assertj.version"] = "3.27.7"
extra["json-smart.version"] = "2.5.2"
extra["xmlunit2.version"] = "2.10.0"
extra["log4j2.version"] = "2.25.5"
extra["artemis.version"] = "2.40.0"
extra["commons-compress.version"] = "1.28.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.15")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Apache HttpClient5 backs TestRestTemplate so it can send the credentialed-CORS
    // preflight headers (Origin, Access-Control-Request-Method) that the JDK client drops.
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ktlint {
    version.set("1.4.1")
    verbose.set(true)
    outputToConsole.set(true)
}

pitest {
    pitestVersion.set("1.15.8")
    junit5PluginVersion.set("1.2.1")
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)

    val targetClassesProp = providers.gradleProperty("pitest.targetClasses").orNull?.takeIf { it.isNotBlank() }
    val targetTestsProp = providers.gradleProperty("pitest.targetTests").orNull?.takeIf { it.isNotBlank() }

    val hasTargetClasses = targetClassesProp != null
    val hasTargetTests = targetTestsProp != null

    if (hasTargetClasses xor hasTargetTests) {
        throw InvalidUserDataException(
            "Both 'pitest.targetClasses' and 'pitest.targetTests' must be specified together, but only one was provided.",
        )
    }

    val (effectiveTargetClasses, effectiveTargetTests) =
        if (hasTargetClasses && hasTargetTests) {
            val parsedClasses = targetClassesProp!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parsedClasses.isEmpty()) {
                throw InvalidUserDataException(
                    "'pitest.targetClasses' was provided but contains no usable target patterns.",
                )
            }
            val parsedTests = targetTestsProp!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parsedTests.isEmpty()) {
                throw InvalidUserDataException(
                    "'pitest.targetTests' was provided but contains no usable target patterns.",
                )
            }
            parsedClasses to parsedTests
        } else {
            listOf("com.chessecho.service.AdaptivePuzzleSchedulingPolicy*") to
                listOf("com.chessecho.service.AdaptivePuzzleSchedulingPolicyTest*")
        }

    targetClasses.set(effectiveTargetClasses)
    targetTests.set(effectiveTargetTests)
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom(files("$projectDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
}

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektMain") {
    baseline.set(file("$projectDir/config/detekt/baseline-main.xml"))
}

tasks.named<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>("detektBaselineMain") {
    baseline.set(file("$projectDir/config/detekt/baseline-main.xml"))
}

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektTest") {
    baseline.set(file("$projectDir/config/detekt/baseline-test.xml"))
}

tasks.named<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>("detektBaselineTest") {
    baseline.set(file("$projectDir/config/detekt/baseline-test.xml"))
}

// Spring Boot dependency-management plugin overrides Kotlin compiler dependencies in the
// detekt configuration to Spring Boot's BOM version (2.0.0). Restore Detekt's compiled version
// (2.0.10) for its dedicated execution configuration per https://detekt.dev/docs/gettingstarted/gradle#dependencies
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                io.gitlab.arturbosch.detekt
                    .getSupportedKotlinVersion(),
            )
        }
    }
}

tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

// Disable untyped detekt task so check executes type-resolved detektMain and detektTest
tasks.named("detekt") {
    enabled = false
}
