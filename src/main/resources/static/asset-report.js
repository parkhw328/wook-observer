"use strict";
let assetReportMarkdown = "";

// This renderer supports only our generated headings, field tables and text fences.
// Every data value is escaped; raw HTML, images, links and remote resources are never rendered.
function renderAssetMarkdown(markdown) {
  const inline = value => value.split("<br>").map(part => esc(part.replace(/&(amp|lt|gt|#\d+);/g, (all, entity) => {
    if (entity[0] === "#") return String.fromCodePoint(Number(entity.slice(1)));
    return {amp:"&", lt:"<", gt:">"}[entity];
  }))).join("<br>");
  const lines = markdown.split("\n"), result = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim()) continue;
    const fence = /^(`{3,})text$/.exec(line);
    if (fence) {
      const code = [];
      while (++i < lines.length && lines[i] !== fence[1]) code.push(lines[i]);
      result.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`); continue;
    }
    const heading = /^(#{1,5}) (.*)$/.exec(line);
    if (heading) { const level = Math.min(heading[1].length + 1, 6); result.push(`<h${level}>${inline(heading[2])}</h${level}>`); continue; }
    if (line.startsWith("| ")) {
      const rows = [];
      do {
        const values = lines[i].split("|").slice(1, -1).map(v => v.trim());
        if (!values.every(v => /^-+$/.test(v))) rows.push(values);
        i++;
      } while (i < lines.length && lines[i].startsWith("| "));
      i--;
      result.push(`<div class="table-wrap"><table><thead><tr>${rows[0].map(v => `<th>${inline(v)}</th>`).join("")}</tr></thead><tbody>${rows.slice(1).map(row => `<tr>${row.map(v => `<td>${inline(v)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`); continue;
    }
    result.push(`<p>${inline(line)}</p>`);
  }
  return result.join("");
}

async function assetReportView() {
  const target = location.hash.startsWith("#assets/report/") ? decodeURIComponent(location.hash.slice(15)) : "";
  const revision = renderRevision;
  const response = await fetch("/api/assets" + (target ? "/" + encodeURIComponent(target) : "") + "/report.md", {cache:"no-store"});
  if (!response.ok) throw new Error(response.status === 401 ? "다시 로그인하세요." : "자산 보고서를 불러오지 못했습니다. 다시 시도하세요.");
  const markdown = await response.text();
  if (revision === renderRevision) assetReportMarkdown = markdown;
  return pageHead("자산 보고서", "저장된 자산 정보와 서비스 운영대장을 하나의 Markdown 문서로 확인합니다.", `<a class="button" href="${target ? assetLink(target) : "#assets"}">자산으로 돌아가기</a><button data-action="download-asset-md" class="primary">MD 다운로드</button><button data-action="refresh-asset">새로고침</button>`)
    + `<article class="panel asset-markdown">${renderAssetMarkdown(markdown)}</article><details class="panel"><summary>Markdown 원문 보기</summary><pre class="markdown-source">${esc(markdown)}</pre></details>`;
}
document.addEventListener("click", event => {
  if (!event.target.closest('[data-action="download-asset-md"]')) return;
  const url = URL.createObjectURL(new Blob([assetReportMarkdown], {type:"text/markdown;charset=utf-8"}));
  const link = document.createElement("a");
  link.href = url; link.download = `observer-assets-${new Date().toISOString().slice(0, 10)}.md`;
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
});
