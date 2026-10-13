package me.rerere.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
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
            // 静态资源与单页应用兜底都由这一条提供：default("index.html") 让任何未命中
            // 请求回落到前端入口（classpath 资源 static/index.html），前端深链照常工作。
            // ⚠ 不要在这里调用 Route.singlePageApplication()：它是 Route 的扩展而不是本配置块
            // 的成员，会在同一父路由上额外注册一条 staticFiles(File(""))；File("") 解析为进程
            // 工作目录（应用进程 = 文件系统根），等于把整个文件系统当成静态资源目录，且该路由
            // 先于本条注册、也在鉴权模块之前 —— 未授权请求可按绝对路径读到设备上的私有数据文件。
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
            }
        }
        module()
    })
}
