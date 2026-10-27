plugins { `java-library`; alias(libs.plugins.shadow) }
dependencies {
    implementation(project(":profiler-core"))
    implementation(project(":profiler-netty"))
    implementation(project(":profiler-server"))
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
}
tasks.shadowJar {
    archiveBaseName.set("minecraft-network-profiler-velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
    dependencies { exclude(dependency("org.slf4j:slf4j-api:.*")) }
    relocate("io.javalin", "dev.networkprofiler.internal.javalin")
    relocate("org.eclipse.jetty", "dev.networkprofiler.internal.jetty")
    relocate("jakarta.servlet", "dev.networkprofiler.internal.servlet")
    relocate("tools.jackson", "dev.networkprofiler.internal.jackson")
    relocate("com.fasterxml.jackson", "dev.networkprofiler.internal.jacksonannotations")
    relocate("org.yaml.snakeyaml", "dev.networkprofiler.internal.yaml")
    relocate("kotlin", "dev.networkprofiler.internal.kotlin")
    relocate("org.intellij", "dev.networkprofiler.internal.intellij")
    relocate("org.jetbrains", "dev.networkprofiler.internal.jetbrains")
}
tasks.jar { archiveClassifier.set("thin") }
tasks.assemble { dependsOn(tasks.shadowJar) }
