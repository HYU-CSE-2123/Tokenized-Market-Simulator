import { defineConfig } from 'vite';

const backendTarget = process.env.BACKEND_URL || 'http://127.0.0.1:8080';

export default defineConfig({
  // sockjs-client의 브라우저 번들이 Node식 global을 참조하므로 표준 전역 객체로 매핑한다.
  define: {
    global: 'globalThis',
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': backendTarget,
      '/ws-sockjs': {
        target: backendTarget,
        ws: true,
      },
      '/ws': {
        target: backendTarget.replace(/^http/, 'ws'),
        ws: true,
      },
    },
  },
});
