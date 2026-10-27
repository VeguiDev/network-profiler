plugins { `java-library` }
dependencies {
    api(project(":profiler-core"))
    implementation(libs.jackson)
    implementation(libs.slf4j)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}
