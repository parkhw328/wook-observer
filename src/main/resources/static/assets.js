"use strict";
const environments = {UNSPECIFIED: "미지정", PRODUCTION: "운영", STAGING: "검증", DEVELOPMENT: "개발", TEST: "테스트"};
const lifecycles = {ACTIVE: "운영", MAINTENANCE: "유지보수", RETIRED: "폐기·사용 종료"};
const serviceTypes = {OTHER: "미분류", MIDDLEWARE: "미들웨어", APPLICATION: "애플리케이션", COMMON: "공통·보안·백업"};
let assetQuery = "", assetEnvironment = "", assetFilter = "", assetDetail = null;
let serviceQuery = "", serviceFilter = "active", serviceColumns = "operations", editingService = null;
const assetLink = id => "#assets/" + encodeURIComponent(id);
const options = (values, value) => Object.entries(values).map(([key, label]) => `<option value="${esc(key)}" ${key === value ? "selected" : ""}>${esc(label)}</option>`).join("");
const filled = value => value ? esc(value) : '<span class="muted">미입력</span>';
const memory = bytes => bytes > 0 ? (bytes / 1024 ** 3).toLocaleString("ko-KR", {maximumFractionDigits: 1}) + " GiB" : "미수집";

function assetKpis(summary) {
  return `<div class="kpis">
    <article class="kpi"><p>등록 자산</p><strong>${number(summary.total)}</strong><small>점검 전 자산 ${number(summary.unscanned)}대</small></article>
    <article class="kpi"><p>서비스 후보·등록</p><strong>${number(summary.services)}</strong><small>보관 처리한 항목 제외</small></article>
    <article class="kpi ${summary.undocumented ? "alert" : ""}"><p>운영 절차 보완</p><strong>${number(summary.undocumented)}</strong><small>시작·종료·상태 확인 방법 미입력</small></article>
    <article class="kpi ${summary.findings ? "alert" : ""}"><p>자산별 최근 발견·의심</p><strong>${number(summary.findings)}</strong><small>자산마다 최근 완료 점검 1회 기준</small></article>
  </div>`;
}

function assetTable(items) {
  if (!items.length) return empty("표시할 자산이 없습니다", "검색 조건을 바꾸거나 점검 대상에서 서버를 등록하세요.", '<a class="button" href="#targets">점검 대상 관리 →</a>');
  return table(["서버 / 자산번호", "환경 / 담당", "OS / 자원", "서비스 / 절차 보완", "최근 점검 / 발견·의심", "관리"], items.map(a => `<tr>
    <td><a href="${assetLink(a.id)}"><b>${esc(a.name)}</b></a><div class="small">${esc(a.host)}</div><div class="small">${esc(a.asset_code || "자산번호 미입력")}</div></td>
    <td>${badge(environments[a.environment] || "미지정")}<div>${filled(a.owner)}</div><div class="small">${esc(lifecycles[a.lifecycle] || "운영")}</div></td>
    <td>${a.inventory ? `${esc(a.inventory.distribution)}<div class="small">CPU ${a.inventory.host.processors || "미수집"} · ${memory(a.inventory.host.physicalMemoryBytes)}</div>` : '<span class="muted">점검 전 · 미수집</span>'}</td>
    <td>${number(a.service_count)}개<div class="small">절차 보완 ${number(a.undocumented_count)}개</div></td>
    <td>${a.latest_status ? badge(a.latest_status) : badge("점검 전")}<div class="small">최근 수집 ${date(a.observed_at)}</div>${a.job_id ? `<span>${number(a.finding_count)}건</span>` : ""}</td>
    <td><a class="button" href="${assetLink(a.id)}">자산 열기</a></td></tr>`));
}

