plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Контракт и всё остальное плагин берёт из клиента в рантайме:
    // DexClassLoader получает parent = classloader приложения, поэтому
    // дублировать эти классы внутрь dex нельзя и не нужно.
    compileOnly(project(":provider-api"))
    compileOnly("org.json:json:20240303")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // org.json нужен тестам по-настоящему: на JVM его нет в составе JDK.
    // provider-api — потому что compileOnly в тестовый classpath не попадает,
    // а парсер возвращает VideoItem/Comment/Author из него.
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":provider-api"))
    testImplementation("org.json:json:20240303")
    // runBlocking в тестах сессии: restore() — suspend.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

tasks.jar {
    archiveBaseName.set("provider-tiktok")
    // В dex попадают только классы плагина.
}
