// Read-only Kubernetes discovery/Secret stub for testing Helm's lookup function.
import { createServer } from 'node:http';
import { parentPort, workerData } from 'node:worker_threads';

createServer((request, response) => {
  const path = new URL(request.url, 'http://localhost').pathname;
  let body;
  if (request.method !== 'GET') {
    response.writeHead(405).end();
    return;
  }
  if (path === '/version') body = { major: '1', minor: '30', gitVersion: 'v1.30.0' };
  else if (path === '/api') body = { kind: 'APIVersions', apiVersion: 'v1', versions: ['v1'] };
  else if (path === '/apis') body = { kind: 'APIGroupList', apiVersion: 'v1', groups:
    ['apps', 'batch', 'networking.k8s.io'].map(name => ({ name,
      versions: [{ groupVersion: `${name}/v1`, version: 'v1' }],
      preferredVersion: { groupVersion: `${name}/v1`, version: 'v1' },
    })) };
  else if (path === '/api/v1') body = { kind: 'APIResourceList', apiVersion: 'v1', groupVersion: 'v1',
    resources: [['secrets', 'Secret'], ['configmaps', 'ConfigMap'], ['services', 'Service']]
      .map(([name, kind]) => ({ name, namespaced: true, kind, verbs: ['get'] })) };
  else if (path.startsWith('/apis/')) {
    const groupVersion = path.slice('/apis/'.length);
    const resources = { 'apps/v1': [['deployments', 'Deployment'], ['statefulsets', 'StatefulSet']],
      'batch/v1': [['jobs', 'Job']], 'networking.k8s.io/v1': [['ingresses', 'Ingress']] }[groupVersion] ?? [];
    body = { kind: 'APIResourceList', apiVersion: 'v1', groupVersion,
      resources: resources.map(([name, kind]) => ({ name, namespaced: true, kind, verbs: ['get'] })) };
  }
  else if (path === `/api/v1/namespaces/participant/secrets/${workerData.metadata.name}`) body = workerData;
  else {
    response.writeHead(404, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ kind: 'Status', apiVersion: 'v1', status: 'Failure', reason: 'NotFound', code: 404 }));
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}).listen(0, '127.0.0.1', function () { parentPort.postMessage(this.address().port); });
