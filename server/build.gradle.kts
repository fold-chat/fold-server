plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Conorrr/libsql-java")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR") ?: "token"
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN") ?: ""
        }
    }
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-websockets-next")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-cache")
    implementation("io.quarkus:quarkus-arc")
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.github.conorrr:libsql-java:0.2.2")
    compileOnly("org.graalvm.sdk:nativeimage:24.2.0")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

group = "chat.fold"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// --- Extract native library from libsql-java dependency (for native image distribution) ---
tasks.register("extractNativeLib") {
    description = "Extract platform-specific native library from libsql-java dependency"
    doLast {
        val libsqlJar = configurations["runtimeClasspath"].resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .first { it.name.startsWith("libsql-java") }
        project.copy {
            from(zipTree(libsqlJar)) {
                include("native/**")
            }
            into(layout.buildDirectory.dir("extracted-native"))
        }
    }
}

// --- Generate migration index (native image can't enumerate classpath directories) ---
val generateMigrationIndex = tasks.register("generateMigrationIndex") {
    val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration")
    val outputDir = layout.buildDirectory.dir("generated/sources/migrationIndex/java/main")
    inputs.dir(migrationDir)
    outputs.dir(outputDir)
    doLast {
        val pattern = Regex("V(\\d+)__.*\\.sql")
        val allSql = migrationDir.asFile.listFiles { f -> f.extension == "sql" }
            ?.sortedBy { it.name } ?: emptyList()

        // Fail on any .sql file that doesn't match the naming convention
        val invalid = allSql.filter { !it.name.matches(pattern) }
        if (invalid.isNotEmpty()) {
            error("Migration files don't match V<NNN>__<description>.sql:\n" +
                invalid.joinToString("\n") { "  ${it.name}" })
        }

        val files = allSql.sortedBy { it.name }
        val versions = files.map { pattern.matchEntire(it.name)!!.groupValues[1].toInt() }

        // Fail on duplicate versions
        val duplicates = versions.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            error("Duplicate migration versions: ${duplicates.joinToString(", ")}")
        }

        // Fail on gaps in version sequence
        versions.zipWithNext { a, b ->
            if (b != a + 1) error("Gap in migration versions between V$a and V$b")
        }

        val filesList = files.joinToString(",\n                    ") { "\"${it.name}\"" }
        val src = outputDir.get().dir("chat/fold/db").asFile
        src.mkdirs()
        src.resolve("MigrationIndex.java").writeText("""
            package chat.fold.db;
            import java.util.List;
            public final class MigrationIndex {
                private MigrationIndex() {}
                public static final List<String> FILES = List.of(
                    $filesList
                );
            }
        """.trimIndent())
    }
}
sourceSets.main { java.srcDir(generateMigrationIndex.map { it.outputs.files.singleFile }) }
tasks.named("compileJava") { dependsOn(generateMigrationIndex) }

// --- Generate BuildInfo with version string ---
val generateVersionInfo = tasks.register("generateVersionInfo") {
    val outputDir = layout.buildDirectory.dir("generated/sources/versionInfo/java/main")
    outputs.dir(outputDir)
    doLast {
        val version = project.findProperty("fold.build.version")?.toString()
            ?: System.getenv("FOLD_BUILD_VERSION")
            ?: "snapshot"
        val src = outputDir.get().dir("chat/fold/config").asFile
        src.mkdirs()
        src.resolve("BuildInfo.java").writeText("""
            package chat.fold.config;
            public final class BuildInfo {
                private BuildInfo() {}
                public static final String VERSION = "$version";
            }
        """.trimIndent())
    }
}
sourceSets.main { java.srcDir(generateVersionInfo.map { it.outputs.files.singleFile }) }
tasks.named("compileJava") { dependsOn(generateVersionInfo) }

// --- Client build ---
tasks.register("buildClient") {
    description = "Build SvelteKit client and copy to META-INF/resources"
    doLast {
        providers.exec {
            workingDir(layout.projectDirectory.dir("../client"))
            commandLine("pnpm", "install")
        }.result.get()
        providers.exec {
            workingDir(layout.projectDirectory.dir("../client"))
            commandLine("pnpm", "build")
        }.result.get()
        val clientBuild = layout.projectDirectory.dir("../client/build").asFile
        val targetDir = layout.projectDirectory.dir("src/main/resources/META-INF/resources").asFile
        targetDir.mkdirs()
        clientBuild.copyRecursively(targetDir, overwrite = true)
    }
}

