plugins {
    `java-library`
    id("dev.clojurephant.clojure")
    `maven-publish`
    signing
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB HTTP Server")
            description.set("XTDB HTTP Server")
        }
    }
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    api(project(":wire-formats"))

    api("ring", "ring-core", "1.10.0")
    api("info.sunng", "ring-jetty9-adapter", "0.22.4")
    api("org.eclipse.jetty", "jetty-alpn-server", "10.0.15")

    api("metosin", "muuntaja", "0.6.8")
    api("metosin", "jsonista", "0.3.3")
    api("metosin", "reitit-core", "0.7.0-alpha7")
    api("metosin", "reitit-interceptors", "0.7.0-alpha7")
    api("metosin", "reitit-ring", "0.7.0-alpha7")
    api("metosin", "reitit-http", "0.7.0-alpha7")
    api("metosin", "reitit-sieppari", "0.7.0-alpha7")
    api("metosin", "reitit-swagger", "0.7.0-alpha7")
    api("metosin", "reitit-spec", "0.7.0-alpha7")
    api("metosin", "ring-swagger", "0.26.2")
    // api("metosin", "ring-swagger-ui", "4.19.1")
    api("ring-cors", "ring-cors", "0.1.13")

    api("com.cognitect", "transit-clj", "1.0.329")
}
