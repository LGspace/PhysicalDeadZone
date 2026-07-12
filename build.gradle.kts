// 根目录 build.gradle.kts

//以此处为准，AGP版本是 7.3.1 (对应 gradle 7.5)
plugins {
    id("com.android.application") version "7.3.1" apply false
    id("com.android.library") version "7.3.1" apply false
    id("org.jetbrains.kotlin.android") version "1.7.20" apply false
}

// 这里的 clean 是标准写法，不要删
tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}