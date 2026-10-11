package me.rerere.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    module: suspend Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, port = port, host = host, module = {
        install(Compression)
        // P0-52：原来 anyHost() + anyMethod() 让任意网页都能在用户浏览器里跨源驱动本机
        // Web 面（配合 GET 面把 token 放 query 的写法，EventSource 可直接读走数据）。
        // Web UI 由本服务自己同源下发（staticResources("/")），web-ui 开发态走 vite
        // proxy（web-ui/vite.config.ts:8-13，服务器对服务器，不触发 CORS），两者都不需要
        // 任何跨源放行 —— 因此这里**不放行任何 Origin**：allowHost 名单为空 = 全部预检/
        // 跨源请求被 CORS 插件拒（403 "Request origin is not allowed"），同源不受影响。
        // 待人工判断：若确有「第三方前端/其他端口页面要直连本 API」的场景，需要人工确认
        // 放哪些源（例如 allowHost("localhost", subDomains = listOf(...)) 或具体端口），
        // 不要退回 anyHost()。
        install(CORS) {
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowNonSimpleContentTypes = true
        }
        install(SSE)
        install(DefaultHeaders)
        routing {
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
                singlePageApplication()
            }
        }
        module()
    })
}
