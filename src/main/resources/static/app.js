"use strict";
const $ = (selector) => document.querySelector(selector);
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );
const date = (value) =>
  value
    ? new Date(value).toLocaleString("ko-KR", { hour12: false })
    : "아직 없음";
const number = (value) => Number(value || 0).toLocaleString("ko-KR");
let session,
  currentView = "",
  catalogPage = 0,
  catalogQuery = "",
  toastTimer,
  renderRevision = 0;
const titles = {
  overview: "대시보드",
  targets: "점검 대상",
  assets: "자산 관리",
  offline: "수집 결과 업로드",
  jobs: "점검 이력",
  catalog: "취약점 데이터",
  updates: "데이터 업데이트",
  users: "사용자 관리",
};
const admin = () => session?.role === "ADMIN";
function toast(message) {
  $("#toast").textContent = message;
  $("#toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("#toast").hidden = true), 6500);
}
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (options.method && options.method !== "GET")
    headers[session.csrfHeader] = session.csrf;
  if (options.body && !(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.body);
  }
  const response = await fetch("/api" + path, {
    ...options,
    headers,
    cache: "no-store",
  });
  if (response.status === 401) {
    location.replace("/login.html");
    throw new Error("로그인이 필요합니다.");
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(
      (error.message ||
        (response.status === 403
          ? "접근 권한 또는 로그인 유효 시간을 확인하세요."
          : `요청 실패 (${response.status})`)) + (error.requestId ? ` (요청 ID: ${error.requestId})` : ""),
    );
  }
  return response.status === 204 ? null : response.json();
}
function badge(state) {
  const labels = {
    COMPLETED: "수집 완료",
    RUNNING: "점검 중",
    QUEUED: "대기 중",
    FAILED: "실패",
    SUCCESS: "반영 완료",
    ADMIN: "관리자",
    USER: "사용자",
  };
  const type = ["COMPLETED", "SUCCESS", "미발견"].includes(state)
    ? "good"
    : ["FAILED", "발견", "CRITICAL", "HIGH"].includes(state)
      ? "bad"
      : ["의심", "MEDIUM", "점검 불가"].includes(state)
        ? "warn"
        : "info";
  return `<span class="badge ${type}">${esc(labels[state] || state)}</span>`;
}
function pageHead(title, description, action = "", showEntityTitle = false) {
  return `<div class="page-head"><div>${showEntityTitle ? `<h2 class="entity-title">${esc(title)}</h2>` : ""}<p>${esc(description)}</p></div><div class="head-actions">${action}</div></div>`;
}
function empty(title, description, action = "") {
  return `<div class="empty"><b>${esc(title)}</b><p>${esc(description)}</p>${action}</div>`;
}
function table(headers, rows) {
  return `<div class="table-wrap"><table><thead><tr>${headers.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead><tbody>${rows.join("")}</tbody></table></div>`;
}
function downloads(id) {
  return ["xlsx", "zip", "json", "md"]
    .map(
      (format) =>
        `<a class="button" href="/api/jobs/${encodeURIComponent(id)}/download/${format}">${format.toUpperCase()}</a>`,
    )
    .join(" ");
}
function jobsTable(jobs) {
  if (!jobs.length)
    return empty(
      "첫 점검을 시작하세요",
      "대상을 등록하면 수집 결과와 보고서를 이곳에서 확인할 수 있습니다.",
      '<a class="button" href="#targets">점검 대상으로 이동 →</a>',
    );
  return table(
    ["점검 대상", "실행 / 반입 시각", "상태", "발견·의심", "결과"],
    jobs.map(
      (job) =>
        `<tr><td><b>${esc(job.target_name)}</b><div class="small">${esc(job.requested_by)} · ${job.collection_method === "UPLOAD" ? "파일 업로드" : "SSH"}</div></td><td>${date(job.created_at)}</td><td>${badge(job.status)}</td><td>${job.status === "COMPLETED" ? number(job.finding_count) + "건" : "—"}</td><td>${job.status === "COMPLETED" ? `<button data-action="detail" data-id="${esc(job.id)}">상세 보기</button> <a class="button" href="/api/jobs/${encodeURIComponent(job.id)}/download/xlsx">XLSX ↓</a>` : `<span class="small">${esc(job.message || "수집 결과를 기다리고 있습니다.")}</span>`}</td></tr>`,
    ),
  );
}
async function overview() {
  const [assets, jobs, catalog] = await Promise.all([api("/assets"), api("/jobs"), api("/catalog/status")]);
  const attention = assets.items.filter(a => !a.job_id || a.undocumented_count > 0 || a.latest_status === "FAILED");
  return pageHead("자산과 운영 현황을 한눈에", "등록 자산, 서비스 운영대장과 최근 점검 결과를 함께 확인하세요.", '<a class="button primary" href="#assets">자산 관리 열기 →</a>')
    + assetKpis(assets.summary)
    + `<div class="split"><section class="panel"><div class="panel-title"><h2>관리 현황</h2><a href="#assets">자산 목록 →</a></div><div class="inline-metric"><span>진행 중인 점검</span><strong>${number(assets.summary.inProgress)}대</strong></div><div class="inline-metric"><span>최근 점검 실패</span><strong>${number(assets.summary.failed)}대</strong></div><p class="small">점검 실패 시에도 이전 수집 기준 시각과 수기 운영대장은 유지됩니다.</p></section><section class="panel"><div class="panel-title"><h2>취약점 데이터</h2><a href="#catalog">목록 보기 →</a></div><div class="inline-metric"><span>내부 카탈로그</span><strong>${number(catalog.counts.reduce((sum, c) => sum + c.count, 0))}건</strong></div><p class="small">최근 반영 ${date(catalog.imported_at)} · 개정 ${catalog.revision}</p><p class="small">판정 범위와 수집 한계를 점검 상세에서 확인하세요.</p></section></div>`
    + `<section class="panel"><div class="panel-title"><h2>점검·운영 정보 보완</h2><span class="small">${number(attention.length)}대 · 최대 5대 표시</span></div>${attention.length ? assetTable(attention.slice(0, 5)) : '<p>점검 전·실패·운영 절차 미입력 자산이 없습니다.</p>'}</section>`
    + `<section class="panel"><div class="panel-title"><h2>최근 점검</h2><a href="#jobs">전체 이력 →</a></div>${jobsTable(jobs.slice(0, 5))}</section>`;
}
async function targetView() {
  const targets = await api("/targets");
  return (
    pageHead(
      "점검 대상",
      "SSH 점검 또는 현장 수집 파일 업로드로 서버를 관리합니다.",
      admin()
        ? '<a class="button" href="#offline">수집 SH / 결과 업로드</a><button data-action="add-upload-target">+ 파일 업로드 자산</button><button class="primary" data-action="add-target">+ SSH 대상</button>'
        : "",
    ) +
    '<div class="note">Linux의 셸·ps 등 기본 도구가 필요하며 SSH 방식에는 서버 접속 권한이 필요합니다. 컨테이너는 Docker 조회 권한, Java 상세는 Python 3 또는 jcmd 등 대상에서 사용할 수 있는 도구에 따라 수집 범위가 달라집니다.</div>' +
    (targets.length
      ? `<div class="target-grid">${targets.map((t) => `<article class="target-card"><div class="head-actions">${t.collection_mode === "UPLOAD" ? '<span class="badge info">파일 업로드 / LINUX</span>' : `<span class="badge info">SSH / LINUX</span><span class="badge">${t.auth_type === "PASSWORD" ? "비밀번호 인증" : "SSH 키 인증"}</span><span class="badge">${esc({NONE:"접속 계정 권한", SU_ROOT:"root 전환 · su", SUDO:"root 전환 · sudo", SUDO_NOPASSWD:"root 전환 · sudo"}[t.privilege_mode || "NONE"])}</span>`}</div><h2>${esc(t.name)}</h2><p class="target-address">${t.collection_mode === "UPLOAD" ? esc(t.host) : `${esc(t.ssh_user)}@${esc(t.host)}:${t.port}`}</p><p class="small">${esc(t.description || "등록된 설명이 없습니다.")}</p><div class="actions"><a class="button" href="${assetLink(t.id)}">자산 관리</a>${admin() ? `<button class="quiet" data-action="edit-target" data-id="${esc(t.id)}">${t.collection_mode === "UPLOAD" ? "자산 설정" : "연결 설정"}</button>` : ""}${t.collection_mode === "UPLOAD" ? `${admin() ? `<a class="button primary" href="#offline/${encodeURIComponent(t.id)}">결과 업로드 →</a>` : ""}<a class="button" href="/api/collector/observer-collect.sh">수집 SH</a>` : `<button class="primary" data-action="scan" data-id="${esc(t.id)}">점검 시작 →</button>`}</div></article>`).join("")}</div>`
      : empty(
          "아직 등록된 대상이 없습니다",
          "SSH 대상을 등록하거나 현장에서 수집한 파일로 자산을 추가할 수 있습니다.",
        ))
  );
}
async function targetForm(id) {
  const target = id ? await api("/admin/targets/" + encodeURIComponent(id)) : {};
  if (target.collection_mode === "UPLOAD") return offlineTargetForm(id);
  const selected = (value, expected) => value === expected ? "selected" : "";
  const auth = target.auth_type || "PASSWORD";
  $("#content").innerHTML = pageHead(
    id ? "대상 연결 설정" : "새 점검 대상 등록",
    "서버 주소와 접속 정보를 입력하세요. 등록할 때 서버 지문을 자동으로 조회합니다.",
    "", true,
  ) + `<section class="panel connection-panel"><form id="target-form" data-id="${esc(id || "")}" data-auth="${esc(target.auth_type || "")}" data-privilege="${esc(target.privilege_mode || "")}" data-has-key="${!!target.hasPrivateKey}" data-has-password="${!!target.hasSshPassword}" data-has-privilege="${!!target.hasPrivilegePassword}" data-host-confirmed="${!!target.host_key}">
    <fieldset><legend><span class="step-number">01</span> 서버 연결</legend>
    <div class="grid"><label>대상 이름<input name="name" required maxlength="120" value="${esc(target.name)}" placeholder="예: 주문 서비스 운영 서버"></label>
    <label>SSH 호스트<input name="host" required maxlength="253" value="${esc(target.host)}" placeholder="192.168.10.20 또는 내부 DNS"></label>
    <label>SSH 포트<input name="port" type="number" min="1" max="65535" required value="${target.port || 22}"></label>
    <label>SSH 접속 계정<input name="sshUser" required maxlength="64" value="${esc(target.ssh_user)}" autocomplete="off" placeholder="예: observer 또는 root"></label></div></fieldset>
    <fieldset><legend><span class="step-number">02</span> SSH 로그인</legend>
    <label>인증 방식<select name="authType"><option value="PASSWORD" ${selected(auth, "PASSWORD")}>SSH 계정 비밀번호</option><option value="KEY" ${selected(auth, "KEY")}>SSH 개인 키</option></select></label>
    <div id="password-auth-fields"><label>SSH 계정 비밀번호<input name="sshPassword" type="password" autocomplete="new-password" maxlength="1024"><small>${target.hasSshPassword ? "비밀번호가 저장되어 있습니다. 빈칸이면 기존 값을 유지합니다." : "접속 계정의 비밀번호를 입력하세요. 키 파일을 준비할 필요가 없습니다."}</small></label></div>
    <div id="key-auth-fields" hidden><label>접속용 SSH 개인 키<textarea name="privateKey" autocomplete="off" spellcheck="false" rows="5" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"></textarea><small>${target.hasPrivateKey ? "개인 키가 저장되어 있습니다. 빈칸이면 기존 키를 유지합니다." : "PEM 또는 OpenSSH 형식의 개인 키를 입력하세요."}</small></label>
    <label>개인 키 암호 <span class="muted">(선택)</span><input name="keyPassphrase" type="password" autocomplete="new-password" maxlength="1024"><small>암호화된 개인 키인 경우 입력하세요. 새 키를 넣으면 암호도 새로 입력해야 합니다.${target.hasKeyPassphrase ? " 저장된 키를 유지하면 기존 암호도 유지됩니다." : ""}</small></label></div>
    </fieldset><fieldset><legend><span class="step-number">03</span> 점검 권한</legend>
    <label>점검 실행 방식<select name="privilegeMode"><option value="NONE" ${selected(target.privilege_mode, "NONE")}>접속 계정 권한으로 점검</option><option value="SU_ROOT" ${selected(target.privilege_mode, "SU_ROOT")}>root 비밀번호로 전환 · su</option><option value="SUDO" ${selected(target.privilege_mode, "SUDO")}>sudo 인증 비밀번호로 root 전환</option><option value="SUDO_NOPASSWD" ${selected(target.privilege_mode, "SUDO_NOPASSWD")}>비밀번호 없는 sudo로 root 전환</option></select></label>
    <p id="privilege-help" class="field-description"></p>
    <div id="privilege-password-field" hidden><label><span id="privilege-password-label">root 비밀번호</span><input name="privilegePassword" type="password" autocomplete="new-password" maxlength="1024"><small>${target.hasPrivilegePassword ? "같은 전환 방식을 유지하면 빈칸은 기존 비밀번호를 보존합니다." : "선택한 권한 전환에 사용할 비밀번호를 입력하세요."}</small></label></div>
    <div class="note">root 전환을 선택하면 실제 root 권한을 확인한 뒤 점검합니다. 전환 실패와 수집하지 못한 항목은 점검 결과에 기록합니다.</div>
    </fieldset><fieldset id="server-identity"><legend><span class="step-number">04</span> 서버 확인</legend>
    <p class="field-description">공개 키를 직접 입력하지 않아도 됩니다. 서버에서 조회한 지문을 확인하면 이후 접속에서도 같은 서버인지 검증합니다.</p>
    <div id="host-key-probe"><button type="button" data-action="probe-host-key">서버 지문 미리 조회</button><div id="host-key-results" aria-live="polite"></div></div>
    <p id="host-key-status" class="small" aria-live="polite">${target.host_key ? "저장된 서버 지문 · " + esc(target.hostKeyFingerprint || "확인된 호스트 키") : "연결 정보 저장을 누르면 서버 지문을 조회하고 확인을 안내합니다."}</p>
    <details class="advanced-connection"><summary>서버 확인 방식 변경</summary>
    <label>호스트 키 확인 방식<select id="host-key-method"><option value="PROBE">자동 조회 후 지문 확인</option><option value="MANUAL">공개 키 직접 입력</option></select></label>
    <div id="host-key-manual" hidden><label>서버 호스트 공개 키<textarea name="hostKey" rows="3" maxlength="8192" spellcheck="false" placeholder="ssh-ed25519 AAAA…">${esc(target.host_key)}</textarea><small>.pub 또는 known_hosts의 한 줄을 입력할 수 있습니다.</small></label></div></details></fieldset>
    <label>설명<textarea name="description" maxlength="1000" placeholder="운영 환경, 담당 팀, 접근 범위 등을 기록하세요.">${esc(target.description)}</textarea></label>
    <p class="small">개인 키와 비밀번호는 암호화해 저장하며, 저장된 비밀값은 화면에 다시 표시하지 않습니다.</p>
    <div class="form-actions"><button type="button" data-action="cancel-target">취소</button><button class="primary" type="submit">연결 정보 저장</button></div></form></section>`;
  if (target.host_key) $("#target-form").dataset.confirmedEndpoint = hostEndpoint($("#target-form"));
  updateTargetFields();
}
function hostEndpoint(form) {
  return JSON.stringify([form.elements.host.value, Number(form.elements.port.value)]);
}
function updateTargetFields() {
  const form = $("#target-form");
  if (!form) return;
  const key = form.elements.authType.value === "KEY";
  $("#key-auth-fields").hidden = !key;
  $("#password-auth-fields").hidden = key;
  form.elements.privateKey.disabled = !key;
  form.elements.keyPassphrase.disabled = !key;
  form.elements.sshPassword.disabled = key;
  form.elements.privateKey.required = key && !(form.dataset.auth === "KEY" && form.dataset.hasKey === "true");
  form.elements.sshPassword.required = !key && !(form.dataset.auth === "PASSWORD" && form.dataset.hasPassword === "true");
  const mode = form.elements.privilegeMode.value;
  const needsPassword = ["SU_ROOT", "SUDO"].includes(mode);
  $("#privilege-password-field").hidden = !needsPassword;
  form.elements.privilegePassword.disabled = !needsPassword;
  form.elements.privilegePassword.required = needsPassword && !(form.dataset.privilege === mode && form.dataset.hasPrivilege === "true");
  $("#privilege-password-label").textContent = mode === "SU_ROOT" ? "root 계정 비밀번호" : "sudo 인증 비밀번호";
  $("#privilege-help").textContent = {
    NONE: "접속 계정이 root이면 그대로 root 권한을 사용합니다. 일반 계정은 접근 가능한 범위만 점검합니다.",
    SU_ROOT: "일반 계정으로 SSH 접속한 다음 root 비밀번호로 su 전환합니다. 대상 서버가 해당 계정의 su 사용을 허용해야 합니다.",
    SUDO: "대상 서버의 sudo 권한이 필요합니다. 일반적으로 SSH 접속 계정의 비밀번호를 입력하며, 실제 sudo 정책을 따릅니다.",
    SUDO_NOPASSWD: "대상 서버에서 해당 계정의 비밀번호 없는 sudo 실행을 허용한 경우 사용합니다.",
  }[mode];
}
function resetHostProbe() {
  const form = $("#target-form");
  if (!form || $("#host-key-method").value !== "PROBE") return;
  form.dataset.probeRevision = String(Number(form.dataset.probeRevision || 0) + 1);
  form.dataset.hostConfirmed = "false";
  form.dataset.pendingSave = "false";
  delete form.dataset.confirmedEndpoint;
  form.elements.hostKey.value = "";
  $("#host-key-results").replaceChildren();
  $("#host-key-status").textContent = "연결 정보 저장을 누르면 서버 지문을 조회하고 확인을 안내합니다.";
}
async function probeHostKey(pendingSave = false) {
  const form = $("#target-form");
  if (!form.elements.host.reportValidity() || !form.elements.port.reportValidity()) return;
  resetHostProbe();
  form.dataset.pendingSave = String(pendingSave);
  const endpoint = hostEndpoint(form), revision = form.dataset.probeRevision;
  const host = form.elements.host.value, port = Number(form.elements.port.value);
  $("#host-key-status").textContent = "서버 지문을 조회하고 있습니다…";
  try {
    const keys = await api("/admin/ssh/host-keys", {method: "POST", body: {host, port}});
    if (form !== $("#target-form") || endpoint !== hostEndpoint(form) || revision !== form.dataset.probeRevision || $("#host-key-method").value !== "PROBE") return;
    form.dataset.probedEndpoint = endpoint;
    $("#host-key-results").innerHTML = `<label>조회한 서버 지문<select id="host-key-choice">${keys.map(key => `<option value="${esc(key.publicKey)}" data-fingerprint="${esc(key.fingerprint)}">${esc(key.type)}</option>`).join("")}</select></label><output id="host-fingerprint" class="fingerprint">${esc(keys[0].fingerprint)}</output><p class="field-description">서버 관리자가 확인한 SHA-256 지문과 대조하세요. 확인한 키는 저장되며 이후 키가 바뀌면 접속을 차단합니다.</p><label class="check"><input id="host-key-verified" type="checkbox">접속할 서버의 지문이 맞는지 확인했습니다.</label><button type="button" class="primary" data-action="confirm-host-key" disabled>${pendingSave ? "지문 확인 후 저장" : "확인한 서버 사용"}</button>`;
    $("#host-key-status").textContent = "서버 지문 확인을 기다리고 있습니다.";
    $("#host-key-results").scrollIntoView({block: "center", behavior: "smooth"});
  } catch (error) {
    if (form === $("#target-form") && revision === form.dataset.probeRevision) $("#host-key-status").textContent = "서버 지문을 조회하지 못했습니다. 주소·포트와 연결 상태를 확인한 뒤 다시 시도하세요.";
    throw error;
  }
}
document.addEventListener("change", event => {
  const form = event.target.closest("#target-form");
  if (!form) return;
  if (["authType", "privilegeMode"].includes(event.target.name)) updateTargetFields();
  if (event.target.id === "host-key-method") {
    const probe = event.target.value === "PROBE";
    $("#host-key-probe").hidden = !probe;
    $("#host-key-manual").hidden = probe;
    form.elements.hostKey.required = !probe;
    $("#host-key-status").textContent = "";
    if (probe) resetHostProbe();
  }
  if (event.target.id === "host-key-choice") {
    $("#host-fingerprint").textContent = event.target.selectedOptions[0].dataset.fingerprint;
    $("#host-key-verified").checked = false;
    $("[data-action=confirm-host-key]").disabled = true;
    form.dataset.hostConfirmed = "false";
    form.elements.hostKey.value = "";
  }
  if (event.target.id === "host-key-verified") {
    $("[data-action=confirm-host-key]").disabled = !event.target.checked;
    if (!event.target.checked) { form.dataset.hostConfirmed = "false"; form.elements.hostKey.value = ""; }
  }
});
document.addEventListener("input", event => {
  if (event.target.closest("#target-form") && ["host", "port"].includes(event.target.name)) resetHostProbe();
});
async function catalogView() {
  const page = catalogPage, query = catalogQuery;
  const [status, result] = await Promise.all([
    api("/catalog/status"),
    api(
      "/catalog/page?query=" +
        encodeURIComponent(query) +
        "&page=" +
        page,
    ),
  ]);
  const rows = result.items;
  const pager = `<div class="form-actions catalog-pager"><button type="button" data-action="catalog-page" data-page="0" ${result.hasPrevious ? "" : "disabled"}>처음</button><button type="button" data-action="catalog-page" data-page="${result.page - 1}" ${result.hasPrevious ? "" : "disabled"}>이전</button><span class="small" aria-live="polite">${result.page + 1} / ${result.totalPages} 페이지 · 전체 ${number(result.total)}건</span><button type="button" data-action="catalog-page" data-page="${result.page + 1}" ${result.hasNext ? "" : "disabled"}>다음</button><button type="button" data-action="catalog-page" data-page="${result.totalPages - 1}" ${result.hasNext ? "" : "disabled"}>마지막</button></div>`;
  return (
    pageHead(
      "취약점 데이터",
      "내부에 저장한 CVE·CCE와 설정 규칙을 조회합니다.",
      admin()
        ? '<a class="button primary" href="#updates">데이터 반입 / 업데이트 →</a>'
        : "",
    ) +
    `<div class="note">카탈로그 버전 <b>${status.revision}</b> · 최근 반영 ${date(status.imported_at)}<br>목록 출처: ${esc(status.source)}<br>Maven 및 RHEL·Rocky·AlmaLinux 8/9/10 호스트 RPM을 대조합니다. RPM은 배포판별 보안 권고·EVR 기준이며 모듈·제품 스트림 미확인은 미판정입니다.</div><section class="panel"><form class="search" id="catalog-search"><label>식별자 / 패키지 검색<input name="query" value="${esc(query)}" maxlength="200" placeholder="CVE, RHSA, RLSA, ALSA, GHSA 또는 패키지"></label><button type="submit">검색</button></form>` + pager +
    (rows.length
      ? table(
          ["종류", "식별자", "패키지 / 설정", "제목 · 상태"],
          rows.map(
            (row) =>
              `<tr><td>${badge(row.kind)}</td><td><b>${esc(row.advisory_id)}</b><div class="small">${esc((row.document.aliases || []).join(", "))}</div></td><td>${esc(row.package_name)}</td><td>${esc(row.document.summary || row.document.title || "")}${row.document.withdrawn ? '<div class="badge warn">철회됨 · 대조 제외</div>' : ""}</td></tr>`,
          ),
        )
      : empty(
          "검색 결과가 없습니다",
          "다른 식별자 또는 패키지 이름으로 검색하세요.",
        )) +
    pager + "</section>"
  );
}
async function updatesView() {
  const { settings, history } = await api("/admin/updates");
  return (
    pageHead(
      "데이터 업데이트",
      "폐쇄망 파일 반입과 내부 HTTPS 미러의 정기 갱신을 관리합니다.",
    ) +
    `<div class="split"><section class="panel"><h2>취약점 목록 반입</h2><p class="small">OSV JSON, JSON 배열, OSV JSON 파일을 담은 ZIP 또는 wook-catalog-1 묶음 파일을 지원합니다. 최대 64MiB.</p><form id="import-form"><label>JSON / ZIP 파일<input name="file" type="file" accept=".json,.zip" required></label><div class="note">전체 파일 검증에 성공하면 변경을 반영합니다. 기존 목록을 유지하며 ID별 갱신하고, 철회된 권고는 판정에서 제외합니다.</div><button class="primary" type="submit">검증 후 반영</button></form></section><section class="panel"><h2>정기 업데이트 설정</h2><form id="schedule-form"><label class="check"><input type="checkbox" name="enabled" ${settings.enabled ? "checked" : ""}>정기 갱신 사용</label><label>업데이트 원본<input name="source" required value="${esc(settings.source)}"><small>반입 폴더의 파일명(예: catalog.json) 또는 서버에서 허용한 내부 HTTPS 미러 URL</small></label><div class="grid"><label>실행 주기 (6필드 cron)<input name="cron" required value="${esc(settings.cron)}"><small>0 0 3 * * * = 매일 03:00</small></label><label>시간대<input name="zone" required value="${esc(settings.zone)}"></label></div><p class="small">다음 실행: ${date(settings.next_run)}</p><div class="actions"><button class="primary" type="submit">설정 저장</button><button type="button" data-action="update-now">저장된 원본 지금 갱신</button></div></form></section></div><section class="panel"><div class="panel-title"><h2>갱신 이력</h2><span class="small">최근 100건</span></div>` +
    (history.length
      ? table(
          ["시작 시각", "원본", "결과", "레코드", "개정 / SHA-256", "내용"],
          history.map(
            (row) =>
              `<tr><td>${date(row.started_at)}</td><td>${esc(row.source)}</td><td>${badge(row.status)}</td><td>${number(row.record_count)}</td><td>${row.catalog_revision ?? "—"}<div class="small">${esc(row.sha256 || "")}</div></td><td>${esc(row.message)}</td></tr>`,
          ),
        )
      : empty(
          "갱신 이력이 없습니다",
          "파일 반입 또는 즉시 갱신 후 결과를 확인할 수 있습니다.",
        )) +
    "</section>"
  );
}
async function usersView() {
  const users = await api("/admin/users");
  return (
    pageHead(
      "사용자 관리",
      "관리자는 연결·데이터·계정을 관리하고, 사용자는 모든 등록 대상의 점검과 결과 조회를 수행합니다.",
      '<button class="primary" data-action="add-user">+ 사용자 추가</button>',
    ) +
    '<div class="note">계정 비활성화, 권한 또는 비밀번호 변경 시 기존 로그인 세션이 만료됩니다. 마지막 활성 관리자는 유지됩니다.</div><section class="panel">' +
    table(
      ["아이디", "권한", "상태", "생성 시각", "관리"],
      users.map(
        (user) =>
          `<tr><td><b>${esc(user.username)}</b>${user.username === session.username ? ' <span class="badge">내 계정</span>' : ""}</td><td>${badge(user.role)}</td><td>${user.enabled ? '<span class="badge good">활성</span>' : '<span class="badge bad">비활성</span>'}</td><td>${date(user.created_at)}</td><td><button data-action="edit-user" data-user="${esc(user.username)}" data-role="${user.role}" data-enabled="${user.enabled}">계정 편집</button></td></tr>`,
      ),
    ) +
    "</section>"
  );
}
function userForm(button) {
  const edit = button.dataset.action === "edit-user";
  $("#modal-content").innerHTML =
    `<h2>${edit ? "계정 편집" : "사용자 추가"}</h2><form id="user-form" data-edit="${edit}"><label>아이디<input name="username" required minlength="3" maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,63}" value="${esc(button.dataset.user)}" ${edit ? "readonly" : ""} autocomplete="off"></label><label>${edit ? "새 비밀번호 (빈칸이면 유지)" : "초기 비밀번호"}<input name="password" type="password" ${edit ? "" : "required"} minlength="8" autocomplete="new-password"><small>8자 이상, UTF-8 72바이트 이하</small></label><label>권한<select name="role"><option value="USER" ${button.dataset.role === "USER" ? "selected" : ""}>사용자 · 점검 / 조회</option><option value="ADMIN" ${button.dataset.role === "ADMIN" ? "selected" : ""}>관리자 · 전체 관리</option></select></label><label class="check"><input name="enabled" type="checkbox" ${!edit || button.dataset.enabled === "true" ? "checked" : ""}>계정 활성화</label><button class="primary" type="submit">저장</button></form>`;
  $("#modal").showModal();
}
function systemDetails(report) {
  const inventory = report.systemInventory;
  const statuses = { COLLECTED: "수집 완료", UNAVAILABLE: "파일·도구 없음", FAILED: "조회 실패",
    TIMEOUT: "시간 제한", TRUNCATED: "일부 수집·제한", NOT_COLLECTED: "미수집" };
  const rpm = inventory?.rpm;
  const packages = rpm?.packages || [];
  const socketInventory = inventory?.sockets;
  const sockets = socketInventory?.entries || [];
  const assessment = report.assessment;
  const rpmStates = new Map();
  const rank = { "발견": 0, "의심": 1, "미판정": 2, "미발견": 3, "비대상": 4 };
  for (const row of [...(assessment?.coverage || []), ...(assessment?.findings || [])]) {
    if (row.kind === "RPM" && (!rpmStates.has(row.asset) || rank[row.status] < rank[rpmStates.get(row.asset)])) rpmStates.set(row.asset, row.status);
  }
  const rpmFallback = assessment ? (rpmStates.size ? "미판정" : "미실시(이전 점검)") : "미실시";
  return '<h3>OS·커널 정보</h3>' +
    (inventory?.os?.length ? table(["근거", "수집 상태", "값"], inventory.os.map(d =>
      `<tr><td>${esc(d.source)}</td><td class="system-status">${esc(statuses[d.status] || "미수집")}</td><td class="system-value">${esc(d.value || "—")}</td></tr>`))
      : empty("OS 상세 미수집", "이전 버전의 결과는 다시 점검하면 OS·RPM 정보를 확인할 수 있습니다.")) +
    '<h3>열린 포트 · TCP 리스닝 / UDP 바인딩</h3>' +
    `<p>${esc(statuses[socketInventory?.status] || "미수집")} · 소켓 ${number(sockets.length)}개</p><p class="small">${esc(socketInventory?.message || "이전 결과는 새 점검부터 포트를 수집합니다.")} 현재 네트워크 네임스페이스의 주소이며 방화벽·외부 접속 가능성은 별도 확인합니다.</p>` +
    (sockets.length ? table(["프로토콜", "바인딩 주소", "포트", "상태", "PID · 프로세스"], sockets.slice(0, 200).map(s =>
      `<tr><td>${esc(s.protocol)}</td><td>${esc(s.localAddress)}</td><td>${esc(s.localPort)}</td><td>${esc(s.state)}</td><td>${s.processes?.length ? s.processes.map(p => `PID ${esc(p.pid)} / ${esc(p.name)}`).join("<br>") : "미확인(권한/프로세스 종료 등)"}</td></tr>`)) : "") +
    (sockets.length > 200 ? '<p class="small">첫 200개 소켓입니다. 전체 목록은 XLSX·JSON·ZIP에서 확인하세요.</p>' : "") +
    '<h3>설치 RPM 패키지</h3>' +
    `<p>${esc(statuses[rpm?.status] || "미수집")} · 수집 항목 ${number(packages.length)}건</p><p class="small">${esc(rpm?.message || "이 스냅샷에서는 RPM을 수집하지 않았습니다.")} RHEL·Rocky·AlmaLinux 8/9/10의 공식 권고와 저장된 판정입니다. 컨테이너별 RPM DB는 미지원입니다.</p><details><summary>저장소·제품 스트림 근거</summary><p>${esc(statuses[rpm?.repositoryStatus] || "미수집")} · 릴리스 고정 ${esc(rpm?.releaseVersion || "없음/미확인")}</p><p>${esc((rpm?.enabledRepositories || []).join(", "))}</p></details>` +
    (packages.length ? table(["이름", "설치 EVR", "아키텍처", "취약점발견", "패키지 근거"], packages.slice(0, 100).map(p =>
      `<tr><td>${esc(p.name)}</td><td>${esc(p.epoch)}:${esc(p.version)}-${esc(p.release)}</td><td>${esc(p.architecture)}</td><td>${badge(rpmStates.get(`rpm:${p.name}-${p.epoch}:${p.version}-${p.release}.${p.architecture}`) || rpmFallback)}</td><td><details><summary>소스·모듈·공급자</summary>${esc(p.sourceRpm || "미확인")}<br>${esc(p.modularityLabel || "비모듈/미확인")}<br>${esc(p.vendor || "미확인")}</details></td></tr>`)) : "") +
    (packages.length > 100 ? '<p class="small">첫 100건을 표시합니다. 전체 수집 목록은 XLSX·JSON·ZIP에서 확인하세요.</p>' : "");
}
async function detail(id) {
  const { report, operator } = await api("/jobs/" + encodeURIComponent(id) + "/overview");
  const actions = operator.actions;
  const list = (values) => values.map(v => esc(v)).join("<br>");
  const location = l => l.pid == null ? (l.path || l.asset) : `PID ${l.pid}${l.containerId ? " / " + l.containerId : ""} / ${l.path}`;
  $("#modal-content").innerHTML =
    `<div class="details-header"><div><p class="eyebrow">INSPECTION REPORT</p><h2>${esc(report.host.name)}</h2></div><span class="badge good">수집 완료</span></div><p class="small">${date(report.collectedAt)} · ${esc(report.host.scope)}</p><div class="actions">${downloads(id)}</div><div class="detail-grid"><div>프로세스<strong>${number(report.processes.length)}</strong></div><div>고유 라이브러리<strong>${number(operator.libraries.length)}</strong></div><div>발견·의심 조치<strong>${number(actions.length)}</strong></div></div><p class="small">카탈로그 개정 ${report.assessment?.catalogRevision ?? "미실시"} · 직군·패키지·버전별 중복 제거. 비대상·미판정은 조치 수에서 제외합니다.</p><h3>발견·의심 조치 ${actions.length > 200 ? "(첫 200건)" : ""}</h3>` +
    (actions.length ? table(["종류 / ID", "직군 · 패키지", "취약점발견", "심각도", "근거 · 조치"], actions.slice(0, 200).map(a =>
      `<tr><td><b>${esc(a.id)}</b><div class="small">${esc(a.kind)}</div></td><td>${esc(a.serviceGroup)}<br>${esc(a.packageName)}<br><b>${esc(a.version)}</b></td><td>${badge(a.status)}</td><td>${badge(a.severity)}</td><td><b>${esc(a.title)}</b><p>${list(a.recommendations)}</p><details><summary>근거 · 영향 위치 ${a.locations.length}곳</summary><p>${list(a.evidence)}</p><p>${list(a.locations.map(location))}</p><p class="small">${list(a.aliases)}<br>${list(a.references)}</p>${a.relatedIds?.length ? `<p class="small">권고의 관련 CVE (각 바이너리의 개별 영향 확정 아님)<br>${list(a.relatedIds)}</p>` : ""}</details></td></tr>`))
      : empty("발견·의심 항목이 없습니다", "미판정은 안전 판정이 아닙니다. 아래 점검 범위를 확인하세요.")) +
    '<h3>점검 범위</h3>' +
    `<p class="small">${esc(report.assessment?.scope || "취약점 대조 미실시")} CVE는 라이브러리 위치, RPM은 설치 NEVRA, 설정은 대상별 규칙 수입니다. 일부 미지원 권고가 있으면 상태별 중복될 수 있습니다.</p>` +
    table(["종류", "판정", "이유", "항목 수"], operator.coverage.map(c => `<tr><td>${esc(c.kind)}</td><td>${esc(c.status)}</td><td>${esc(c.reason)}</td><td>${number(c.count)}</td></tr>`)) +
    `<details class="report-section"><summary>라이브러리 명세 · ${number(operator.libraries.length)}개 (직군·버전별)</summary>` +
    table(["패키지", "버전", "취약점발견", "관련 ID", "식별 · 영향 위치"], operator.libraries.slice(0, 200).map(l =>
      `<tr><td>${esc(l.name)}</td><td>${esc(l.version)}</td><td>${esc(l.status)}</td><td>${list(l.advisories)}</td><td><details><summary>위치 ${l.locations.length}곳</summary><p>${list(l.evidence)}</p>${list(l.locations.map(location))}</details></td></tr>`)) +
    '<p class="small">첫 200개를 표시합니다. 직군별 전체 명세와 원문은 XLSX·ZIP·JSON으로 내려받을 수 있습니다.</p></details>' +
    '<details class="report-section"><summary>OS·커널 · 열린 포트 · 설치 RPM 명세</summary>' + systemDetails(report) + '</details>' +
    `<details class="report-section"><summary>수집 제한 · 오류 ${operator.issues.length}종류</summary>` +
    table(["계층", "대상", "내용"], operator.issues.map(i => `<tr><td>${esc(i.layer)}</td><td>${list(i.targets)}</td><td>${esc(i.message)}</td></tr>`)) + '</details>';
  $("#modal").showModal();
}
async function render() {
  if (!session) return;
  const requested = location.hash.slice(1).split("/")[0] || "overview";
  currentView = Object.hasOwn(titles, requested) ? requested : "overview";
  if (["users", "updates", "offline"].includes(currentView) && !admin())
    currentView = "overview";
  const view = currentView;
  const revision = ++renderRevision;
  document
    .querySelectorAll("[data-view]")
    .forEach((link) =>
      link.classList.toggle("active", link.dataset.view === view),
    );
  $("#breadcrumb").textContent = "워크스페이스 / " + titles[view];
  document.title = titles[view] + " · Wook Observer";
  try {
    const html = await {
      overview,
      targets: targetView,
      assets: assetView,
      offline: offlineView,
      jobs: async () =>
        pageHead(
          "점검 이력",
          "동일 스냅샷의 XLSX·Markdown·JSON 보고서를 다시 내려받을 수 있습니다.",
        ) +
        '<section class="panel">' +
        jobsTable(await api("/jobs")) +
        "</section>",
      catalog: catalogView,
      updates: updatesView,
      users: usersView,
    }[view]();
    if (revision === renderRevision && currentView === view) {
      closeAssetPopover();
      $("#content").classList.toggle("asset-workspace", view === "assets");
      $("#content").innerHTML = html;
    }
  } catch (error) {
    if (revision === renderRevision) toast(error.message);
  }
}
document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  button.disabled = true;
  try {
    switch (button.dataset.action) {
      case "add-upload-target":
        await offlineTargetForm();
        break;
      case "add-target":
        await targetForm();
        break;
      case "edit-target":
        await targetForm(button.dataset.id);
        break;
      case "cancel-target":
        await render();
        break;
      case "probe-host-key":
        await probeHostKey(false);
        break;
      case "confirm-host-key": {
        const form = $("#target-form");
        if (!$("#host-key-verified").checked || form.dataset.probedEndpoint !== hostEndpoint(form)) break;
        form.elements.hostKey.value = $("#host-key-choice").value;
        form.dataset.hostConfirmed = "true";
        form.dataset.confirmedEndpoint = hostEndpoint(form);
        $("#host-key-status").textContent = "서버 지문을 확인했습니다.";
        if (form.dataset.pendingSave === "true") {
          form.dataset.pendingSave = "false";
          form.requestSubmit();
        }
        break;
      }
      case "scan":
        await api(
          "/targets/" + encodeURIComponent(button.dataset.id) + "/scans",
          { method: "POST" },
        );
        toast("점검을 시작했습니다. 점검 이력에서 진행 상황을 확인하세요.");
        location.hash = "jobs";
        if (currentView === "jobs") await render();
        break;
      case "detail":
        await detail(button.dataset.id);
        break;
      case "catalog-page":
        catalogPage = Number(button.dataset.page);
        await render();
        break;
      case "update-now":
        await api("/admin/updates/run", { method: "POST" });
        toast("원본 데이터를 반영했습니다.");
        await render();
        break;
      case "add-user":
      case "edit-user":
        userForm(button);
        break;
    }
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
});
document.addEventListener("submit", async (event) => {
  const form = event.target;
  if (
    ![
      "target-form",
      "catalog-search",
      "import-form",
      "schedule-form",
      "user-form",
    ].includes(form.id)
  )
    return;
  event.preventDefault();
  const button = form.querySelector("[type=submit]");
  button.disabled = true;
  const data = Object.fromEntries(new FormData(form));
  try {
    if (form.id === "target-form") {
      if ($("#host-key-method").value === "PROBE" &&
          (form.dataset.hostConfirmed !== "true" || form.dataset.confirmedEndpoint !== hostEndpoint(form))) {
        await probeHostKey(true);
        return;
      }
      data.port = Number(data.port);
      await api(
        "/admin/targets" +
          (form.dataset.id ? "/" + encodeURIComponent(form.dataset.id) : ""),
        { method: form.dataset.id ? "PUT" : "POST", body: data },
      );
      toast("대상 연결 정보를 저장했습니다.");
      await render();
    } else if (form.id === "catalog-search") {
      catalogQuery = data.query;
      catalogPage = 0;
      await render();
    } else if (form.id === "import-form") {
      const result = await api("/admin/catalog/import", {
        method: "POST",
        body: new FormData(form),
      });
      toast(`${result.records}개 레코드를 검증하고 반영했습니다.`);
      await render();
    } else if (form.id === "schedule-form") {
      data.enabled = data.enabled === "on";
      await api("/admin/updates", { method: "PUT", body: data });
      toast("정기 업데이트 설정을 저장했습니다.");
      await render();
    } else if (form.id === "user-form") {
      data.enabled = data.enabled === "on";
      const edit = form.dataset.edit === "true";
      await api(
        "/admin/users" + (edit ? "/" + encodeURIComponent(data.username) : ""),
        { method: edit ? "PUT" : "POST", body: data },
      );
      $("#modal").close();
      toast("사용자 정보를 저장했습니다.");
      await render();
    }
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
});
$("#modal-close").addEventListener("click", () => $("#modal").close());
$("#logout").addEventListener("click", async () => {
  try {
    await api("/logout", { method: "POST" });
    location.replace("/login.html");
  } catch (error) {
    toast(error.message);
  }
});
window.addEventListener("hashchange", event => {
  if ($("#service-edit")?.dataset.saving === "true" || ($("#offline-import")?.dataset.saving === "true" && $("#offline-import")?.dataset.complete !== "true")) {
    history.replaceState(null, "", new URL(event.oldURL).hash || "#overview");
    toast("저장이 끝날 때까지 기다려주세요."); return;
  }
  if ((hasAssetDraft() || hasOfflineDraft()) && !window.confirm("저장하지 않은 자산·서비스 입력이 있습니다. 입력을 버리고 이동할까요?")) {
    history.replaceState(null, "", new URL(event.oldURL).hash || "#overview");
    return;
  }
  editingService = null;
  $("#service-editor").close();
  render();
});
(async () => {
  const response = await fetch("/api/session", { cache: "no-store" });
  session = await response.json();
  if (!session.username) {
    location.replace("/login.html");
    return;
  }
  $("#account").textContent =
    session.username + " · " + (admin() ? "관리자" : "사용자");
  document
    .querySelectorAll(".admin-only")
    .forEach((element) => (element.hidden = !admin()));
  await render();
  setInterval(() => {
    if (
      ["overview", "jobs"].includes(currentView) &&
      !$("#modal").open &&
      !document.hidden
    )
      render();
  }, 6000);
})().catch((error) => toast(error.message));
