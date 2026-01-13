plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
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
    compileOnly("org.graalvm.sdk:nativeimage:24.2.0")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}

group = "chat.fray"
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

// --- libsql native library build ---
val libsqlDir = layout.projectDirectory.dir("libsql-c")
val nativeDir = layout.projectDirectory.dir("native")

fun osArch(): String {
    val os = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("mac") || it.contains("darwin") -> "darwin"
            it.contains("win") -> "windows"
            else -> "linux"
        }
    }
    val arch = System.getProperty("os.arch").let {
        when (it) {
            "aarch64" -> "aarch64"
            "amd64", "x86_64" -> "amd64"
            else -> it
        }
    }
    return "$os-$arch"
}

fun libName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "liblibsql.dylib"
        os.contains("win") -> "libsql.dll"
        else -> "liblibsql.so"
    }
}

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

tasks.register<Exec>("cloneLibsql") {
    description = "Clone libsql-c repo if not present"
    onlyIf { !libsqlDir.asFile.exists() }
    commandLine("git", "clone", "--depth", "1", "https://github.com/tursodatabase/libsql-c.git", libsqlDir.asFile.absolutePath)
}

tasks.register<Exec>("buildLibsql") {
    description = "Build liblibsql native library from source"
    dependsOn("cloneLibsql")
    val outputFile = nativeDir.dir(osArch()).file(libName())
    onlyIf { !outputFile.asFile.exists() }
    workingDir(libsqlDir)
    commandLine("cargo", "build", "--release")
    doLast {
        val targetLib = libsqlDir.dir("target/release").file(libName()).asFile
        val destDir = nativeDir.dir(osArch()).asFile
        destDir.mkdirs()
        targetLib.copyTo(destDir.resolve(libName()), overwrite = true)
    }
}
