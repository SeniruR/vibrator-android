// Top-level build file with basic settings for a minimal project
plugins {
    id("com.android.application") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}

task("clean") {
    doLast {
        println("clean")
    }
}
