import http.server, sys, functools
class H(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, ".html": "text/html; charset=utf-8", ".webp": "image/webp", ".mp3": "audio/mpeg"}
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", 8765), functools.partial(H, directory=sys.argv[1])).serve_forever()
