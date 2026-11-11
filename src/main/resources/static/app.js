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
  toastTimer;
const titles = {
  overview: "대시보드",
  targets: "점검 대상",
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
      error.message ||
        (response.status === 403
          ? "접근 권한 또는 로그인 유효 시간을 확인하세요."
          : `요청 실패 (${response.status})`),
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
function pageHead(title, description, action = "") {
  return `<div class="page-head"><div><p class="eyebrow">WORKSPACE / ${esc(currentView.toUpperCase())}</p><h1>${esc(title)}</h1><p>${esc(description)}</p></div><div class="head-actions">${action}</div></div>`;
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
    ["점검 대상", "시작 시각", "상태", "발견·의심", "결과"],
    jobs.map(
      (job) =>
        `<tr><td><b>${esc(job.target_name)}</b><div class="small">${esc(job.requested_by)}</div></td><td>${date(job.created_at)}</td><td>${badge(job.status)}</td><td>${job.status === "COMPLETED" ? number(job.finding_count) + "건" : "—"}</td><td>${job.status === "COMPLETED" ? `<button data-action="detail" data-id="${esc(job.id)}">상세 보기</button> <a class="button" href="/api/jobs/${encodeURIComponent(job.id)}/download/xlsx">XLSX ↓</a>` : `<span class="small">${esc(job.message || "수집 결과를 기다리고 있습니다.")}</span>`}</td></tr>`,
    ),
  );
}
async function overview() {
  const [targets, jobs, catalog] = await Promise.all([
    api("/targets"),
    api("/jobs"),
    api("/catalog/status"),
  ]);
  const completed = jobs.filter((job) => job.status === "COMPLETED");
  const findingCount = completed.reduce(
    (sum, job) => sum + job.finding_count,
    0,
  );
  const counts = Object.fromEntries(
    catalog.counts.map((row) => [row.kind, row.count]),
  );
  return (
    pageHead(
      "서비스 환경을 한눈에",
      "점검 대상과 최근 수집 결과, 취약점 데이터 상태를 확인하세요.",
      `<a class="button primary" href="#targets">${admin() ? "+ 점검 대상 관리" : "점검 시작 →"}</a>`,
    ) +
    `<div class="kpis"><article class="kpi"><p>등록된 점검 대상</p><strong>${number(targets.length)}</strong><small>SSH로 연결하는 Linux 서버</small></article><article class="kpi"><p>진행 중인 점검</p><strong>${jobs.filter((j) => ["RUNNING", "QUEUED"].includes(j.status)).length}</strong><small>동시 2개 · 대기 최대 16개</small></article><article class="kpi ${findingCount ? "alert" : ""}"><p>최근 100회 발견·의심 항목</p><strong>${number(findingCount)}</strong><small>반복 점검의 중복 항목 포함</small></article><article class="kpi"><p>내부 취약점·설정 데이터</p><strong>${number(catalog.counts.reduce((s, c) => s + c.count, 0))}</strong><small>카탈로그 버전 ${catalog.revision}</small></article></div>` +
    `<div class="panel"><div class="panel-title"><h2>최근 점검</h2><a class="small" href="#jobs">전체 이력 보기 →</a></div>${jobsTable(jobs.slice(0, 5))}</div>` +
    `<div class="split"><section class="panel"><div class="panel-title"><h2>점검에서 보고서까지</h2><span class="badge info">Java 설치 없이 기본 점검</span></div><ol class="step-list"><li><span class="number">01</span><div><b>점검 대상 연결</b><p>SSH 계정과 접속 키, 확인한 서버 호스트 키를 등록합니다.</p></div></li><li><span class="number">02</span><div><b>실행 환경 수집과 기준 대조</b><p>프로세스·JVM·컨테이너를 수집하고 내부 CVE·CCE 기준과 대조합니다.</p></div></li><li><span class="number">03</span><div><b>서비스 직군별 보고서</b><p>프로세스·오픈소스·컨테이너 명세를 XLSX, Markdown, JSON으로 확인합니다.</p></div></li></ol></section><section class="panel"><div class="panel-title"><h2>취약점 데이터 상태</h2><a class="small" href="#catalog">목록 보기 →</a></div><div class="inline-metric"><span>CVE / OSV 권고</span><strong>${number(counts.CVE)}건</strong></div><div class="inline-metric"><span>CCE 설정 식별자</span><strong>${number(counts.CCE)}건</strong></div><div class="inline-metric"><span>내부 설정 규칙</span><strong>${number(counts.POLICY)}건</strong></div><p class="small">최근 반영 ${date(catalog.imported_at)}</p><div class="note warn">${catalog.counts.reduce((sum, c) => sum + c.count, 0) <= 5 ? "기본 제공 목록은 시작용 5건입니다. 운영 범위에 맞는 데이터를 반입하세요." : "저장한 데이터의 범위와 갱신 시각을 확인하세요."} 미발견은 전체 시스템의 안전을 의미하지 않습니다.</div></section></div>`
  );
}
async function targetView() {
  const targets = await api("/targets");
  return (
    pageHead(
      "점검 대상",
      "서버에 별도 Java 설치 없이 SSH로 기본 정보를 수집합니다.",
      admin()
        ? '<button class="primary" data-action="add-target">+ 대상 등록</button>'
        : "",
    ) +
    '<div class="note">Linux의 SSH·셸·ps가 기본 수집에 필요합니다. 컨테이너는 Docker 조회 권한, Java 상세는 Python 3 또는 jcmd 등 대상에서 사용할 수 있는 도구에 따라 수집 범위가 달라집니다.</div>' +
    (targets.length
      ? `<div class="target-grid">${targets.map((t) => `<article class="target-card"><span class="badge info">SSH / LINUX</span><h2>${esc(t.name)}</h2><p class="target-address">${esc(t.ssh_user)}@${esc(t.host)}:${t.port}</p><p class="small">${esc(t.description || "등록된 설명이 없습니다.")}</p><div class="actions">${admin() ? `<button class="quiet" data-action="edit-target" data-id="${esc(t.id)}">연결 설정</button>` : ""}<button class="primary" data-action="scan" data-id="${esc(t.id)}">점검 시작 →</button></div></article>`).join("")}</div>`
      : empty(
          "아직 등록된 대상이 없습니다",
          "관리자가 대상 서버의 SSH 접속 정보를 등록하면 점검을 시작할 수 있습니다.",
        ))
  );
}
async function targetForm(id) {
  const target = id
    ? await api("/admin/targets/" + encodeURIComponent(id))
    : {};
  $("#content").innerHTML =
    pageHead(
      id ? "대상 연결 설정" : "새 점검 대상 등록",
      "서버 관리자가 확인한 호스트 공개 키로 접속 대상을 검증합니다.",
    ) +
    `<section class="panel"><form id="target-form" data-id="${esc(id || "")}"><div class="grid"><label>대상 이름<input name="name" required maxlength="120" value="${esc(target.name)}" placeholder="예: 주문 서비스 운영 서버"></label><label>SSH 호스트<input name="host" required maxlength="253" value="${esc(target.host)}" placeholder="192.168.10.20 또는 내부 DNS"></label><label>SSH 포트<input name="port" type="number" min="1" max="65535" required value="${target.port || 22}"></label><label>SSH 계정<input name="sshUser" required maxlength="64" value="${esc(target.ssh_user)}" autocomplete="off"></label></div><label>서버 호스트 공개 키<textarea name="hostKey" required placeholder="ssh-ed25519 AAAA…">${esc(target.host_key)}</textarea><small>서버의 /etc/ssh/ssh_host_ed25519_key.pub에서 유형과 base64 두 필드를 확인하세요. 처음 연결할 때도 자동 신뢰하지 않습니다.</small></label><label>접속용 SSH 개인 키<textarea name="privateKey" ${id ? "" : "required"} autocomplete="off" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"></textarea><small>암호 없는 전용 접속 키를 사용하세요. DB에는 암호화해 저장합니다.${id ? " 빈칸으로 두면 기존 키를 유지합니다." : ""}</small></label><label>설명<textarea name="description" maxlength="1000" placeholder="운영 환경, 담당 팀, 접근 범위 등을 기록하세요.">${esc(target.description)}</textarea></label><div class="form-actions"><button type="button" data-action="cancel-target">취소</button><button class="primary" type="submit">연결 정보 저장</button></div></form></section>`;
}
async function catalogView() {
  const [status, rows] = await Promise.all([
    api("/catalog/status"),
    api(
      "/catalog?query=" +
        encodeURIComponent(catalogQuery) +
        "&page=" +
        catalogPage,
    ),
  ]);
  return (
    pageHead(
      "취약점 데이터",
      "내부에 저장한 CVE·CCE와 설정 규칙을 조회합니다.",
      admin()
        ? '<a class="button primary" href="#updates">데이터 반입 / 업데이트 →</a>'
        : "",
    ) +
    `<div class="note">카탈로그 버전 <b>${status.revision}</b> · 최근 반영 ${date(status.imported_at)}<br>목록 출처: ${esc(status.source)}<br>버전 비교는 Maven 패키지를 지원합니다. 다른 생태계의 OSV 항목은 저장·조회만 지원합니다.</div><section class="panel"><form class="search" id="catalog-search"><label>식별자 / 패키지 검색<input name="query" value="${esc(catalogQuery)}" placeholder="CVE, GHSA, CCE 또는 패키지 이름"></label><button type="submit">검색</button></form>` +
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
    `<div class="form-actions"><button data-action="catalog-prev" ${catalogPage === 0 ? "disabled" : ""}>이전</button><span class="small">${catalogPage + 1} 페이지 · 최대 50건</span><button data-action="catalog-next" ${rows.length < 50 ? "disabled" : ""}>다음</button></div></section>`
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
    `<h2>${edit ? "계정 편집" : "사용자 추가"}</h2><form id="user-form" data-edit="${edit}"><label>아이디<input name="username" required minlength="3" maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,63}" value="${esc(button.dataset.user)}" ${edit ? "readonly" : ""} autocomplete="off"></label><label>${edit ? "새 비밀번호 (빈칸이면 유지)" : "초기 비밀번호"}<input name="password" type="password" ${edit ? "" : "required"} minlength="12" autocomplete="new-password"><small>12자 이상, UTF-8 72바이트 이하</small></label><label>권한<select name="role"><option value="USER" ${button.dataset.role === "USER" ? "selected" : ""}>사용자 · 점검 / 조회</option><option value="ADMIN" ${button.dataset.role === "ADMIN" ? "selected" : ""}>관리자 · 전체 관리</option></select></label><label class="check"><input name="enabled" type="checkbox" ${!edit || button.dataset.enabled === "true" ? "checked" : ""}>계정 활성화</label><button class="primary" type="submit">저장</button></form>`;
  $("#modal").showModal();
}
async function detail(id) {
  const report = await api("/jobs/" + encodeURIComponent(id));
  const all = report.assessment?.findings || [];
  const found = all.filter((f) => ["발견", "의심"].includes(f.status));
  const findings = all.slice(0, 500);
  const count = report.javaProcesses.reduce(
    (sum, j) => sum + j.libraries.length,
    0,
  );
  $("#modal-content").innerHTML =
    `<div class="details-header"><div><p class="eyebrow">INSPECTION REPORT</p><h2>${esc(report.host.name)}</h2></div><span class="badge good">수집 완료</span></div><p class="small">${date(report.collectedAt)} · ${esc(report.host.scope)}</p><div class="actions">${downloads(id)}</div><div class="detail-grid"><div>프로세스<strong>${number(report.processes.length)}</strong></div><div>라이브러리 항목<strong>${number(count)}</strong></div><div>컨테이너<strong>${number(report.containers.length)}</strong></div></div><div class="note">카탈로그 버전 ${report.assessment?.catalogRevision ?? "미실시"} · 발견·의심 ${found.length}건<br>${esc(report.assessment?.scope || "취약점 대조를 실시하지 않았습니다.")}</div><h3>취약점 판정 ${all.length > 500 ? "(첫 500건; 전체는 보고서 다운로드)" : ""}</h3>` +
    (findings.length
      ? table(
          ["종류 / ID", "취약점발견", "대상", "근거"],
          findings.map(
            (f) =>
              `<tr><td>${esc(f.kind)}<br>${esc(f.id || "기준 없음")}</td><td>${badge(f.status)}</td><td>${esc(f.asset)}</td><td>${esc(f.evidence)}</td></tr>`,
          ),
        )
      : empty(
          "대조 가능한 항목이 없습니다",
          "라이브러리 식별 정보, 적용 규칙과 수집 제한을 확인하세요.",
        )) +
    "<h3>수집 제한 및 오류</h3>" +
    table(
      ["계층", "대상", "내용"],
      report.issues.map(
        (issue) =>
          `<tr><td>${esc(issue.layer)}</td><td>${esc(issue.target)}</td><td>${esc(issue.message)}</td></tr>`,
      ),
    );
  $("#modal").showModal();
}
async function render() {
  if (!session) return;
  const requested = location.hash.slice(1) || "overview";
  currentView = Object.hasOwn(titles, requested) ? requested : "overview";
  if (["users", "updates"].includes(currentView) && !admin())
    currentView = "overview";
  const view = currentView;
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
    if (currentView === view) $("#content").innerHTML = html;
  } catch (error) {
    toast(error.message);
  }
}
document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  button.disabled = true;
  try {
    switch (button.dataset.action) {
      case "add-target":
        await targetForm();
        break;
      case "edit-target":
        await targetForm(button.dataset.id);
        break;
      case "cancel-target":
        await render();
        break;
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
      case "catalog-prev":
        catalogPage = Math.max(0, catalogPage - 1);
        await render();
        break;
      case "catalog-next":
        catalogPage++;
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
window.addEventListener("hashchange", render);
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
