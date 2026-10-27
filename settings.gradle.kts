pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "minecraft-network-profiler"
include("profiler-api", "profiler-core", "profiler-netty", "profiler-server", "profiler-platforms:velocity")
