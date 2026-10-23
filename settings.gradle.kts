//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Payanam"

include(":app")
include(":desktop")
include(":core:shared")
include(":core:domain")
include(":core:database")
include(":core:database-model")
include(":core:database-jvm")
include(":core:scoring")
include(":core:common")
include(":core:ui")
include(":core:strings")
include(":core:presentation")
