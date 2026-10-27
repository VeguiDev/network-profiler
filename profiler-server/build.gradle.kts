plugins { `java-library` }
dependencies {
    api(project(":profiler-core"))
    implementation(libs.javalin)
    implementation(libs.jackson)
    implementation(libs.slf4j)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}
tasks.processResources {
    dependsOn(rootProject.tasks.named("frontendBuild"))
    from(rootProject.layout.projectDirectory.dir("profiler-web/dist")) { into("web") }
}
