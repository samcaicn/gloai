/**
 * The little web site the check browses: a search box with a results page, a page with an irreversible-looking
 * button, a long page to scroll, and a link that answers with a download. Loopback only, gone when the check ends.
 */
import { createServer } from 'node:http';

const page = (title, body) =>
  `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>
<style>body{font:16px system-ui;margin:24px}input,button{font:inherit;padding:6px 10px}</style></head><body>${body}</body></html>`;

const escape = (text) => text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

export function startSite() {
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const html = (title, body) => {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(page(title, body));
    };
    switch (url.pathname) {
      case '/':
        return html(
          'Library',
          `<h1>Library</h1>
<form action="/results" method="get">
  <label for="q">Search the catalogue</label>
  <input id="q" name="q" type="text" placeholder="Title or author">
  <button type="submit">Search</button>
</form>
<p id="size"></p>
<script>document.getElementById('size').textContent = 'viewport ' + innerWidth + ' x ' + innerHeight;</script>`
        );
      case '/results':
        return html(
          'Results',
          `<h1>Results for ${escape(url.searchParams.get('q') ?? '')}</h1>
<ul><li><a href="/book/1">WeakMap, a field guide</a></li><li><a href="/book/2">Maps and sets</a></li></ul>`
        );
      case '/danger':
        return html(
          'Account',
          `<h1>Account</h1><p>Signed in as check@example.test</p>
<form action="/deleted" method="get"><button type="submit">Delete account</button></form>`
        );
      case '/deleted':
        return html('Deleted', '<h1>Your account was deleted</h1>');
      case '/long':
        return html(
          'Long page',
          `<h1>Long page</h1>${Array.from({ length: 80 }, (_unused, index) => `<p>Paragraph ${index + 1}</p>`).join('')}`
        );
      case '/download':
        response.writeHead(200, {
          'content-type': 'application/octet-stream',
          'content-disposition': 'attachment; filename="report.bin"',
        });
        return response.end('not for the disk');
      default:
        response.writeHead(404, { 'content-type': 'text/plain' });
        return response.end('not found');
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ origin: `http://127.0.0.1:${port}`, close: () => server.close() });
    });
  });
}
