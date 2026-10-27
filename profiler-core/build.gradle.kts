plugins { `java-library` }
dependencies { api(project(":profiler-api")) }

tasks.register<JavaExec>("benchmarkCapture") {
    group = "verification"
    description = "Indicative capture-counter timing/allocation probe, not a production overhead benchmark"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.vegui.vnp.core.PacketCaptureBenchmark")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