async function assetView() {
  const target = location.hash.startsWith("#assets/") ? decodeURIComponent(location.hash.slice(8)) : "";
  const revision = renderRevision;
  if (target) {
    const data = await api("/assets/" + encodeURIComponent(target));
    if (revision === renderRevision) {
      if (assetDetail?.asset.id !== target) { serviceQuery = ""; serviceFilter = "active"; }
      assetDetail = data;
      editingService = null;
    }
    return assetDetailHtml(data);
  }
  const query = assetQuery.toLocaleLowerCase(), environment = assetEnvironment, filter = assetFilter;
  const {items, summary} = await api("/assets");
  const selected = items.filter(a => (!query || [a.name, a.host, a.asset_code, a.owner, a.location, a.purpose, a.inventory?.distribution].join(" ").toLocaleLowerCase().includes(query))
    && (!environment || (a.environment || "UNSPECIFIED") === environment)
    && (!filter || (filter === "unscanned" && !a.job_id) || (filter === "undocumented" && a.undocumented_count > 0) || (filter === "failed" && a.latest_status === "FAILED")));
  return pageHead("자산 관리", "서버별 자산 정보와 서비스 운영대장을 함께 관리하세요.", '<a class="button" href="#targets">점검 대상 관리 →</a>') + assetKpis(summary)
    + `<section class="panel"><form id="asset-search" class="asset-filters"><label>자산 검색<input name="query" maxlength="200" value="${esc(assetQuery)}" placeholder="서버·주소·자산번호·담당자"></label><label>환경<select name="environment"><option value="">전체 환경</option>${options(environments, environment)}</select></label><label>보완 상태<select name="filter">${options({"": "전체 자산", unscanned: "점검 전", undocumented: "운영 절차 보완", failed: "최근 점검 실패"}, filter)}</select></label><button type="submit">검색</button></form><div class="panel-title"><h2>자산 목록</h2><span class="small">검색 ${number(selected.length)} / 전체 ${number(items.length)}대</span></div>${assetTable(selected)}</section>`;
}

function assetProfile(a) {
  const disabled = admin() ? "" : "disabled";
  const input = (label, name, value, max) => `<label>${label}<input name="${name}" maxlength="${max}" value="${esc(value)}" ${disabled}></label>`;
  return `<details class="panel asset-profile"><summary>자산 정보 ${a.owner ? "· " + esc(a.owner) : "· 담당자·환경·용도를 입력하세요"}</summary><form id="asset-profile" data-target="${esc(a.id)}" data-revision="${a.revision}"><div class="grid">
    ${input("자산번호", "assetCode", a.asset_code, 120)}<label>환경<select name="environment" ${disabled}>${options(environments, a.environment || "UNSPECIFIED")}</select></label>
    ${input("담당자 / 담당파트", "owner", a.owner, 120)}${input("위치 / 소속", "location", a.location, 200)}
    <label>운영 상태<select name="lifecycle" ${disabled}>${options(lifecycles, a.lifecycle || "ACTIVE")}</select></label>${input("서버 용도", "purpose", a.purpose, 1000)}</div>
    <label>자산 메모<textarea name="notes" maxlength="4000" ${disabled}>${esc(a.notes)}</textarea></label>
    <div class="form-actions"><span class="small">${a.updated_at ? esc(a.updated_by) + " · " + date(a.updated_at) : "아직 수기 정보를 저장하지 않았습니다."}</span>${admin() ? '<button type="button" data-action="cancel-profile">입력 취소</button><button type="submit" class="primary">자산 정보 저장</button>' : ""}</div></form></details>`;
}

