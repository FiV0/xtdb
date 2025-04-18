import xtdb.DataReaderTransformer

plugins {
    java
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":xtdb-core"))
    implementation(project(":modules:xtdb-kafka"))
    implementation(project(":xtdb-http-server"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

application {
    mainClass.set("clojure.main")
}

tasks.shadowJar {
    archiveBaseName.set("xtdb")
    archiveVersion.set("")
    archiveClassifier.set("monitoring")
    mergeServiceFiles()
    transform(DataReaderTransformer())
}

