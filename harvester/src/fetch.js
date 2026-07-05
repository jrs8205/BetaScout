// Thin HTTP adapter. Reads public pages with a browser User-Agent; APKMirror
// sits behind Cloudflare and may return 403 intermittently, so callers must
// tolerate non-200 responses.

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

export async function fetchText(url) {
  try {
    const response = await fetch(url, {
      headers: {
        'User-Agent': USER_AGENT,
        Accept: 'text/html,application/xhtml+xml,application/xml,*/*',
      },
    });
    return { status: response.status, text: response.ok ? await response.text() : '' };
  } catch (error) {
    return { status: 0, text: '', error: String(error) };
  }
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
