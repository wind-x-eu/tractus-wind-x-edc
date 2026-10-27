// Offline integration checks. Requires Node.js, Helm and kubectl; no npm packages.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';

const chart = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(join(tmpdir(), 'windx-chart-test-'));
const kubeconfig = join(temporary, 'kubeconfig');
writeFileSync(kubeconfig, 'apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []\n');
const run = (command, args, options = {}) => execFileSync(command, args, {
  encoding: 'utf8', maxBuffer: 10 * 1024 * 1024, ...options,
});

// kubectl's local reader converts YAML to JSON without a cluster or YAML library.
function readResources(yaml) {
  const input = join(temporary, 'rendered.yaml');
  writeFileSync(input, yaml);
  const stream = run('kubectl', [`--kubeconfig=${kubeconfig}`, 'label', '--local',
    '--overwrite', '-f', input, 'verification=offline', '-o', 'json']);
  const resources = [];
  let depth = 0, quoted = false, escaped = false, start = 0;
  for (let i = 0; i < stream.length; i++) {
    const character = stream[i];
    if (quoted) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') quoted = false;
    } else if (character === '"') quoted = true;
    else if (character === '{') { if (depth++ === 0) start = i; }
    else if (character === '}' && --depth === 0) resources.push(JSON.parse(stream.slice(start, i + 1)));
  }
  assert.equal(depth, 0);
  assert(resources.length > 0);
  return resources;
}

function render(settings = [], source = chart) {
  return readResources(run('helm', ['template', 'portable', source, '--namespace', 'participant',
    ...settings.flatMap(setting => ['--set', setting])]));
}

function checkWiring(resources, external = false) {
  const find = (kind, name) => resources.find(item => item.kind === kind && item.metadata.name === name);
  const names = new Set();
  for (const resource of resources) {
    assert(resource.metadata.name.length <= 63, resource.metadata.name);
    const identifier = `${resource.kind}/${resource.metadata.name}`;
    assert(!names.has(identifier), `duplicate ${identifier}`);
    names.add(identifier);
    assert(!resource.metadata.annotations?.['helm.sh/hook'], 'Startup must not depend on Helm hooks');
    if (resource.kind === 'ConfigMap') {
      for (const value of Object.values(resource.data)) assert.equal(typeof value, 'string');
    }
    if (['Deployment', 'StatefulSet', 'Job'].includes(resource.kind)) {
      const pod = resource.spec.template.spec;
      for (const volume of pod.volumes ?? []) {
        if (volume.configMap) {
          const config = find('ConfigMap', volume.configMap.name);
          assert(config, `missing ConfigMap ${volume.configMap.name}`);
          for (const item of volume.configMap.items ?? []) assert(item.key in config.data);
        }
        if (volume.secret && !external) {
          const secret = find('Secret', volume.secret.secretName);
          assert(secret, `missing Secret ${volume.secret.secretName}`);
          for (const item of volume.secret.items ?? []) assert(item.key in secret.data);
        }
      }
      const volumes = new Set((pod.volumes ?? []).map(volume => volume.name));
      for (const claim of resource.spec.volumeClaimTemplates ?? []) volumes.add(claim.metadata.name);
      for (const container of [...(pod.initContainers ?? []), ...pod.containers]) {
        for (const mount of container.volumeMounts ?? []) assert(volumes.has(mount.name), mount.name);
        for (const source of container.envFrom ?? []) {
          assert(find('ConfigMap', source.configMapRef.name), 'envFrom ConfigMap must exist');
        }
      }
    }
    if (resource.kind === 'Service') {
      const workload = resources.find(item => ['Deployment', 'StatefulSet'].includes(item.kind) &&
        Object.entries(resource.spec.selector).every(([key, value]) => item.spec.template.metadata.labels[key] === value));
      assert(workload, `no workload for ${identifier}`);
      const ports = workload.spec.template.spec.containers.flatMap(container => container.ports ?? []);
      for (const port of resource.spec.ports) {
        assert(ports.some(candidate => candidate.name === port.targetPort), `unresolved service port in ${identifier}`);
      }
    }
    if (resource.kind === 'Ingress') {
      const paths = resource.spec.rules[0].http.paths;
      assert.deepEqual(paths.map(path => path.path), ['/.well-known/did.json', '/api/credentials', '/api/v1/dsp', '/api/public']);
      for (const path of paths) {
        const service = find('Service', path.backend.service.name);
        assert(service, 'ingress backend must exist');
        assert(service.spec.ports.some(port => port.port === path.backend.service.port.number));
      }
    }
  }
}

function normalizeAddresses(value) {
  return value.replaceAll('://postgres:', '://portable-postgresql:')
    .replaceAll('://identityhub:', '://portable-identityhub:')
    .replaceAll('://controlplane:', '://portable-controlplane:')
    .replaceAll('://dataplane:', '://portable-dataplane:')
    .replaceAll('://vault:', '://portable-vault:');
}

