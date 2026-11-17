"use strict";
let offlinePreview = null, offlineRequest = 0;

async function offlineView() {
  const revision = renderRevision;
  const targets = await api("/targets");
  const selected = location.hash.startsWith("#offline/") ? decodeURIComponent(location.hash.slice(9)) : "";
  if (revision === renderRevision) { offlinePreview = null; offlineRequest++; }
  return pageHead("파일로 수집 결과 반입", "Observer에서 접속할 수 없는 서버는 현장에서 수집한 결과를 파일로 반입하세요.", '<a class="button" href="#assets">자산 목록</a>')
    + `<section class="panel"><h2>1. 수집 스크립트 준비</h2><p>아래 파일을 점검할 Linux 서버로 옮기세요. SSH 계정 등록이나 대상 서버의 Java 설치는 필요하지 않습니다.</p><a class="button primary" href="/api/collector/observer-collect.sh">수집 SH 다운로드</a></section>
    <section class="panel"><h2>2. 대상 서버에서 실행</h2><pre class="offline-command">sh observer-collect.sh</pre><p>현재 폴더에 생성되는 <code>observer-….observer</code> 파일을 가져오세요. 다른 저장 폴더를 쓰려면 <code>sh observer-collect.sh /저장/폴더</code>로 실행합니다.</p><p class="small">Linux의 sh·ps·timeout·sha256sum 등 기본 도구를 사용합니다. 실행 계정이 읽을 수 있는 범위를 최대 180초 동안 수집하며, 일반 계정에서는 일부 정보가 누락될 수 있습니다. Docker·jcmd·Python 3는 설치되어 있고 접근 가능한 경우에만 사용합니다. 도구 설치·서비스 변경·자동 권한 전환·외부 전송은 하지 않습니다.</p></section>
    <section class="panel"><h2>3. 결과 확인 후 반영</h2><form id="offline-import"><div class="grid"><label>반영할 자산<select name="targetId"><option value="">새 자산으로 등록</option>${targets.map(t => `<option value="${esc(t.id)}" ${selected === t.id ? "selected" : ""}>${esc(t.name)} · ${esc(t.host)}</option>`).join("")}</select></label><label>수집 결과 파일<input type="file" name="file" accept=".observer" required><small>원본 .observer 파일 · 최대 16 MiB</small></label></div>
    <div class="actions"><button type="button" data-offline="preview">파일 내용 확인</button></div><p class="error" role="alert"></p><div id="offline-preview" aria-live="polite"></div></form></section>`;
}

function offlinePreviewHtml(data, creating) {
  const host = data.host;
  return `<div class="import-preview"><h3>수집 결과</h3><dl class="import-facts"><dt>수집 호스트</dt><dd>${esc(host.name)}</dd><dt>운영체제</dt><dd>${esc(data.distribution || "미확인")}</dd><dt>수집 시각</dt><dd>${date(data.collectedAt)}</dd><dt>실행 범위</dt><dd>${esc(host.scope)}</dd><dt>수집 항목</dt><dd>프로세스 ${number(data.processes)} · JVM ${number(data.jvms)} · 컨테이너 ${number(data.containers)} · RPM ${number(data.rpmPackages)} · 포트 ${number(data.sockets)}</dd></dl>
    ${osSupportBanner(data.osSupport)}
    <details><summary>수집 제한 ${number(data.issues.length)}건 확인</summary><ul>${data.issues.map(i => `<li>${esc(i.layer)} · ${esc(i.target)}: ${esc(i.message)}</li>`).join("")}</ul></details>
    ${data.duplicate.id ? '<p class="note">이미 반영된 파일입니다. 같은 자산에 다시 반영하면 기존 이력으로 안내합니다.</p>' : ""}
    ${data.identityMismatch ? `<label class="check note warn"><input name="confirmIdentity" type="checkbox" required>이전 수집 호스트 ${esc(data.expectedHost)}와 현재 ${esc(host.name)}가 같은 서버인지 확인했습니다.</label>` : ""}
    ${creating ? `<div class="grid"><label>새 자산 이름<input name="name" maxlength="120" required value="${esc(host.name.slice(0, 120))}"></label><label>서버 주소 / 호스트명<input name="host" maxlength="253" required value="${esc(/^[A-Za-z0-9][A-Za-z0-9.:-]{0,252}$/.test(host.name) ? host.name : "")}" placeholder="관리할 서버 IP 또는 호스트명"><small>연결 검증 없이 자산 식별용으로 저장합니다.</small></label></div>` : ""}
    <p class="small">수집 서버와 반영할 자산을 확인하세요. 현재 내부 카탈로그로 판정하며 수기 정보는 유지합니다. 이전 시각의 파일은 이력에만 추가합니다.</p><details><summary>파일 SHA-256</summary><code class="file-hash">${esc(data.sha256)}</code></details>
    <div class="form-actions"><button type="submit" class="primary">확인한 결과 반영</button></div></div>`;
}

