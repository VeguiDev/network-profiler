import java.net.URI

plugins { `java-library`; alias(libs.plugins.shadow) }

abstract class GenerateRelayConfiguration : DefaultTask() {
    @get:Input abstract val relayUrl: Property<String>
    @get:Input abstract val publicWebUrl: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction fun generate() {
        fun origin(value: String): String {
            val uri = URI(value)
            val local = uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")
            require((uri.scheme == "https" || local) && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/")) {
                "Build-time relay URLs must be HTTPS origins (HTTP loopback only for development)"
            }
            return uri.toASCIIString().removeSuffix("/")
        }
        val content = "endpoint=${origin(relayUrl.get())}\npublic-web-url=${origin(publicWebUrl.get())}\n"
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(content, Charsets.UTF_8) }
    }
}

val compiledRelayUrl = providers.environmentVariable("VNP_RELAY_URL").orElse("https://networkprofiler.vegui.dev")
val generateRelayConfiguration = tasks.register<GenerateRelayConfiguration>("generateRelayConfiguration") {
    relayUrl.set(compiledRelayUrl)
    publicWebUrl.set(providers.environmentVariable("VNP_PUBLIC_WEB_URL").orElse(compiledRelayUrl))
    outputFile.set(layout.buildDirectory.file("generated/relay-resources/vnp-relay.properties"))
}
sourceSets.main { resources.srcDir(generateRelayConfiguration.map { it.outputFile.get().asFile.parentFile }) }

abstract class GeneratePluginVersion : DefaultTask() {
    @get:Input abstract val pluginVersion: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val value = pluginVersion.get()
        require(value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))) {
            "Plugin version must be a semantic version"
        }
        outputDirectory.file("dev/vegui/vnp/velocity/PluginBuildVersion.java").get().asFile.apply {
            parentFile.mkdirs()
            writeText("package dev.vegui.vnp.velocity;\nfinal class PluginBuildVersion {\n    static final String VERSION = \"$value\";\n    private PluginBuildVersion() {}\n}\n", Charsets.UTF_8)
        }
    }
}
val generatePluginVersion = tasks.register<GeneratePluginVersion>("generatePluginVersion") {
    pluginVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/plugin-version"))
}
sourceSets.main { java.srcDir(generatePluginVersion.flatMap { it.outputDirectory }) }

dependencies {
    implementation(project(":profiler-core"))
    implementation(project(":profiler-netty"))
    implementation(project(":profiler-relay"))
    implementation("org.yaml:snakeyaml:2.7")
    compileOnly(libs.velocity)
    annotationProcessor(libs.velocity)
    compileOnly(libs.netty)
    compileOnly(libs.slf4j)
    compileOnly(libs.packetevents)
    testImplementation(libs.netty)
    testImplementation(libs.slf4j)
    testImplementation(libs.packetevents)
    testImplementation(libs.velocity)
    testImplementation(libs.jackson)
}
tasks.shadowJar {
    archiveBaseName.set("vegui-network-profiler-velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
    dependencies { exclude(dependency("org.slf4j:slf4j-api:.*")) }
    relocate("tools.jackson", "dev.vegui.vnp.internal.jackson")
    relocate("com.fasterxml.jackson", "dev.vegui.vnp.internal.jacksonannotations")
    relocate("org.yaml.snakeyaml", "dev.vegui.vnp.internal.yaml")
    relocate("kotlin", "dev.vegui.vnp.internal.kotlin")
    relocate("org.intellij", "dev.vegui.vnp.internal.intellij")
    relocate("org.jetbrains", "dev.vegui.vnp.internal.jetbrains")
}
tasks.jar { archiveClassifier.set("thin") }
tasks.assemble { dependsOn(tasks.shadowJar) }
