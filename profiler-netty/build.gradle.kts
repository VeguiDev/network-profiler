plugins { `java-library` }
dependencies {
    api(project(":profiler-api"))
    compileOnly(libs.netty)
    testImplementation(libs.netty)
    testImplementation(project(":profiler-core"))
}
