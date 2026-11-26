// 把 Maven Central 排到镜像之后（仅 CI 使用）。
//
// Gradle 遇到 HTTP 429 不会继续尝试下一个仓库，而是直接失败；CI runner 共享出口 IP
// 时常被 Central 限流，而仓库里已配的镜像只有排在 Central 之前才会被用到。
gradle.settingsEvaluated {
    fun pushCentralLast(handler: org.gradle.api.artifacts.dsl.RepositoryHandler) {
        val central = handler
            .withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository::class.java)
            .filter { it.url.host == "repo.maven.apache.org" }
        if (central.isNotEmpty()) {
            handler.removeAll(central)
            handler.mavenCentral()
        }
    }
    pushCentralLast(pluginManagement.repositories)
    pushCentralLast(dependencyResolutionManagement.repositories)
}