function assetDetailHtml(data) {
  const a = data.asset, i = a.inventory;
  return pageHead(a.name, `${a.host} · ${a.asset_code || "자산번호 미입력"}`, `<a class="button" href="#assets">자산 목록</a><button data-action="refresh-asset">새로고침</button><button class="primary" data-action="scan" data-id="${esc(a.id)}" ${["RUNNING", "QUEUED"].includes(a.latest_status) ? "disabled" : ""}>점검 시작</button>`)
    + (a.latest_status === "FAILED" ? `<div class="note warn">최근 점검 실패 · ${esc(a.latest_message)} 기존 수집 정보는 ${date(a.observed_at)} 기준입니다.</div>` : "")
    + `<div class="asset-summary panel"><div><span class="small">수집된 호스트 / OS</span><b>${esc(i?.host.name || "미수집")}</b><span>${esc(i?.distribution || "점검 후 표시됩니다")}</span></div><div><span class="small">CPU / 메모리</span><b>${i ? (i.host.processors || "미수집") + " CPU · " + memory(i.host.physicalMemoryBytes) : "미수집"}</b><span>${esc(i?.host.architecture || "")}</span></div><div><span class="small">수집 기준 시각</span><b>${date(a.observed_at)}</b><span>${a.latest_status ? badge(a.latest_status) : "점검 전"} ${a.job_id ? `· 발견·의심 ${number(a.finding_count)}건` : ""}</span></div></div>`
    + assetProfile(a)
    + `<section class="panel"><div class="panel-title"><div><h2>서비스 운영대장</h2><p class="small">서비스·포트·실행계정·경로와 운영 절차를 한 행에서 관리합니다.</p></div>${admin() ? '<button class="primary" data-action="add-service">+ 수동 서비스</button>' : '<span class="badge">조회 권한</span>'}</div>
    <div class="note">자동 수집은 최근 점검의 관측값입니다. 시작·종료·상태 확인 방법은 기록용이며 실행되지 않습니다. 수기 정보는 재점검 후에도 유지됩니다.${i ? `<br>포트 수집: ${esc({COLLECTED:"수집 완료", NOT_COLLECTED:"미수집", FAILED:"조회 실패", UNAVAILABLE:"도구 없음", TIMEOUT:"시간 제한", TRUNCATED:"일부 수집"}[i.socketStatus] || i.socketStatus)} · ${esc(i.socketMessage)} · 수집 제한 ${number(i.issueCount)}건` : ""}</div>
    <form id="service-search" class="asset-filters"><label>서비스 검색<input name="query" maxlength="200" value="${esc(serviceQuery)}" placeholder="서비스·SW·담당·포트·경로"></label><label>표시 항목<select name="filter">${options({active:"관리 중", undocumented:"절차 보완", unobserved:"최근 수집 미확인", archived:"보관함", all:"전체"}, serviceFilter)}</select></label><button type="submit">검색</button></form><div id="service-grid">${serviceGrid(data.services)}</div></section>`
    + `<section class="panel"><div class="panel-title"><h2>이 자산의 최근 점검</h2><span class="small">최근 20회 · 당시 보고서 보존</span></div>${jobsTable(data.jobs)}</section>`;
}

function serviceGrid(services) {
  const query = serviceQuery.toLocaleLowerCase();
  const items = services.filter(s => (serviceFilter === "all" || (serviceFilter === "archived" ? s.archived : !s.archived))
    && (serviceFilter !== "undocumented" || !s.start_command || !s.stop_command || !s.status_command)
    && (serviceFilter !== "unobserved" || s.observation_status === "NOT_OBSERVED")
    && (!query || [s.name, s.owner, s.software, s.bindings, s.install_path, s.data_path, s.log_path, s.observed?.name, s.observed?.bindings.join(" ")].join(" ").toLocaleLowerCase().includes(query)));
  return `<div class="panel-title"><p class="small">표시 ${number(items.length)} / 전체 ${number(services.length)}개 · 넓은 표는 좌우로 스크롤하세요.</p><div class="actions" aria-label="표시 컬럼"><button data-action="service-columns" data-mode="operations" aria-pressed="${serviceColumns === "operations"}">운영 절차</button><button data-action="service-columns" data-mode="all" aria-pressed="${serviceColumns === "all"}">전체 항목</button></div></div><div class="table-wrap service-grid ${serviceColumns === "operations" ? "operations" : ""}" tabindex="0" aria-label="서비스 운영대장 가로 스크롤"><table><thead><tr>${["서비스 / 분류", "SW / 버전", "담당 / 실행계정", "APP · DATA · LOG", "바인딩 포트 / 운영 포트", "시작 방법", "종료 방법", "상태 확인 방법", "연계 대상 / 메모", "관측 / 관리"].map(h => `<th scope="col">${h}</th>`).join("")}</tr></thead><tbody>${items.length ? items.map(serviceRow).join("") : '<tr><td colspan="10">표시할 서비스가 없습니다. 점검으로 후보를 수집하거나 수동 서비스를 추가하세요.</td></tr>'}</tbody></table></div>`;
}

