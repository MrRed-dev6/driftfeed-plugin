plugins {
    kotlin("jvm")
}

// Чистый Kotlin-модуль без Android SDK: от него зависят и клиент, и плагин,
// так что он обязан собираться для обеих сторон одинаково.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
