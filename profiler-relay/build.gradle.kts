plugins { `java-library` }
dependencies {
    api(project(":profiler-server"))
    implementation(libs.jackson)
}