function observedPorts(s) {
  return `<div class="small">자동 관측${s.observation_status === "NOT_OBSERVED" ? " · 이전 수집" : ""}</div><div class="cell-lines">${s.observed?.bindings.length ? s.observed.bindings.map(esc).join("<br>") : '<span class="muted">미확인</span>'}</div>`;
}
function serviceRow(s) {
  const o = s.observed;
  return `<tr data-service="${esc(s.id)}"><td><b>${esc(s.name || o?.name || "이름 미입력")}</b><div class="small">${esc(serviceTypes[s.service_type])}</div><div class="small">${o ? esc(o.category) + " · " + esc({CONTAINER:"컨테이너", PROCESS:"프로세스", SOCKET:"소켓"}[o.kind]) : "수동 등록"}</div></td>
    <td>${filled(s.software)}<div class="small">버전 ${filled(s.software_version)}</div>${o?.image ? `<div class="small">관측 이미지 ${esc(o.image)}</div>` : ""}</td>
    <td>${filled(s.owner)}<div class="small">실행 ${filled(s.run_user || o?.runUser)}</div></td>
    <td><div class="cell-lines"><span class="small">APP / 설치</span><br>${filled(s.install_path)}<br><span class="small">DATA</span><br>${filled(s.data_path)}<br><span class="small">LOG</span><br>${filled(s.log_path)}</div>${o?.executable ? `<details><summary>관측 실행 파일</summary>${esc(o.executable)}</details>` : ""}</td>
    <td>${observedPorts(s)}<div class="small">운영 포트 / 보완</div><div class="cell-lines">${filled(s.bindings)}</div></td>
    <td class="cell-lines">${filled(s.start_command)}</td><td class="cell-lines">${filled(s.stop_command)}</td><td class="cell-lines">${filled(s.status_command)}</td>
    <td><div class="small">연계 대상 / 방향</div><div class="cell-lines">${filled(s.dependencies)}</div><div class="small">메모</div><div class="cell-lines">${filled(s.notes)}</div></td>
    <td>${badge(s.archived ? "보관" : {OBSERVED:"최근 수집 확인", NOT_OBSERVED:"최근 수집 미확인", MANUAL:"수동 등록"}[s.observation_status])}<div class="small">${s.last_seen_at ? date(s.last_seen_at) : "관측 이력 없음"}</div>${o?.pids.length ? `<div class="small">PID ${o.pids.map(number).join(", ")}</div>` : ""}${s.updated_at ? `<div class="small">수정 ${esc(s.updated_by)}<br>${date(s.updated_at)}</div>` : ""}${admin() ? `<button data-action="edit-service" data-id="${esc(s.id)}">편집</button>` : ""}</td></tr>`;
}

function editService(id) {
  if ($("#asset-profile")?.dataset.dirty === "true") { toast("자산 정보 입력을 먼저 저장하거나 취소하세요."); return; }
  if (!assetDetail || editingService) { toast("편집 중인 행을 먼저 저장하거나 취소하세요."); return; }
  const s = id ? assetDetail.services.find(row => row.id === id) : {id:"new", revision:0, service_type:"OTHER"};
  if (!s) return;
  editingService = s.id;
  serviceColumns = "all";
  $(".service-grid").classList.remove("operations");
  document.querySelectorAll('[data-action="service-columns"]').forEach(button => button.setAttribute("aria-pressed", String(button.dataset.mode === "all")));
  const form = "service-edit-" + s.id;
  const field = (label, name, value, max, placeholder = "") => `<label>${label}<textarea name="${name}" form="${form}" maxlength="${max}" placeholder="${esc(placeholder)}">${esc(value)}</textarea></label>`;
  const row = `<tr class="editing" data-service="${esc(s.id)}"><td>${field("서비스명", "name", s.name, 160, s.observed?.name || "서비스명 입력")}<label>구분<select name="serviceType" form="${form}">${options(serviceTypes, s.service_type)}</select></label></td>
    <td>${field("SW", "software", s.software, 200)}${field("버전", "softwareVersion", s.software_version, 200, "확인된 SW 버전")}</td>
    <td>${field("담당파트 / 담당자", "owner", s.owner, 120)}${field("실행계정", "runUser", s.run_user, 120, s.observed?.runUser || "실행계정")}</td>
    <td>${field("APP / 설치위치", "installPath", s.install_path, 2000)}${field("DATA", "dataPath", s.data_path, 2000)}${field("LOG", "logPath", s.log_path, 2000)}</td>
    <td>${observedPorts(s)}${field("운영 포트 / 보완", "bindings", s.bindings, 2000, "예: TCP 8080, 관리 8443")}</td>
    <td>${field("시작 방법", "startCommand", s.start_command, 4000)}</td><td>${field("종료 방법", "stopCommand", s.stop_command, 4000)}</td><td>${field("상태 확인 방법", "statusCommand", s.status_command, 4000)}</td>
    <td>${field("연계 대상 / 통신 방향", "dependencies", s.dependencies, 4000, "예: 내부 → 외부, 목적지:포트, 용도")}${field("비고", "notes", s.notes, 4000)}</td>
    <td><form id="${form}" class="service-edit" data-id="${id || ""}" data-revision="${s.revision}"><label class="check"><input type="checkbox" name="archived" ${s.archived ? "checked" : ""}>보관</label><button type="submit" class="primary">행 저장</button><button type="button" data-action="cancel-service">취소</button><p class="small">빈 수기 값은 미입력으로 저장합니다. 자동 관측은 보존됩니다.</p><p class="error" role="status"></p></form></td></tr>`;
  if (id) $("[data-service='" + id + "']").outerHTML = row;
  else $("#service-grid tbody").insertAdjacentHTML("afterbegin", row);
  $("[data-service='" + s.id + "'] textarea").focus();
  setAssetBusy(true);
}

