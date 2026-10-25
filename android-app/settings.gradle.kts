pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }  // build-time only; the app itself has no network code
rootProject.name = "ViNT"
include(":app")