document.addEventListener("change", event => {
  const form = event.target.closest("#offline-import");
  if (form && ["file", "targetId"].includes(event.target.name)) {
    offlinePreview = null; offlineRequest++;
    $("#offline-preview").replaceChildren(); form.querySelector(".error").textContent = "";
  }
});
document.addEventListener("click", async event => {
  const button = event.target.closest('[data-offline="preview"]');
  if (!button || button.disabled) return;
  const form = button.closest("form"), file = form.elements.file.files[0];
  if (!file) { form.elements.file.reportValidity(); return; }
  if (file.size > 16 * 1024 * 1024) { form.querySelector(".error").textContent = "파일 제한은 16 MiB입니다."; return; }
  const request = ++offlineRequest;
  offlinePreview = null; $("#offline-preview").replaceChildren(); form.querySelector(".error").textContent = "";
  button.disabled = true; button.textContent = "검증 중…";
  try {
    const result = await api("/admin/offline/preview", {method:"POST", body:new FormData(form)});
    if (form !== $("#offline-import") || request !== offlineRequest) return;
    offlinePreview = result;
    $("#offline-preview").innerHTML = offlinePreviewHtml(result, !form.elements.targetId.value);
  } catch (error) { if (form === $("#offline-import") && request === offlineRequest) form.querySelector(".error").textContent = error.message; }
  finally { button.disabled = false; button.textContent = "파일 내용 확인"; }
});
document.addEventListener("submit", async event => {
  const form = event.target;
  if (!["offline-import", "offline-target-form"].includes(form.id)) return;
  event.preventDefault();
  if (form.id === "offline-import" && !offlinePreview) return;
  const body = new FormData(form), controls = [...form.querySelectorAll("input,select,button")];
  controls.forEach(input => input.disabled = true); form.dataset.saving = "true";
  form.querySelector(".error").textContent = "";
  try {
    if (form.id === "offline-import") {
      body.set("confirmIdentity", String(body.get("confirmIdentity") === "on"));
      const result = await api("/admin/offline/import", {method:"POST", body});
      offlinePreview = null; form.dataset.complete = "true";
      toast(result.duplicate ? "이미 반영된 파일입니다. 기존 자산으로 이동합니다." : result.currentInventory ? "수집 결과와 판정을 반영했습니다. 수기 정보는 유지했습니다." : "과거 수집 결과를 이력에 추가했습니다. 현재 자산의 최신 관측값은 유지했습니다.");
      location.hash = assetLink(result.targetId);
    } else {
      const result = await api("/admin/offline/targets" + (form.dataset.id ? "/" + encodeURIComponent(form.dataset.id) : ""), {method:form.dataset.id ? "PUT" : "POST", body:Object.fromEntries(body)});
      form.dataset.complete = "true"; toast("파일 업로드 자산을 저장했습니다."); location.hash = assetLink(result.id);
    }
  } catch (error) { form.querySelector(".error").textContent = error.message; }
  finally { controls.forEach(input => input.disabled = false); form.dataset.saving = "false"; }
});

async function offlineTargetForm(id) {
  const target = id ? await api("/admin/targets/" + encodeURIComponent(id)) : {};
  $("#content").innerHTML = pageHead("파일 업로드 자산 등록", "SSH 계정 없이 자산을 등록하고 현장에서 수집한 결과를 반입합니다.", "", true)
    + `<section class="panel"><form id="offline-target-form" data-id="${esc(id || "")}"><div class="grid"><label>자산 이름<input name="name" required maxlength="120" value="${esc(target.name)}"></label><label>서버 주소 / 호스트명<input name="host" required maxlength="253" value="${esc(target.host)}" placeholder="192.168.10.20 또는 내부 DNS"></label></div><label>설명<textarea name="description" maxlength="1000" rows="4">${esc(target.description)}</textarea></label><p class="note">서버에 접속하지 않고 관리용으로 저장합니다. 수집 결과는 자산 상세의 결과 업로드에서 반영할 수 있습니다.</p><p class="error" role="alert"></p><div class="form-actions"><button type="button" data-action="cancel-target">취소</button><button type="submit" class="primary">자산 등록 정보 저장</button></div></form></section>`;
}
function hasOfflineDraft() {
  const form = $("#offline-import");
  return !!form && form.dataset.complete !== "true" && !!form.elements.file.files.length;
}
window.addEventListener("beforeunload", event => {
  if (hasOfflineDraft()) { event.preventDefault(); event.returnValue = ""; }
});
