const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {document: {addEventListener() {}}, window: {addEventListener() {}},
  esc: value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))};
vm.createContext(context);
vm.runInContext(fs.readFileSync('src/main/resources/static/assets.js', 'utf8'), context);
const groups = ports => JSON.parse(JSON.stringify(context.portGroups(ports)));

test('groups IPv4 and IPv6 by protocol and port, preserving all endpoints', () => {
  const result = groups(['TCP 0.0.0.0:443', 'TCP [::]:443', 'TCP 127.0.0.1:443', 'UDP 0.0.0.0:443', 'TCP *:80']);
  assert.deepEqual(result.map(g => g.label), ['TCP 80', 'TCP 443', 'UDP 443']);
  assert.equal(result[1].values.length, 3);
});
test('groups Docker mappings without mixing host sockets or internal declarations', () => {
  const result = groups(['Docker 80/tcp [{"HostIp":"0.0.0.0","HostPort":"8080"},{"HostIp":"::","HostPort":"8080"}]',
    'Docker 0.0.0.0:8080 -> 80/tcp', 'Docker 80/tcp null', 'TCP 0.0.0.0:8080']);
  assert.equal(result.length, 3);
  const mapping = result.find(g => g.label === 'Docker TCP 8080 → 80');
  assert.equal(mapping.values.length, 3);
  assert.ok(mapping.values.some(v => v.includes('[::]:8080')));
  assert.ok(result.some(g => g.label === 'Docker TCP 80 · 내부'));
});
test('keeps unrecognized observations verbatim and distinguishes unknown Docker declarations', () => {
  const raw = 'Docker 80/tcp [{"unrecognized":"<script>"}]';
  const result = groups([raw, 'Docker 53/udp', 'Docker 443/tcp []']);
  assert.ok(result.some(g => g.values.includes(raw)));
  assert.ok(result.some(g => g.label === 'Docker UDP 53 · 선언'));
  assert.ok(result.some(g => g.label === 'Docker TCP 443 · 내부'));
});
test('bounds the visible port list and escapes unknown evidence and identifiers', () => {
  const html = context.observedPorts({id:'"><img>', observed:{bindings:Array.from({length:8}, (_,i) => `TCP 0.0.0.0:${8000+i}`)}});
  assert.equal((html.match(/class="port-chip"/g) || []).length, 4);
  assert.ok(html.includes('+4'));
  assert.ok(!html.includes('8007') && !html.includes('<img>') && !html.includes('0.0.0.0'));
});
test('shows three separate PID badges and the remaining count without commas', () => {
  const html = context.observedPids({id:'one', observed:{pids:[12345,23456,34567,45678,56789]}});
  assert.equal((html.match(/class="pid-chip"/g) || []).length, 3);
  assert.ok(['12345','23456','34567'].every(pid => html.includes('>'+pid+'</span>')));
  assert.ok(!html.includes(','));
  assert.ok(html.includes('+2') && !html.includes('45678') && !html.includes('56789'));
  assert.equal(context.observedPids({id:'manual'}), '');
});

const service = (name, executable = '', extra = {}) => ({source:'AUTO', observed:{kind:'PROCESS', name, executable, pids:[12345], runUser:'root', bindings:['TCP 0.0.0.0:22']}, ...extra});
test('recognizes observed OS services and named kernel threads without changing their data', () => {
  for (const item of [service('sshd','/usr/sbin/sshd'),service('systemd-resolve','/usr/lib/systemd/systemd-resolved'),
    service('chronyd','/sbin/chronyd (deleted)'),service('rpcbind'),service('[nfsd]'),service('kworker/u8:3-events'),service('kernel'),
    {...service('sshd','/usr/sbin/sshd'),name:'관리자가 지정한 이름'}]) {
    const before = JSON.stringify(item);
    assert.equal(context.isSystemService(item),true,item.observed.name);
    assert.equal(JSON.stringify(item),before);
  }
});
test('retains business services, containers, manual entries and unidentified sockets', () => {
  const manual = service('sshd','/usr/sbin/sshd',{source:'MANUAL'});
  const container = service('sshd'); container.observed.kind='CONTAINER';
  const socket = service('kernel'); socket.observed.kind='SOCKET'; socket.observed.pids=[];
  for (const item of [manual, container, socket, {source:'MANUAL',name:'systemd'}, service('sshd','/APP/sshd'),
    service('sshd','/usr/bin/company-agent'),service('systemd-helper','/usr/bin/systemd-helper'),service('kernel-api'),
    service('nginx','/usr/sbin/nginx'),service('prometheus','/usr/bin/prometheus'),service('grafana'),service('postgres'),
    service('unknown'),{observed:{name:'systemd'}}]) {
    assert.equal(context.isSystemService(item),false,JSON.stringify(item));
  }
});