try {
  run('helm', ['lint', chart, '--strict']);
  const bundled = render(['ingress.enabled=true']);
  checkWiring(bundled);
  assert.equal(bundled.filter(item => item.kind === 'Deployment').length, 3);
  assert.equal(bundled.filter(item => item.kind === 'StatefulSet').length, 2);
  const secret = bundled.find(item => item.kind === 'Secret');
  assert.equal(secret.metadata.annotations['helm.sh/resource-policy'], 'keep');
  assert.match(Buffer.from(secret.data['superuser-key'], 'base64').toString(), /^c3VwZXItdXNlcg==\.[A-Za-z0-9]{48}$/);
  assert.equal(new Set(Object.values(secret.data)).size, 4);
  console.log('PASS: bundled stack, secret generation, service/volume wiring and public routes');

  // Exercise Helm lookup against an isolated read-only API stub, never a real cluster.
  const api = new Worker(new URL('./lookup-server.mjs', import.meta.url), { workerData: secret });
  try {
    const port = await new Promise((resolve, reject) => { api.once('message', resolve); api.once('error', reject); });
    const serverConfig = join(temporary, 'lookup-kubeconfig');
    writeFileSync(serverConfig, JSON.stringify({ apiVersion: 'v1', kind: 'Config',
      clusters: [{ name: 'stub', cluster: { server: `http://127.0.0.1:${port}` } }],
      contexts: [{ name: 'stub', context: { cluster: 'stub', user: 'stub' } }],
      'current-context': 'stub', users: [{ name: 'stub', user: {} }],
    }));
    const upgrade = readResources(run('helm', ['template', 'portable', chart, '--namespace', 'participant',
      '--is-upgrade', '--dry-run=server', '--disable-openapi-validation', '--kubeconfig', serverConfig]));
    assert.deepEqual(upgrade.find(item => item.kind === 'Secret').data, secret.data);
    console.log('PASS: Helm server-side lookup preserves every credential on upgrade');
  } finally {
    await api.terminate();
  }

  const external = render(['vault.enabled=false', 'vault.url=https://external-vault',
    'postgresql.enabled=false', 'postgresql.host=external-postgres', 'secrets.create=false',
    'secrets.name=existing-credentials', 'ingress.enabled=true', 'connector.fullnameOverride=connector']);
  checkWiring(external, true);
  assert(!external.some(item => ['Secret', 'StatefulSet'].includes(item.kind)));
  assert(external.some(item => item.kind === 'Job' && item.metadata.name.includes('vault-bootstrap')));
  const noBootstrap = render(['vault.enabled=false', 'vault.url=https://external-vault', 'vault.bootstrap.enabled=false']);
  assert(!noBootstrap.some(item => item.metadata.name.includes('vault-bootstrap')));
  console.log('PASS: external PostgreSQL/Vault/Secret and optional Vault bootstrap');

  const ephemeral = render(['vault.persistence.enabled=false', 'postgresql.persistence.enabled=false',
    'imagePullSecrets[0].name=registry', 'connector.imagePullSecret=connector-registry',
    'identityhub.serviceName=wallet', 'postgresql.serviceName=database', 'vault.serviceName=key-store']);
  checkWiring(ephemeral);
  for (const resource of ephemeral.filter(item => item.spec?.template)) {
    assert.deepEqual(resource.spec.template.spec.imagePullSecrets.map(item => item.name), ['registry', 'connector-registry']);
    assert(!resource.spec.volumeClaimTemplates);
  }
  console.log('PASS: ephemeral storage, custom service names and registry credentials on every Pod');

  const isolated = join(temporary, 'isolated-chart');
  cpSync(chart, isolated, { recursive: true, filter: path => !['values-windx.yaml', 'secrets'].includes(basename(path)) });
  run('helm', ['package', isolated, '--destination', temporary]);
  const archive = join(temporary, readdirSync(temporary).find(file => file.endsWith('.tgz')));
  const packaged = render(['ingress.enabled=true'], archive);
  checkWiring(packaged);
  assert.deepEqual(packaged.map(item => [item.kind, item.metadata.name]), bundled.map(item => [item.kind, item.metadata.name]));
  const files = run('tar', ['-tf', archive]);
  assert(!files.includes('values-connector.yaml.gotmpl'));
  assert(!files.includes('/tests/'));
  assert(!files.includes('/values-windx.yaml'));
  assert(files.includes('/values-windx.example.yaml'));
  assert(files.includes('/LICENSE'));
  assert(files.includes('/files/vault-keeper.sh'));
  console.log('PASS: copy/package/install rendering without any sibling chart');

  const composeOption = process.argv.indexOf('--compose');
  if (composeOption !== -1) {
    const compose = resolve(process.argv[composeOption + 1]);
    const definition = JSON.parse(run('docker', ['compose', '--env-file', join(compose, '.env.example'),
      '-f', join(compose, 'docker-compose.yml'), 'config', '--format', 'json', '--no-path-resolution'], {
      env: { ...process.env, BPN: 'BPNLCHANGEME', PUBLIC_HOST: 'edc.partner.example' },
    }));
    for (const role of ['controlplane', 'dataplane']) {
      const actual = { ...bundled.find(item => item.kind === 'ConfigMap' && item.metadata.name === `portable-${role}`).data, ROLE: role };
      const expected = Object.fromEntries(Object.entries(definition.services[role].environment)
        .map(([key, value]) => [key, normalizeAddresses(value)]));
      assert.deepEqual(actual, expected, `${role}: Helm runtime settings must match Compose`);
      const deployment = bundled.find(item => item.kind === 'Deployment' && item.metadata.name === `portable-${role}`);
      assert.equal(deployment.spec.template.spec.containers[0].image, definition.services[role].image);
    }
    const properties = bundled.find(item => item.kind === 'ConfigMap' && item.metadata.name === 'portable-identityhub-files').data['identityhub.properties'];
    const actual = Object.fromEntries(properties.split('\n').filter(line => line.includes('=')).map(line => {
      const index = line.indexOf('=');
      return [line.slice(0, index), line.slice(index + 1)];
    }));
    const expected = Object.fromEntries(Object.entries(definition.services.identityhub.environment)
      // Compose config preserves $$; container creation turns it into a literal $.
      .filter(([key]) => key !== 'ROLE').map(([key, value]) => [key.toLowerCase().replaceAll('_', '.'), normalizeAddresses(value).split('$$').join('$')]));
    assert.deepEqual(actual, expected, 'IdentityHub runtime settings must match Compose');
    console.log('PASS: every Control Plane, Data Plane and IdentityHub runtime setting matches Compose');
  }
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
