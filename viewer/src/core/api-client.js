// 服务端 basePath/maxDepth 在 config/api.yaml；当前 Viewer 默认仍使用 /api/v1 和 depth 1..8。
// 修改服务端配置不会自动同步这里；需要同步 Viewer 时一并修改并运行 npm test/build。
export class ApiClient {
  constructor(basePath = '/api/v1') {
    this.basePath = basePath.replace(/\/$/, '');
  }

  entity(uid) { return this.#get(`/entities/${encodeURIComponent(this.#text(uid, 'uid'))}`); }
  oneHop(uid) { return this.#post('/graph/one-hop', { uid: this.#text(uid, 'uid') }); }
  kHop(uid, depth) {
    return this.#post('/graph/k-hop', { uid: this.#text(uid, 'uid'), depth: this.#depth(depth) });
  }
  path(fromUid, toUid, maxDepth) {
    return this.#post('/graph/path', {
      fromUid: this.#text(fromUid, 'fromUid'),
      toUid: this.#text(toUid, 'toUid'),
      maxDepth: this.#depth(maxDepth)
    });
  }

  async #get(path) {
    return this.#read(await fetch(`${this.basePath}${path}`, { headers: { Accept: 'application/json' } }));
  }

  async #post(path, body) {
    return this.#read(await fetch(`${this.basePath}${path}`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }));
  }

  async #read(response) {
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    return body;
  }

  #text(value, name) {
    const text = String(value ?? '').trim();
    if (!text) throw new Error(`${name} 不能为空`);
    return text;
  }

  #depth(value) {
    const depth = Number(value);
    if (!Number.isInteger(depth) || depth < 1 || depth > 8) throw new Error('depth 必须在 1..8');
    return depth;
  }
}
