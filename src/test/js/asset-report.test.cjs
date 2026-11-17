const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {document: {addEventListener() {}}, esc: value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))};
vm.createContext(context);
vm.runInContext(fs.readFileSync('src/main/resources/static/asset-report.js', 'utf8'), context);
test('renders generated headings and field tables without executing HTML or remote resources', () => {
  const html = context.renderAssetMarkdown('# 자산 보고서\n\n| 항목 | 내용 |\n| --- | --- |\n| 위치 | A &#124; B<br>2층 |\n| 이름 | &lt;img src=x onerror=alert(1)&gt; |\n![image](https://example.com)\n<script>alert(1)</script>');
  assert.ok(html.includes('<h2>자산 보고서</h2>') && html.includes('<td>A | B<br>2층</td>'));
  assert.ok(!html.includes('<img') && !html.includes('<script>') && !html.includes('href='));
  assert.ok(html.includes('&lt;script&gt;'));
});
test('handles long fences, preserving shell commands, literal markup and newlines as text', () => {
  const html = context.renderAssetMarkdown('##### 시작 방법\n\n````text\necho ```literal```\n<script>\n````\n');
  assert.ok(html.includes('<h6>시작 방법</h6>'));
  assert.ok(html.includes('echo ```literal```\n&lt;script&gt;'));
});
