package com.callagent.gateway.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Lightweight LAN diagnostic page. */
object WebDebugServer {
    const val PORT = 8787
    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null
    @Volatile private var running = false

    fun start(context: Context) {
        if (running) return
        synchronized(this) {
            if (running) return
            try {
                serverSocket = ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"))
                workers = Executors.newCachedThreadPool()
                running = true
                val appContext = context.applicationContext
                thread(name = "web-debug-accept", isDaemon = true) {
                    while (running) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            workers?.execute { handle(appContext, socket) }
                        } catch (_: Exception) {
                            if (running) GatewayService.logWeb("Web debug accept failed")
                        }
                    }
                }
                GatewayService.logWeb("Web debug page listening on port $PORT")
            } catch (e: Exception) {
                running = false
                GatewayService.logWeb("Web debug page unavailable: ${e.message}")
            }
        }
    }

    fun openInBrowser(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://${GatewayService.localIp()}:$PORT/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun handle(context: Context, socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val firstLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotEmpty() == true) { }
                val parts = firstLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
                val response = if (method != "GET") {
                    response(405, "text/plain; charset=utf-8", "GET only")
                } else when (path) {
                    "/" -> response(200, "text/html; charset=utf-8", PAGE)
                    "/api/status" -> response(200, "application/json; charset=utf-8", statusJson())
                    "/api/logs" -> response(200, "application/json; charset=utf-8", logsJson())
                    "/api/action/reload" -> {
                        GatewayService.reload(context)
                        response(200, "application/json; charset=utf-8", "{\"ok\":true}")
                    }
                    "/api/action/start" -> {
                        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                        GatewayService.start(context, prefs.getString("server", "") ?: "", prefs.getInt("port", 5060),
                            prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
                        response(200, "application/json; charset=utf-8", "{\"ok\":true}")
                    }
                    "/api/action/stop" -> {
                        GatewayService.stop(context)
                        response(200, "application/json; charset=utf-8", "{\"ok\":true}")
                    }
                    else -> response(404, "text/plain; charset=utf-8", "Not found")
                }
                val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
                writer.write(response)
                writer.flush()
            } catch (e: Exception) {
                GatewayService.logWeb("Web debug request failed: ${e.message}")
            }
        }
    }

    private fun statusJson(): String = "{" +
        "\"state\":${json(GatewayService.webState)}," +
        "\"info\":${json(GatewayService.webInfo)}," +
        "\"server\":${json(GatewayService.webServer)}," +
        "\"port\":${GatewayService.webPort}," +
        "\"user\":${json(GatewayService.webUser)}," +
        "\"local_ip\":${json(GatewayService.localIp())}," +
        "\"web_port\":$PORT," +
        "\"online_since\":${GatewayService.webOnlineSince}}"

    private fun logsJson(): String = "{\"logs\":[${GatewayService.webLogSnapshot().joinToString(",") { json(it) }}]}"

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { ch -> when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        } }
        append('"')
    }

    private fun response(code: Int, type: String, body: String): String =
        "HTTP/1.1 $code ${if (code == 200) "OK" else if (code == 404) "Not Found" else "Method Not Allowed"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n$body"

    private val PAGE = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>callagent debug</title><style>
:root{color-scheme:dark}body{margin:0;background:#111827;color:#e5e7eb;font:14px system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:20px}h1{font-size:24px;margin:0 0 4px}p{color:#9ca3af;margin:0 0 18px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin-bottom:14px}.card{background:#1f2937;border:1px solid #374151;border-radius:8px;padding:13px}.label{color:#9ca3af;font-size:12px;text-transform:uppercase}.value{font-size:16px;margin-top:5px;word-break:break-all}.ok{color:#4ade80}.warn{color:#facc15}.bad{color:#f87171}.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}button{background:#2563eb;color:#fff;border:0;border-radius:6px;padding:9px 13px;font-weight:600}button.secondary{background:#374151}pre{height:52vh;overflow:auto;background:#030712;border:1px solid #374151;border-radius:8px;padding:12px;white-space:pre-wrap;word-break:break-word;font:12px ui-monospace,monospace;margin:0}</style></head>
<body><main><h1>callagent debug</h1><p>LAN diagnostics · updates every second · password is never displayed</p>
<section class="grid"><div class="card"><div class="label">Gateway</div><div id="state" class="value">Loading...</div></div><div class="card"><div class="label">SIP server</div><div id="server" class="value">-</div></div><div class="card"><div class="label">Account</div><div id="user" class="value">-</div></div><div class="card"><div class="label">Local address</div><div id="ip" class="value">-</div></div></section>
<div class="toolbar"><button onclick="action('start')">Start</button><button onclick="action('stop')" class="secondary">Stop</button><button onclick="action('reload')" class="secondary">Reload stats</button><button onclick="clearLog()" class="secondary">Clear view</button><label class="card" style="padding:8px 10px"><input id="follow" type="checkbox" checked> Follow log</label></div>
<pre id="logs">Loading logs...</pre></main><script>
const $=id=>document.getElementById(id), esc=s=>String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));let last='';
async function refresh(){try{let s=await fetch('/api/status',{cache:'no-store'}).then(r=>r.json());let c=s.state==='ERROR'?'bad':(s.state==='STOPPED'?'warn':'ok');$('state').innerHTML='<span class="'+c+'">'+esc(s.state)+'</span> · '+esc(s.info);$('server').textContent=s.server+':'+s.port;$('user').textContent=s.user||'-';$('ip').textContent=s.local_ip+':'+s.web_port;let l=await fetch('/api/logs',{cache:'no-store'}).then(r=>r.json());let text=l.logs.join('\n');if(text!==last){let at=$('logs').scrollTop;let follow=$('follow').checked;last=text;$('logs').textContent=text;if(follow)$('logs').scrollTop=$('logs').scrollHeight;else $('logs').scrollTop=at}}catch(e){$('state').textContent='Web service error: '+e}}
function clearLog(){$('logs').textContent='';last=''}async function action(a){await fetch('/api/action/'+a);setTimeout(refresh,300)}refresh();setInterval(refresh,1000);
</script></body></html>
""".trimIndent()
}
