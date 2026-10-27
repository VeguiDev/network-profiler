plugins { base; alias(libs.plugins.shadow) apply false }

allprojects {
    group = "dev.vegui.vnp"
    version = providers.gradleProperty("releaseVersion").orElse(providers.gradleProperty("vnpVersion")).get()
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
    }
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(if (path == ":profiler-platforms:velocity") 25 else 21))
        withSourcesJar()
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); testLogging { events("failed", "skipped") } }
}

fun npmCommand(vararg arguments: String): List<String> =
    (if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) listOf("cmd", "/c", "npm") else listOf("npm")) + arguments

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir("profiler-web")
    commandLine(npmCommand("ci", "--no-audit", "--no-fund"))
    inputs.files("profiler-web/package.json", "profiler-web/package-lock.json")
    outputs.dir("profiler-web/node_modules")
}
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir("profiler-web")
    commandLine(npmCommand("run", "build"))
    inputs.dir("profiler-web/src")
    inputs.files(fileTree("profiler-web") { include("*.json", "*.ts", "*.mjs") })
    outputs.dir("profiler-web/dist")
}
val frontendTest = tasks.register<Exec>("frontendTest") {
    dependsOn(frontendInstall)
    workingDir("profiler-web")
    commandLine(npmCommand("test"))
}
val backendInstall = tasks.register<Exec>("backendInstall") {
    workingDir("profiler-backend"); commandLine(npmCommand("ci", "--no-audit", "--no-fund"))
    inputs.files("profiler-backend/package.json", "profiler-backend/package-lock.json"); outputs.dir("profiler-backend/node_modules")
}
val backendBuild = tasks.register<Exec>("backendBuild") {
    dependsOn(backendInstall); workingDir("profiler-backend"); commandLine(npmCommand("run", "build"))
}
val backendTest = tasks.register<Exec>("backendTest") {
    dependsOn(backendInstall); workingDir("profiler-backend"); commandLine(npmCommand("test"))
}
tasks.named("check") { dependsOn(frontendTest, backendBuild, backendTest, subprojects.map { it.tasks.named("check") }) }
tasks.named("assemble") { dependsOn(":profiler-platforms:velocity:shadowJar", frontendBuild) }
tasks.wrapper {
    gradleVersion = "9.8.2"
    distributionSha256Sum = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
}