function setAssetBusy(busy) {
  document.querySelectorAll('#service-search input, #service-search select, #service-search button, [data-action="refresh-asset"], [data-action="add-service"], [data-action="edit-service"], [data-action="service-columns"], #asset-profile input, #asset-profile select, #asset-profile textarea, #asset-profile button, [data-action="scan"]').forEach(el => el.disabled = busy);
}

document.addEventListener("click", async event => {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  if (button.dataset.action === "edit-service") editService(button.dataset.id);
  if (button.dataset.action === "add-service") editService();
  if (button.dataset.action === "cancel-service") {
    editingService = null; $("#service-grid").innerHTML = serviceGrid(assetDetail.services); setAssetBusy(false);
  }
  if (button.dataset.action === "refresh-asset") await render();
  if (button.dataset.action === "cancel-profile") { $("#asset-profile").dataset.dirty = "false"; await render(); }
  if (button.dataset.action === "service-columns") {
    serviceColumns = button.dataset.mode; $("#service-grid").innerHTML = serviceGrid(assetDetail.services);
  }
});

document.addEventListener("submit", async event => {
  const form = event.target;
  if (!["asset-search", "asset-profile", "service-search"].includes(form.id) && !form.matches(".service-edit")) return;
  event.preventDefault();
  const data = Object.fromEntries(new FormData(form));
  const button = form.querySelector('[type="submit"]');
  button.disabled = true;
  try {
    if (form.id === "asset-search") {
      assetQuery = data.query; assetEnvironment = data.environment; assetFilter = data.filter; await render();
    } else if (form.id === "service-search") {
      serviceQuery = data.query; serviceFilter = data.filter; $("#service-grid").innerHTML = serviceGrid(assetDetail.services);
    } else if (form.id === "asset-profile") {
      data.revision = Number(form.dataset.revision);
      await api("/admin/assets/" + encodeURIComponent(form.dataset.target), {method:"PUT", body:data});
      form.dataset.dirty = "false"; toast("자산 정보를 저장했습니다."); await render();
    } else {
      data.revision = Number(form.dataset.revision); data.archived = data.archived === "on";
      const path = "/admin/assets/" + encodeURIComponent(assetDetail.asset.id) + "/services" + (form.dataset.id ? "/" + encodeURIComponent(form.dataset.id) : "");
      await api(path, {method:form.dataset.id ? "PUT" : "POST", body:data});
      editingService = null; toast("서비스 운영 정보를 저장했습니다."); await render();
    }
  } catch (error) {
    if (form.querySelector(".error")) form.querySelector(".error").textContent = error.message;
    toast(error.message);
  } finally { button.disabled = false; }
});
document.addEventListener("input", event => {
  const form = event.target.closest("#asset-profile");
  if (form) {
    form.dataset.dirty = "true";
    document.querySelectorAll('[data-action="refresh-asset"], [data-action="scan"], [data-action="add-service"], [data-action="edit-service"]').forEach(button => button.disabled = true);
  }
});
function hasAssetDraft() { return !!$("tr.editing") || $("#asset-profile")?.dataset.dirty === "true"; }
window.addEventListener("beforeunload", event => {
  if (hasAssetDraft()) { event.preventDefault(); event.returnValue = ""; }
});
