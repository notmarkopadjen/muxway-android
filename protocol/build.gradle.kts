plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android ships org.json; the JVM tests bring their own copy.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.google.zxing:core:3.5.3")
}

tasks.test {
    // The end-to-end suite reads these from scripts/e2e.sh.
    // As inputs too, so a new run directory means the suite really runs again.
    for (name in listOf("MUXWAY_BIN", "MUXWAY_RUN_DIR", "MUXWAY_CORE")) {
        val v = System.getenv(name) ?: ""
        inputs.property(name, v)
        if (v.isNotEmpty()) environment(name, v)
    }
    testLogging { events("failed") }
}
