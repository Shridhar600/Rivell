/// <reference types="vitest/config" />
import { readFileSync, readdirSync, statSync, mkdirSync, copyFileSync } from 'node:fs'
import { dirname, extname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

const FRONTEND = fileURLToPath(new URL('.', import.meta.url))
const DEMO_CAPTURE = resolve(FRONTEND, '../demo/capture')
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

function contentType(path: string): string {
  switch (extname(path).toLowerCase()) {
    case '.json': return 'application/json; charset=utf-8'
    case '.docx': return DOCX
    case '.xlsx': return XLSX
    default: return 'application/octet-stream'
  }
}

function copyTree(from: string, to: string): void {
  mkdirSync(to, { recursive: true })
  for (const entry of readdirSync(from)) {
    const source = resolve(from, entry)
    const target = resolve(to, entry)
    if (statSync(source).isDirectory()) copyTree(source, target)
    else {
      mkdirSync(dirname(target), { recursive: true })
      copyFileSync(source, target)
    }
  }
}

/** Serves and ships the captured demo data beside the demo build, and nowhere else. */
function demoCapturePlugin(base: string): Plugin {
  const prefix = `${base}demo-data/`
  return {
    name: 'rivell-demo-capture',
    // The demo page stays out of search indexes even when it is served through a rewrite, and
    // shows a line of text while the capture loads.
    transformIndexHtml(html) {
      return html
        .replace('</head>', '    <meta name="robots" content="noindex">\n  </head>')
        .replace('<div id="root"></div>', '<div id="root"><p style="font: 15px/1.5 system-ui, sans-serif; color: #5b6360; padding: 32px;">Loading the demo</p></div>')
    },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const url = request.url ?? ''
        const pathname = url.split('?')[0]
        if (!pathname.startsWith(prefix)) return next()
        let relative = ''
        try {
          relative = decodeURIComponent(pathname.slice(prefix.length))
        } catch {
          response.statusCode = 400
          response.end('Bad request')
          return
        }
        const file = resolve(DEMO_CAPTURE, relative)
        if (!file.startsWith(resolve(DEMO_CAPTURE) + '/') || relative.includes('\0')) {
          response.statusCode = 404
          response.end('Not found')
          return
        }
        try {
          const body = readFileSync(file)
          response.statusCode = 200
          response.setHeader('Content-Type', contentType(file))
          response.setHeader('Cache-Control', 'no-store')
          response.end(body)
        } catch {
          response.statusCode = 404
          response.end('Not found')
        }
      })
    },
    writeBundle(options) {
      const outDir = resolve(process.cwd(), options.dir ?? 'dist')
      try {
        copyTree(DEMO_CAPTURE, resolve(outDir, 'demo-data'))
      } catch (error) {
        // A demo build with no capture is still useful for layout work; a failed copy is not.
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
      }
    },
  }
}

export default defineConfig(({ mode }) => {
  const demo = mode === 'demo'
  const base = demo ? '/demo/' : '/'
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'
  const apiProxy = {
    '/api': { target: apiProxyTarget, changeOrigin: true },
  }

  return {
    base,
    plugins: [react(), ...(demo ? [demoCapturePlugin(base)] : [])],
    resolve: {
      alias: {
        '@demo-hooks': resolve(FRONTEND, demo ? 'src/demo/install.ts' : 'src/demo/off.ts'),
      },
    },
    server: { proxy: apiProxy },
    preview: { proxy: apiProxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      globals: false,
      passWithNoTests: true,
    },
  }
})
