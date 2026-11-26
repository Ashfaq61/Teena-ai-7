package com.example.ai

import android.content.Context
import android.net.Uri
import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class WebsiteForgeProject(
    val id: String,
    val title: String,
    val prompt: String,
    val htmlSource: String,
    val templateKind: String,
    val hasWebGlCanvas: Boolean,
    val createdAtMs: Long
)

data class WebsiteStarterTemplate(
    val id: String,
    val title: String,
    val kind: String,
    val htmlSource: String
)

class WebsiteForgeEngine(private val context: Context) {

    companion object {
        private val HTML_FENCE_REGEX = Regex(
            """```(?:html|htm)\s*\n([\s\S]*?)```""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val CSS_FENCE_REGEX = Regex(
            """```css\s*\n([\s\S]*?)```""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val JS_FENCE_REGEX = Regex(
            """```(?:js|javascript)\s*\n([\s\S]*?)```""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val RAW_HTML_DOC_REGEX = Regex(
            """(<!DOCTYPE\s+html[\s\S]*?</html>|<html[\s\S]*?</html>)""",
            setOf(RegexOption.IGNORE_CASE)
        )

        fun hasWebGlOrThreeJs(html: String): Boolean {
            val lower = html.lowercase()
            return lower.contains("webgl") ||
                lower.contains("three.") ||
                lower.contains("gl_position") ||
                (lower.contains("<canvas") && lower.contains("requestanimationframe"))
        }

        fun extractHtmlDocumentFromText(
            rawText: String,
            titleFallback: String = "Teena Website Forge"
        ): String? {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null

            val htmlFence = HTML_FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            val candidateHtml = when {
                !htmlFence.isNullOrBlank() -> htmlFence
                else -> RAW_HTML_DOC_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            }

            if (!candidateHtml.isNullOrBlank()) {
                return if (candidateHtml.contains("<html", ignoreCase = true)) {
                    ensureViewportMeta(candidateHtml)
                } else {
                    wrapFragmentAsDocument(
                        title = titleFallback,
                        bodyHtml = candidateHtml,
                        css = CSS_FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1).orEmpty(),
                        js = JS_FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
                    )
                }
            }

            // Assemble if separate CSS or JS + body HTML tags were provided without a full document
            val css = CSS_FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val js = JS_FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (css.isNotBlank() && js.isNotBlank()) {
                return wrapFragmentAsDocument(
                    title = titleFallback,
                    bodyHtml = "<main class=\"forge-root\"><canvas id=\"scene\"></canvas><section id=\"app\"></section></main>",
                    css = css,
                    js = js
                )
            }
            return null
        }

        fun ensureViewportMeta(html: String): String {
            if (html.contains("name=\"viewport\"", ignoreCase = true) ||
                html.contains("name='viewport'", ignoreCase = true)
            ) {
                return html
            }
            val headTag = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)
            val match = headTag.find(html)
            return if (match != null) {
                html.replaceRange(
                    match.range,
                    "${match.value}\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                )
            } else {
                html
            }
        }

        fun sanitizePreviewHtml(html: String): String {
            var clean = ensureViewportMeta(html)
            // Strip local device scheme references inside generated previews for WebView safety
            clean = clean.replace(Regex("""(file|content)://[^\s"'<>]+""", RegexOption.IGNORE_CASE), "#blocked-scheme")
            return clean
        }

        private fun wrapFragmentAsDocument(
            title: String,
            bodyHtml: String,
            css: String,
            js: String
        ): String {
            val safeTitle = SecurityAuditor.redactSecrets(title).replace("<", "&lt;").replace(">", "&gt;")
            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>$safeTitle</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: system-ui, -apple-system, sans-serif; background: #070B14; color: #E2F1FF; min-height: 100vh; }
                    $css
                  </style>
                </head>
                <body>
                  $bodyHtml
                  <script>
                    $js
                  </script>
                </body>
                </html>
            """.trimIndent()
        }

        fun starterTemplates(): List<WebsiteStarterTemplate> = listOf(
            WebsiteStarterTemplate(
                id = "webgl_neural_prism",
                title = "3D WebGL Neural Prism",
                kind = "WEBGL_3D",
                htmlSource = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Teena WebGL Neural Prism</title>
                      <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body { background: radial-gradient(circle at top, #0D1B2A, #04070D 75%); color: #E8F7FF; font-family: system-ui, sans-serif; overflow: hidden; height: 100vh; display: flex; flex-direction: column; }
                        canvas { width: 100%; flex: 1; display: block; touch-action: none; }
                        .hud { position: fixed; top: 14px; left: 14px; right: 14px; padding: 12px 16px; border-radius: 16px; background: rgba(8, 16, 30, 0.72); border: 1px solid rgba(56, 224, 255, 0.35); backdrop-filter: blur(8px); }
                        h1 { font-size: 16px; color: #38E0FF; letter-spacing: 0.5px; }
                        p { font-size: 12px; color: #A6C8DC; margin-top: 4px; }
                      </style>
                    </head>
                    <body>
                      <div class="hud">
                        <h1>TEENA AI • Live WebGL Forge</h1>
                        <p>Interactive hardware-accelerated WebGL shader & rotating prism. Drag screen to orbit.</p>
                      </div>
                      <canvas id="glCanvas"></canvas>
                      <script>
                        const canvas = document.getElementById('glCanvas');
                        const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                        if (!gl) {
                          document.querySelector('.hud p').textContent = 'WebGL fallback active (2D Canvas mode).';
                          const ctx = canvas.getContext('2d');
                          let t = 0;
                          function draw2d() {
                            canvas.width = window.innerWidth; canvas.height = window.innerHeight;
                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                            ctx.strokeStyle = '#38E0FF'; ctx.lineWidth = 2;
                            ctx.beginPath();
                            for (let i = 0; i < 6; i++) {
                              const a = t + i * Math.PI / 3;
                              const x = canvas.width / 2 + Math.cos(a) * 90;
                              const y = canvas.height / 2 + Math.sin(a) * 90;
                              if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
                            }
                            ctx.closePath(); ctx.stroke();
                            t += 0.02; requestAnimationFrame(draw2d);
                          }
                          draw2d();
                        } else {
                          const vsSrc = 'attribute vec3 pos; attribute vec3 col; varying vec3 vCol; uniform float angle; void main(){ float c=cos(angle), s=sin(angle); mat2 r=mat2(c,-s,s,c); vec3 p=pos; p.xz=r*p.xz; p.xy=mat2(cos(angle*0.6),-sin(angle*0.6),sin(angle*0.6),cos(angle*0.6))*p.xy; gl_Position=vec4(p.x*0.75, p.y*0.75, p.z*0.35, 1.0); vCol=col; }';
                          const fsSrc = 'precision mediump float; varying vec3 vCol; void main(){ gl_FragColor=vec4(vCol, 1.0); }';
                          function compile(type, src){ const s=gl.createShader(type); gl.shaderSource(s,src); gl.compileShader(s); return s; }
                          const prog = gl.createProgram();
                          gl.attachShader(prog, compile(gl.VERTEX_SHADER, vsSrc));
                          gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fsSrc));
                          gl.linkProgram(prog); gl.useProgram(prog);
                          const verts = new Float32Array([
                             0.0,  0.75,  0.0, 0.22, 0.88, 1.0,
                            -0.65,-0.55,  0.55, 0.54, 0.36, 0.96,
                             0.65,-0.55,  0.55, 0.20, 0.95, 0.72,
                             0.0,  0.75,  0.0, 0.22, 0.88, 1.0,
                             0.65,-0.55,  0.55, 0.20, 0.95, 0.72,
                             0.0, -0.55, -0.75, 0.96, 0.45, 0.78,
                             0.0,  0.75,  0.0, 0.22, 0.88, 1.0,
                             0.0, -0.55, -0.75, 0.96, 0.45, 0.78,
                            -0.65,-0.55,  0.55, 0.54, 0.36, 0.96
                          ]);
                          const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);
                          const posLoc = gl.getAttribLocation(prog, 'pos'); gl.enableVertexAttribArray(posLoc); gl.vertexAttribPointer(posLoc, 3, gl.FLOAT, false, 24, 0);
                          const colLoc = gl.getAttribLocation(prog, 'col'); gl.enableVertexAttribArray(colLoc); gl.vertexAttribPointer(colLoc, 3, gl.FLOAT, false, 24, 12);
                          const angLoc = gl.getUniformLocation(prog, 'angle');
                          let angle = 0; let spin = 0.018;
                          canvas.addEventListener('pointermove', (e) => { if (e.buttons || e.pointerType === 'touch') angle += e.movementX * 0.015; });
                          function render() {
                            const w = canvas.clientWidth, h = canvas.clientHeight;
                            if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; gl.viewport(0, 0, w, h); }
                            gl.enable(gl.DEPTH_TEST); gl.clearColor(0.02, 0.04, 0.08, 1.0); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
                            angle += spin; gl.uniform1f(angLoc, angle); gl.drawArrays(gl.TRIANGLES, 0, 9);
                            requestAnimationFrame(render);
                          }
                          render();
                        }
                      </script>
                    </body>
                    </html>
                """.trimIndent()
            ),
            WebsiteStarterTemplate(
                id = "neon_portfolio",
                title = "Teena Dark Portfolio",
                kind = "HTML5_PORTFOLIO",
                htmlSource = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Neural Developer Portfolio</title>
                      <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body { font-family: system-ui, sans-serif; background: #060913; color: #F0F6FC; padding: 24px 18px; line-height: 1.5; }
                        .hero { background: linear-gradient(135deg, rgba(56,224,255,0.18), rgba(139,92,246,0.22)); border: 1px solid rgba(56,224,255,0.35); border-radius: 20px; padding: 22px; margin-bottom: 16px; }
                        .badge { display: inline-block; font-size: 11px; color: #34D399; border: 1px solid #34D399; padding: 3px 10px; border-radius: 99px; margin-bottom: 10px; }
                        h1 { font-size: 26px; margin-bottom: 6px; color: #38E0FF; }
                        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
                        .card { background: #0E1525; border: 1px solid rgba(255,255,255,0.08); border-radius: 14px; padding: 14px; }
                        .card h3 { color: #A78BFA; font-size: 15px; margin-bottom: 4px; }
                        button { margin-top: 14px; background: #38E0FF; color: #050810; font-weight: 700; border: 0; border-radius: 10px; padding: 10px 16px; cursor: pointer; }
                      </style>
                    </head>
                    <body>
                      <section class="hero">
                        <span class="badge">AVAILABLE FOR COLLABORATION</span>
                        <h1>Interactive Showcase</h1>
                        <p>Forged cleanly in Teena Website Forge with responsive grid, zero external bloat, and interactive status actions.</p>
                        <button onclick="document.getElementById('ping').textContent='Interactive ping verified at ' + new Date().toLocaleTimeString()">Test Interactive JS</button>
                        <p id="ping" style="margin-top:8px;color:#34D399;font-size:12px;"></p>
                      </section>
                      <section class="grid">
                        <div class="card"><h3>Android + Compose</h3><p style="font-size:13px;color:#9FB3C8;">Material 3 adaptive apps, CameraX & live voice AI.</p></div>
                        <div class="card"><h3>WebGL Shaders</h3><p style="font-size:13px;color:#9FB3C8;">Real-time interactive 3D visuals right inside mobile WebView.</p></div>
                      </section>
                    </body>
                    </html>
                """.trimIndent()
            )
        )
    }

    private val forgeDir = File(context.filesDir, "teena_website_forge").apply { mkdirs() }
    private val catalogFile = File(forgeDir, "catalog.json")
    private val lock = Any()

    private val _projects = MutableStateFlow<List<WebsiteForgeProject>>(emptyList())
    val projects: StateFlow<List<WebsiteForgeProject>> = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    init {
        val loaded = loadCatalog()
        if (loaded.isEmpty()) {
            val starter = starterTemplates().first()
            val seeded = saveProject(
                title = starter.title,
                prompt = "Built-in WebGL starter showcase",
                htmlSource = starter.htmlSource,
                templateKind = starter.kind
            )
            _activeProjectId.value = seeded.id
        } else {
            _projects.value = loaded
            _activeProjectId.value = loaded.firstOrNull()?.id
        }
    }

    fun saveProject(
        title: String,
        prompt: String,
        htmlSource: String,
        templateKind: String = "AI_FORGED"
    ): WebsiteForgeProject = synchronized(lock) {
        val safeHtml = sanitizePreviewHtml(htmlSource)
        val cleanTitle = SecurityAuditor.redactSecrets(title.trim()).ifBlank { "Forged Website" }.take(60)
        val id = "site_${UUID.randomUUID().toString().take(8)}"
        val file = File(forgeDir, "$id.html")
        file.writeText(safeHtml)

        val project = WebsiteForgeProject(
            id = id,
            title = cleanTitle,
            prompt = SecurityAuditor.redactSecrets(prompt.trim()).take(300),
            htmlSource = safeHtml,
            templateKind = templateKind,
            hasWebGlCanvas = hasWebGlOrThreeJs(safeHtml),
            createdAtMs = System.currentTimeMillis()
        )
        val updated = listOf(project) + _projects.value.filterNot { it.id == project.id }.take(19)
        persistCatalog(updated)
        _projects.value = updated
        _activeProjectId.value = project.id
        project
    }

    fun selectProject(projectId: String) {
        if (_projects.value.any { it.id == projectId }) {
            _activeProjectId.value = projectId
        }
    }

    fun updateProjectHtml(projectId: String, updatedHtml: String): WebsiteForgeProject? = synchronized(lock) {
        val safeHtml = sanitizePreviewHtml(updatedHtml)
        val next = _projects.value.map { p ->
            if (p.id == projectId) {
                runCatching { File(forgeDir, "${p.id}.html").writeText(safeHtml) }
                p.copy(htmlSource = safeHtml, hasWebGlCanvas = hasWebGlOrThreeJs(safeHtml))
            } else p
        }
        persistCatalog(next)
        _projects.value = next
        next.firstOrNull { it.id == projectId }
    }

    fun deleteProject(projectId: String) = synchronized(lock) {
        runCatching { File(forgeDir, "$projectId.html").delete() }
        val next = _projects.value.filterNot { it.id == projectId }
        persistCatalog(next)
        _projects.value = next
        if (_activeProjectId.value == projectId) {
            _activeProjectId.value = next.firstOrNull()?.id
        }
    }

    suspend fun exportHtmlToUri(project: WebsiteForgeProject, destUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                out.write(project.htmlSource.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Unable to open HTML export destination.")
            "Exported '${project.title}.html' cleanly."
        }
    }

    private fun loadCatalog(): List<WebsiteForgeProject> = synchronized(lock) {
        if (!catalogFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(catalogFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                val file = File(forgeDir, "$id.html")
                if (id.isBlank() || !file.exists()) return@mapNotNull null
                val html = file.readText()
                WebsiteForgeProject(
                    id = id,
                    title = obj.optString("title", "Forged Website"),
                    prompt = obj.optString("prompt"),
                    htmlSource = html,
                    templateKind = obj.optString("templateKind", "AI_FORGED"),
                    hasWebGlCanvas = obj.optBoolean("hasWebGlCanvas", hasWebGlOrThreeJs(html)),
                    createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun persistCatalog(list: List<WebsiteForgeProject>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    put("prompt", p.prompt)
                    put("templateKind", p.templateKind)
                    put("hasWebGlCanvas", p.hasWebGlCanvas)
                    put("createdAtMs", p.createdAtMs)
                })
            }
            catalogFile.writeText(arr.toString(2))
        }
    }
}
