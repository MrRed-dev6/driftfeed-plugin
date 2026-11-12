pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tiktok-plugin"

// provider-api — вендорная копия контракта клиента. Она должна совпадать
// с клиентской по MAJOR-версии и по сигнатурам интерфейсов; расхождение
// ловится при загрузке, но лучше не доводить.
include(":provider-api")
include(":plugin-tiktok")
