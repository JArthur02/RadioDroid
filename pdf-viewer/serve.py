#!/usr/bin/env python3
"""Serve the browser-compatible PDF viewer on port 8080."""
import http.server
import os
import socketserver

PORT = 8080
DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(DIR)

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Allow embedding the PDF in iframes from any origin
        self.send_header("Access-Control-Allow-Origin", "*")
        if self.path.endswith(".pdf"):
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Disposition", "inline")
        super().end_headers()

with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"Serving PDF viewer at http://localhost:{PORT}/")
    httpd.serve_forever()
