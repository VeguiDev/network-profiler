pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "vegui-network-profiler"
include("profiler-api", "profiler-core", "profiler-netty", "profiler-server", "profiler-relay", "profiler-platforms:velocity")
