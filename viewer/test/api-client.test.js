import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiClient } from '../src/core/api-client.js';

test('ApiClient.cypher posts the single cypher request field', async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({ schemaVersion: '1', nodes: [], relationships: [], meta: {} }), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    });
  };
  try {
    const result = await new ApiClient('/api/v1').cypher('MATCH (n) RETURN n LIMIT 2');
    assert.equal(result.schemaVersion, '1');
    assert.equal(request.url, '/api/v1/graph/cypher');
    assert.deepEqual(JSON.parse(request.options.body), { cypher: 'MATCH (n) RETURN n LIMIT 2' });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('ApiClient.cypher rejects blank input before fetch', async () => {
  assert.throws(() => new ApiClient().cypher('  '), /cypher 不能为空/);
});
